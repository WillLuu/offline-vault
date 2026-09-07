package com.qiqiao.passwordvault.ui.export

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import java.io.File

// 导出备份：选择主密码 / 独立导出密码，确认导出 -> 生成 .vault 字节（私有目录暂存）-> SAF 让用户选保存位置。
// 审计 2026-09-06 P2#4：原实现仅写私有 filesDir 用户取不出备份 → 改为 ACTION_CREATE_DOCUMENT 写用户可选位置。
class ExportBackupActivity : PwdBaseActivity() {

    private var pendingFileName: String? = null
    private val reqSaveFile = 301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export_backup)

        val rgMode = findViewById<RadioGroup>(R.id.rgMode)
        val rbMaster = findViewById<RadioButton>(R.id.rbMaster)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val etConfirm = findViewById<EditText>(R.id.etConfirm)
        val lblPw = findViewById<TextView>(R.id.lblPw)
        val lblConfirm = findViewById<View>(R.id.lblConfirm)
        val neuConfirm = findViewById<View>(R.id.neuConfirm)

        // 模式联动：
        //  - 使用主密码：仅一个框，标签"主密码"（导出要用主密码再走 Argon2id 派生 KEK）；
        //  - 使用独立导出密码：两个框（导出密码 + 确认导出密码）。
        fun applyMode(independent: Boolean) {
            val single = !independent
            lblPw.text = getString(if (single) R.string.export_master_label else R.string.export_password)
            lblConfirm.visibility = if (single) View.GONE else View.VISIBLE
            neuConfirm.visibility = if (single) View.GONE else View.VISIBLE
            etPassword.text.clear()
            etConfirm.text.clear()
        }
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            applyMode(checkedId == R.id.rbIndependent)
        }
        applyMode(false) // 默认主密码

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            val useMaster = rbMaster.isChecked
            val pw = etPassword.text.toString()
            // 独立导出密码：二次一致 + 最小长度（弱导出密码可被离线暴力，审计 P2#5）
            if (!useMaster) {
                if (pw.length < MIN_EXPORT_PW_LEN) {
                    etPassword.error = getString(R.string.export_pw_too_short)
                    return@setOnClickListener
                }
                if (pw != etConfirm.text.toString()) {
                    etConfirm.error = getString(R.string.export_password_mismatch)
                    return@setOnClickListener
                }
            } else if (pw.isBlank()) {
                etPassword.error = getString(R.string.edit_name_required)
                return@setOnClickListener
            }
            Vault.data.exportVault(pw, useMaster) { res ->
                runOnUiThread {
                    res.onSuccess { fileName ->
                        pendingFileName = fileName
                        pickSaveLocation(fileName) // SAF 选保存位置后再进入完成页
                    }.onFailure {
                        Toast.makeText(this, it.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun pickSaveLocation(name: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        com.qiqiao.passwordvault.MainApplication.externalPickerStarted() // 选择器期间豁免自动锁
        startActivityForResult(intent, reqSaveFile)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqSaveFile) {
            val uri: Uri? = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                // 仅在真正写入用户所选位置成功后，才由 writeToExternal 内部进入"导出完成"页。
                writeToExternal(uri)
            } else {
                // 审计 F1：用户取消（或选择器异常）时，用户可访问位置未写入任何文件，
                // 绝不能进"导出完成"页（曾误报成功，用户以为已拿到备份实则没有）。
                // 同时清理私有暂存 .vault，避免副本滞留 filesDir。
                deleteScratchFile()
                Toast.makeText(this, R.string.export_cancelled, Toast.LENGTH_SHORT).show()
                // 停留导出页：用户可重新点击导出再次选择位置
            }
        }
    }

    // 删除导出流程产生的私有暂存文件（取消/放弃导出时调用），防密文副本滞留 filesDir。
    private fun deleteScratchFile() {
        val name = pendingFileName ?: return
        runCatching { File(filesDir, name).delete() }
            .onFailure { android.util.Log.w("Export", "清理暂存文件失败: $name") }
        pendingFileName = null
    }

    // 把私有暂存文件内容写入用户所选 uri，成功后删除本地副本（防明文残留），再进完成页。
    private fun writeToExternal(uri: Uri) {
        // SAF 选择期间若会话已被自动锁（切后台），先引导解锁，避免半途写入/状态错乱
        if (Vault.data.isLocked()) {
            Toast.makeText(this, R.string.import_need_unlock, Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(this, com.qiqiao.passwordvault.ui.unlock.UnlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
            return
        }
        val name = pendingFileName ?: "backup.vault"
        val src = File(filesDir, name)
        try {
            check(src.exists() && src.length() > 0) { "导出暂存文件缺失或为空" }
            val bytes = src.readBytes()
            val out = contentResolver.openOutputStream(uri)
                ?: throw java.io.IOException("无法打开所选位置")
            try {
                out.write(bytes)
                out.flush()
            } finally {
                out.close()
            }
            src.delete() // 写入成功才删本地副本
            goDone()
        } catch (e: Exception) {
            android.util.Log.e("Export", "SAF 写入失败", e)
            Toast.makeText(
                this,
                getString(R.string.export_save_failed) + "：" + (e.message ?: "未知错误"),
                Toast.LENGTH_LONG
            ).show()
            // 不跳"成功"页、不 finish：用户可重试（私有暂存文件保留）
        }
    }

    private fun goDone() {
        val name = pendingFileName ?: "backup.vault"
        startActivity(Intent(this, ExportDoneActivity::class.java).putExtra("fileName", name))
        finish()
    }

    companion object {
        // 独立导出密码最小长度：低于主密码路径的 KEK 派生强度，离线暴力成本低，故要求更长（审计 P2#5）
        private const val MIN_EXPORT_PW_LEN = 8
    }
}
