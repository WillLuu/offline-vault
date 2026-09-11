package com.qiqiao.passwordvault.ui.import

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.model.EntryInput
import com.qiqiao.passwordvault.ui.PwdBaseActivity
import vault.NoteParser
import vault.ParsedEntry

/**
 * 批量录入：粘贴自由文本笔记 → 解析为多条 → 预览可编辑 → 顺序写入。
 * 分类按名称匹配已有分类，没有则自动新建（追加到分类列表末尾）。
 */
class BatchImportActivity : PwdBaseActivity() {

    private lateinit var etPaste: EditText
    private lateinit var btnParse: Button
    private lateinit var tvCount: TextView
    private lateinit var llPreview: LinearLayout
    private lateinit var btnImport: Button

    private val entries = mutableListOf<ParsedEntry>()
    private val catMap = mutableMapOf<String, Long>()

    // 新建分类的排序值：从现有分类 max(sort_order)+1 起递增，追加到末尾。
    // 修复：原实现一律 sortOrder=0，批量录入新建的分类会全部插队到列表/chips 最前，打乱用户已拖拽排好的顺序。
    private var nextSortOrder = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_import)

        etPaste = findViewById(R.id.etPaste)
        btnParse = findViewById(R.id.btnParse)
        tvCount = findViewById(R.id.tvCount)
        llPreview = findViewById(R.id.llPreview)
        btnImport = findViewById(R.id.btnImport)
        // 修复：初始为真禁用态（isEnabled=false + 降透明度）。原实现只设 alpha=0.45 未禁用，
        // 未解析就点「写入」会误报「没有勾选可写入的条目」。
        setImportEnabled(false)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnParse.setOnClickListener { doParse() }
        btnImport.setOnClickListener { doImport() }
    }

    private fun setImportEnabled(on: Boolean) {
        btnImport.isEnabled = on
        btnImport.alpha = if (on) 1f else 0.45f
    }

    private fun doParse() {
        val text = etPaste.text.toString()
        entries.clear()
        entries.addAll(NoteParser.parse(text))
        llPreview.removeAllViews()

        if (entries.isEmpty()) {
            tvCount.visibility = View.GONE
            setImportEnabled(false)
            btnImport.text = getString(R.string.batch_import_idle) // 复位，避免残留上次的「写入 N 条」
            Toast.makeText(this, R.string.batch_parse_empty, Toast.LENGTH_SHORT).show()
            return
        }

        entries.forEachIndexed { i, e ->
            val view = layoutInflater.inflate(R.layout.item_batch_entry, llPreview, false)
            view.findViewById<TextView>(R.id.tvIndex).text = "第 ${i + 1} 条"
            view.findViewById<TextView>(R.id.tvPreview).text =
                e.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: ""
            view.findViewById<EditText>(R.id.etName).setText(e.name)
            view.findViewById<EditText>(R.id.etUsername).setText(e.username)
            view.findViewById<EditText>(R.id.etPassword).setText(e.password)
            view.findViewById<EditText>(R.id.etWebsite).setText(e.website)
            view.findViewById<EditText>(R.id.etCategory).setText(e.category)
            view.findViewById<EditText>(R.id.etNotes).setText(e.notes)
            view.findViewById<CheckBox>(R.id.cbInclude).isChecked = true
            llPreview.addView(view)
        }

        tvCount.visibility = View.VISIBLE
        tvCount.text = getString(R.string.batch_count, entries.size)
        setImportEnabled(true)
        btnImport.text = getString(R.string.batch_import, entries.size)
    }

    private fun doImport() {
        // 修复：导入前检查锁态（与 ImportBackupActivity P2#6 一致）。会话被自动锁后分类读取不受限、
        // 但 createEntry 全部抛 LockedException，原实现只会得到「已写入 0/N」而无任何原因提示。
        if (Vault.data.isLocked()) {
            Toast.makeText(this, R.string.batch_need_unlock, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, com.qiqiao.passwordvault.ui.unlock.UnlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
            return
        }

        val toWrite = mutableListOf<ParsedEntry>()
        for (i in entries.indices) {
            val view = llPreview.getChildAt(i)
            val include = view.findViewById<CheckBox>(R.id.cbInclude).isChecked
            if (!include) continue
            val e = ParsedEntry(
                name = view.findViewById<EditText>(R.id.etName).text.toString().trim(),
                username = view.findViewById<EditText>(R.id.etUsername).text.toString().trim(),
                password = view.findViewById<EditText>(R.id.etPassword).text.toString().trim(),
                website = view.findViewById<EditText>(R.id.etWebsite).text.toString().trim(),
                category = view.findViewById<EditText>(R.id.etCategory).text.toString().trim(),
                notes = view.findViewById<EditText>(R.id.etNotes).text.toString().trim()
            )
            if (e.name.isBlank() && e.username.isBlank() && e.password.isBlank() && e.website.isBlank()) continue
            toWrite.add(e)
        }
        if (toWrite.isEmpty()) {
            Toast.makeText(this, R.string.batch_none_selected, Toast.LENGTH_SHORT).show()
            return
        }

        setImportEnabled(false)
        btnImport.text = getString(R.string.batch_importing)

        val failedNames = mutableListOf<String>()
        Vault.data.listCategories { res ->
            val cats = res.getOrNull().orEmpty()
            cats.forEach { catMap[it.name.trim()] = it.id }
            // 新分类追加到末尾：max(sort_order)+1 起递增（修复原先一律 0 导致的插队）
            nextSortOrder = (cats.maxOfOrNull { it.sortOrder } ?: -1) + 1
            importSequential(toWrite, 0, 0, failedNames) { ok ->
                runOnUiThread {
                    val msg = if (failedNames.isEmpty()) {
                        getString(R.string.batch_done, ok, toWrite.size)
                    } else {
                        // 失败明细最多列 3 条，避免 Toast 过长
                        getString(
                            R.string.batch_partial_failed, ok, toWrite.size,
                            failedNames.take(3).joinToString("、") +
                                if (failedNames.size > 3) " 等${failedNames.size}条" else ""
                        )
                    }
                    Toast.makeText(this@BatchImportActivity, msg, Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun importSequential(
        list: List<ParsedEntry>,
        index: Int,
        ok: Int,
        failedNames: MutableList<String>,
        onDone: (Int) -> Unit
    ) {
        if (index >= list.size) {
            onDone(ok)
            return
        }
        val e = list[index]
        fun proceed(catId: Long?) {
            Vault.data.createEntry(EntryInput(e.name, e.username, e.password, e.website, e.notes, catId)) { res ->
                if (res.isSuccess) {
                    importSequential(list, index + 1, ok + 1, failedNames, onDone)
                } else {
                    failedNames.add(e.name.ifBlank { "第${index + 1}条" })
                    importSequential(list, index + 1, ok, failedNames, onDone)
                }
            }
        }
        val catText = e.category.trim()
        if (catText.isEmpty()) {
            proceed(null)
            return
        }
        val existing = catMap[catText]
        if (existing != null) {
            proceed(existing)
            return
        }
        Vault.data.createCategory(catText, nextSortOrder) { res ->
            val id = res.getOrNull()
            if (id != null && id > 0) {
                catMap[catText] = id
                nextSortOrder++
                proceed(id)
            } else {
                // 修复：createCategory 底层 db.insert 失败返回 -1（不抛异常），原实现会把 -1 当真实
                // categoryId 写入，产生悬空引用（SQLite 默认不启用外键）→ 条目不属于任何分类且
                // 分类筛选永远选不中。失败时降级为未分类（null），并计入失败明细之外的可写路径。
                proceed(null)
            }
        }
    }
}
