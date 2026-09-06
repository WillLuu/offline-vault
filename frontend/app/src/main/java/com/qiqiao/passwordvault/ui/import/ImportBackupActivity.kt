package com.qiqiao.passwordvault.ui.import

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.model.MergeReport

// 导入备份：选择 .vault 文件 + 输入密码 -> 展示合并报告。
class ImportBackupActivity : PwdBaseActivity() {

    private var fileBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_backup)

        val etPassword = findViewById<EditText>(R.id.etPassword)
        val tvFileName = findViewById<TextView>(R.id.tvFileName)
        val tvReport = findViewById<TextView>(R.id.tvReport)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnChoose).setOnClickListener {
            val pick = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            com.qiqiao.passwordvault.MainApplication.externalPickerStarted() // 选择文件期间豁免自动锁
            // ponytail: 用 startActivityForResult（已废弃但 API26 稳定，未引 Activity Result API） | 触发升级阈值：迁移到 RegisterForActivityResult
            startActivityForResult(pick, 200)
        }

        findViewById<Button>(R.id.btnImport).setOnClickListener {
            // 审计 P2#6：会话若已锁（后台被自动锁但本页残留前台），先引导回解锁页，避免 LockedException 裸 Toast
            if (Vault.data.isLocked()) {
                Toast.makeText(this, R.string.import_need_unlock, Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, com.qiqiao.passwordvault.ui.unlock.UnlockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return@setOnClickListener
            }
            val bytes = fileBytes
            val pw = etPassword.text.toString()
            if (bytes == null) {
                Toast.makeText(this, R.string.import_no_file, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pw.isBlank()) {
                etPassword.error = getString(R.string.edit_name_required)
                return@setOnClickListener
            }
            Vault.data.importVault(bytes, pw) { res ->
                runOnUiThread {
                    res.onSuccess { report -> tvReport.text = renderReport(report); tvReport.visibility = View.VISIBLE }
                        .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun renderReport(r: MergeReport): String = buildString {
        appendLine(getString(R.string.import_cat_added, r.categoriesAdded))
        appendLine(getString(R.string.import_cat_merged, r.categoriesMerged))
        appendLine(getString(R.string.import_entries_added, r.entriesAdded))
        appendLine(getString(R.string.import_entries_updated, r.entriesUpdated))
        appendLine(getString(R.string.import_entries_skipped, r.entriesSkipped))
    }

    @Deprecated("Deprecated in favor of Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == 200 && resultCode == RESULT_OK) {
                val uri: Uri? = data?.data
                uri?.let {
                    contentResolver.openInputStream(it)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size < 37) { // 最小 .vault 头（magic+ver+algo+params+盐8B+nonce12B）
                            fileBytes = null
                            Toast.makeText(this, R.string.import_file_invalid, Toast.LENGTH_SHORT).show()
                            findViewById<TextView>(R.id.tvFileName).text =
                                getString(R.string.import_no_file)
                        } else {
                            fileBytes = bytes
                            findViewById<TextView>(R.id.tvFileName).text =
                                it.lastPathSegment ?: "backup.vault"
                        }
                    }
                }
            }
    }
}
