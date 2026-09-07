package com.qiqiao.passwordvault.ui.generator

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.util.CharsetFlags
import com.qiqiao.passwordvault.util.generatePassword

// 密码生成器：长度滑块 + 字符集勾选 + 生成 + 复制（复制后回传密码给编辑页）。
// 复制已统一走 ClipboardHelper（P2#7 已修：含自动清除延时，读设置；见 copyAndReturn()）。
class PasswordGeneratorActivity : PwdBaseActivity() {

    private lateinit var sbLength: SeekBar
    private lateinit var tvLength: TextView
    private lateinit var cbUpper: CheckBox
    private lateinit var cbLower: CheckBox
    private lateinit var cbDigit: CheckBox
    private lateinit var cbSymbol: CheckBox
    private lateinit var tvOutput: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password_generator)

        sbLength = findViewById(R.id.sbLength)
        tvLength = findViewById(R.id.tvLength)
        cbUpper = findViewById(R.id.cbUpper)
        cbLower = findViewById(R.id.cbLower)
        cbDigit = findViewById(R.id.cbDigit)
        cbSymbol = findViewById(R.id.cbSymbol)
        tvOutput = findViewById(R.id.tvOutput)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        sbLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                tvLength.text = getString(R.string.generator_length, progress.coerceAtLeast(4))
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tvLength.text = getString(R.string.generator_length, sbLength.progress.coerceAtLeast(4))

        findViewById<Button>(R.id.btnGenerate).setOnClickListener { generate() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyAndReturn() }

        generate()
    }

    private fun currentFlags() = CharsetFlags(
        upper = cbUpper.isChecked,
        lower = cbLower.isChecked,
        digit = cbDigit.isChecked,
        symbol = cbSymbol.isChecked
    )

    private fun generate() {
        val len = sbLength.progress.coerceAtLeast(4)
        val pw = generatePassword(len, currentFlags())
        if (pw.isEmpty()) {
            Toast.makeText(this, R.string.generator_empty_charset, Toast.LENGTH_SHORT).show()
            return
        }
        tvOutput.text = pw
    }

    private fun copyAndReturn() {
        val pw = tvOutput.text.toString()
        if (pw.isEmpty()) return
        // 统一走 ClipboardHelper（含自动清除延时，读设置）；设置读取失败退化为不自动清除（等价旧直写）。
        com.qiqiao.passwordvault.data.Vault.data.getSettings { res ->
            res.onSuccess { s ->
                runOnUiThread {
                    com.qiqiao.passwordvault.util.ClipboardHelper.copy(
                        this@PasswordGeneratorActivity, pw, s.clipboardClearDelaySec)
                    returnWith(pw)
                }
            }.onFailure {
                runOnUiThread {
                    com.qiqiao.passwordvault.util.ClipboardHelper.copy(
                        this@PasswordGeneratorActivity, pw, 0)
                    returnWith(pw)
                }
            }
        }
    }

    private fun returnWith(pw: String) {
        val intent = Intent().putExtra("password", pw)
        setResult(RESULT_OK, intent)
        finish()
    }
}
