package com.qiqiao.passwordvault.ui.edit

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.model.CategoryRow
import com.qiqiao.passwordvault.model.EntryInput
import com.qiqiao.passwordvault.model.ExtraField
import com.qiqiao.passwordvault.ui.common.NeuDialog
import com.qiqiao.passwordvault.ui.generator.PasswordGeneratorActivity
import com.qiqiao.passwordvault.ui.import.DataManageActivity
import com.qiqiao.passwordvault.ui.list.PasswordListActivity
import com.qiqiao.passwordvault.ui.settings.SettingsActivity
import com.qiqiao.passwordvault.util.FieldTemplateStore
import com.qiqiao.passwordvault.util.NavAnim

// 新增 / 编辑条目：表单 + 唤起生成器。真实加解密由 backend 负责。
// 词条动态化 v3（2026-09-06）：
//  - 固定词条：平台 / 帐号 / 密码（始终凹陷输入，不可删）。
//  - 模板词条：邮箱 / 网站 / 备注 + 自定义，全局同步（FieldTemplateStore）。行 = [词条名 ✎改名 … ✕] + 凹陷内容输入框(常显)。
//  - 备注：永远排最后、内容框最大（多行）。网站/备注值仍映射独立列，其余进加密 extras。
class EntryEditActivity : PwdBaseActivity() {

    private val GEN_REQUEST = 100
    private var editId: Long = -1
    private var categories: List<CategoryRow> = emptyList()

    private lateinit var etName: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var spCategory: Spinner
    private lateinit var llExtraFields: LinearLayout

    // 词条行句柄。
    private class FieldRow(val view: View, val tvLabel: TextView, val etValue: EditText) {
        val label: String get() = tvLabel.text.toString()
        fun currentValue(): String = etValue.text.toString()
    }

    private val fieldRows = mutableListOf<FieldRow>()

    // 密码明文揭示态（2026-09-06 用户需求：编辑时可见密码，核对是否改对）
    private var pwRevealed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry_edit)

        editId = intent.getLongExtra("id", -1)
        findViewById<TextView>(R.id.tvTitle).setText(
            if (editId < 0) R.string.edit_title_new else R.string.edit_title_edit
        )
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        setupNavBar()

        etName = findViewById(R.id.etName)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        spCategory = findViewById(R.id.spCategory)
        llExtraFields = findViewById(R.id.llExtraFields)

        findViewById<Button>(R.id.btnAddField).setOnClickListener { showAddFieldDialog() }
        findViewById<Button>(R.id.btnGenerate).setOnClickListener {
            startActivityForResult(
                Intent(this, PasswordGeneratorActivity::class.java), GEN_REQUEST
            )
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        // 底部常驻保存栏（2026-09-06 用户需求）：长表单滚到下方也能直接保存，同一 handler
        findViewById<Button>(R.id.btnSaveBottom).setOnClickListener { save() }

        // 密码显隐眼睛（2026-09-06 用户需求）：默认掩码，点眼切明文核对；视觉态与列表卡/详情页 eye 一致
        val btnEyePw = findViewById<ImageButton>(R.id.btnEyePw)
        val eyePwContainer = findViewById<com.qiqiao.passwordvault.ui.common.NeuSurface>(R.id.eyeContainerPw)
        btnEyePw.setOnClickListener {
            pwRevealed = !pwRevealed
            etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                if (pwRevealed) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            etPassword.setSelection(etPassword.text.length) // inputType 切换后光标回末尾
            eyePwContainer.direction = if (pwRevealed)
                com.qiqiao.passwordvault.ui.common.NeuSurface.Direction.INSET
            else com.qiqiao.passwordvault.ui.common.NeuSurface.Direction.RAISED
            btnEyePw.imageTintList = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this,
                    if (pwRevealed) R.color.primary else R.color.on_surface_variant))
            btnEyePw.contentDescription = getString(
                if (pwRevealed) R.string.detail_hide else R.string.detail_show)
        }

        Vault.data.listCategories { res ->
            res.onSuccess { cats ->
                runOnUiThread {
                    categories = cats
                    spCategory.adapter = ArrayAdapter(
                        this, android.R.layout.simple_spinner_item,
                        cats.map { it.name }
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    if (editId >= 0) prefill(editId) else rebuildFieldRows(emptyValues = true)
                }
            }
        }
    }

    // ---- 底部导航（本页高亮"＋"；方向感知左右滑动切页）----
    private fun setupNavBar() {
        findViewById<View>(R.id.btnNavHome).setOnClickListener {
            navTo(Intent(this, PasswordListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), NavAnim.DEST_HOME)
        }
        findViewById<View>(R.id.btnNavData).setOnClickListener {
            navTo(Intent(this, DataManageActivity::class.java), NavAnim.DEST_DATA)
        }
        // btnNavAdd = 当前页
        findViewById<View>(R.id.btnNavSettings).setOnClickListener {
            navTo(Intent(this, SettingsActivity::class.java), NavAnim.DEST_SETTINGS)
        }
    }

    // 底部导航切页：方向感知左右滑动（NavAnim 单一事实源；本页 = 新增(2)）。
    private fun navTo(intent: Intent, to: Int) {
        NavAnim.go(this, intent, NavAnim.DEST_ADD, to)
    }

    // ---- 词条区 ----

    // 行序：备注永远排最后（模板内备注先摘出，末尾再补）。
    private fun rowOrder(labels: List<String>): List<String> {
        val notes = getString(R.string.edit_notes)
        return labels.filter { it != notes } + labels.filter { it == notes }
    }

    // emptyValues=true（新增）：模板词条空行；否则按 values[label] 填（缺失 → 空行，仍展示词条）。
    private fun rebuildFieldRows(emptyValues: Boolean = false, values: Map<String, String> = emptyMap()) {
        fieldRows.clear()
        llExtraFields.removeAllViews()
        val notes = getString(R.string.edit_notes)
        val template = FieldTemplateStore.load(this)
        val seen = mutableSetOf<String>()
        rowOrder(template).forEach { label ->
            addFieldRow(label, if (emptyValues) "" else (values[label] ?: ""))
            seen.add(label)
        }
        // 旧数据词条（不在模板）逐条展示（备注不会出现在此处——备注有独立列）
        values.forEach { (label, v) ->
            if (!seen.contains(label) && label != notes && v.isNotBlank()) addFieldRow(label, v)
        }
    }

    private fun addFieldRow(label: String, value: String) {
        val view = layoutInflater.inflate(R.layout.view_edit_field_row, llExtraFields, false)
        val row = FieldRow(
            view = view,
            tvLabel = view.findViewById(R.id.tvFieldLabel),
            etValue = view.findViewById(R.id.etFieldValue)
        )
        row.tvLabel.text = label
        row.etValue.setText(value)
        if (label == getString(R.string.edit_notes)) {
            // 备注：内容框最大（多行），便于长文本
            val big = (96 * resources.displayMetrics.density).toInt()
            row.etValue.minHeight = big
            row.etValue.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            row.etValue.gravity = android.view.Gravity.TOP
            row.etValue.setPadding(
                row.etValue.paddingLeft, (10 * resources.displayMetrics.density).toInt(),
                row.etValue.paddingRight, row.etValue.paddingBottom
            )
        }
        view.findViewById<View>(R.id.btnRenameField).setOnClickListener { showRenameFieldDialog(row) }
        view.findViewById<View>(R.id.btnDeleteField).setOnClickListener { confirmDeleteField(row) }
        llExtraFields.addView(view)
        fieldRows.add(row)
    }

    private fun showAddFieldDialog() {
        NeuDialog.showInput(
            this,
            title = getString(R.string.edit_add_field_title),
            hint = getString(R.string.edit_add_field_hint),
            onConfirm = { input ->
                val label = input.trim()
                if (label.isBlank()) {
                    Toast.makeText(this, R.string.edit_name_required, Toast.LENGTH_SHORT).show()
                    return@showInput false
                }
                if (FieldTemplateStore.load(this).contains(label) ||
                    fieldRows.any { it.label == label }
                ) {
                    Toast.makeText(this, R.string.edit_field_exists, Toast.LENGTH_SHORT).show()
                    return@showInput false
                }
                FieldTemplateStore.add(this, label)   // 全局模板：所有密码卡片同步
                addFieldRow(label, "")
                true
            }
        )
    }

    // ✎：重命名词条（全局模板同步改；本卡片内容框里的值随保存写入新词条名）。
    private fun showRenameFieldDialog(row: FieldRow) {
        NeuDialog.showInput(
            this,
            title = getString(R.string.edit_rename_field_title),
            hint = getString(R.string.edit_rename_field_hint),
            prefill = row.label,
            onConfirm = { input ->
                val newLabel = input.trim()
                if (newLabel.isBlank() || newLabel == row.label) return@showInput true
                if (FieldTemplateStore.load(this).contains(newLabel) ||
                    fieldRows.any { it !== row && it.label == newLabel }
                ) {
                    Toast.makeText(this, R.string.edit_field_exists, Toast.LENGTH_SHORT).show()
                    return@showInput false
                }
                val wasTemplate = FieldTemplateStore.load(this).contains(row.label)
                if (wasTemplate) {
                    FieldTemplateStore.rename(this, row.label, newLabel)
                } else {
                    FieldTemplateStore.add(this, newLabel)  // 旧孤儿词条升级进模板
                }
                row.tvLabel.text = newLabel
                true
            }
        )
    }

    private fun confirmDeleteField(row: FieldRow) {
        val inTemplate = FieldTemplateStore.load(this).contains(row.label)
        NeuDialog.showConfirm(
            this,
            title = getString(R.string.edit_delete_field),
            message = if (inTemplate) {
                getString(R.string.edit_field_delete_confirm, row.label)
            } else {
                getString(R.string.edit_field_delete_confirm_self)
            },
            danger = true,
            okText = getString(R.string.common_ok),
            onOk = {
                if (inTemplate) {
                    FieldTemplateStore.remove(this, row.label)  // 全局模板删除
                }
                llExtraFields.removeView(row.view)
                fieldRows.remove(row)
            }
        )
    }

    private fun prefill(id: Long) {
        Vault.data.getEntry(id) { res ->
            res.onSuccess { entry ->
                runOnUiThread {
                    entry ?: return@runOnUiThread
                    etName.setText(entry.name)
                    etUsername.setText(entry.username)
                    etPassword.setText(entry.password)
                    val websiteStr = getString(R.string.edit_website)
                    val notesStr = getString(R.string.edit_notes)
                    val values = linkedMapOf<String, String>()
                    if (entry.website.isNotBlank()) values[websiteStr] = entry.website
                    if (entry.notes.isNotBlank()) values[notesStr] = entry.notes
                    entry.extras.forEach { values[it.label] = it.value }
                    rebuildFieldRows(values = values)
                    val idx = categories.indexOfFirst { it.id == entry.categoryId }
                    if (idx >= 0) spCategory.setSelection(idx)
                }
            }
        }
    }

    private fun selectedCategoryId(): Long? {
        val pos = spCategory.selectedItemPosition
        if (pos < 0 || pos >= categories.size) return null
        return categories[pos].id
    }

    // 收集词条值：模板词条"网站"→website 列、"备注"→notes 列，其余非空进 extras。
    private fun collectFieldRows(): Triple<String, String, List<ExtraField>> {
        var website = ""
        var notes = ""
        val extras = mutableListOf<ExtraField>()
        val websiteStr = getString(R.string.edit_website)
        val notesStr = getString(R.string.edit_notes)
        for (row in fieldRows) {
            val value = row.currentValue()
            when (row.label) {
                websiteStr -> website = value.trim()
                notesStr -> notes = value
                else -> if (value.isNotBlank()) extras.add(ExtraField(row.label, value.trim()))
            }
        }
        return Triple(website, notes, extras)
    }

    private fun save() {
        val name = etName.text.toString().trim()
        if (name.isBlank()) {
            etName.error = getString(R.string.edit_name_required)
            return
        }
        val (website, notes, extras) = collectFieldRows()
        val input = EntryInput(
            name = name,
            username = etUsername.text.toString().trim(),
            password = etPassword.text.toString(),
            website = website,
            notes = notes,
            categoryId = selectedCategoryId(),
            extras = extras
        )
        // 修复：保存失败不再静默（原实现 res 失败时无任何提示，用户误以为已保存）
        if (editId >= 0) {
            Vault.data.updateEntry(editId, input) { res ->
                runOnUiThread {
                    if (res.isSuccess) finish()
                    else Toast.makeText(this, R.string.edit_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Vault.data.createEntry(input) { res ->
                runOnUiThread {
                    if (res.isSuccess) finish()
                    else Toast.makeText(this, R.string.edit_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ponytail: 使用 startIntentForResult（已废弃但 API26 稳定） | 触发升级阈值：需改用 Activity Result API / 共享 VM
    @Deprecated("Deprecated in favor of Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GEN_REQUEST && resultCode == RESULT_OK) {
            val pw = data?.getStringExtra("password")
            if (!pw.isNullOrEmpty()) etPassword.setText(pw)
        }
    }
}
