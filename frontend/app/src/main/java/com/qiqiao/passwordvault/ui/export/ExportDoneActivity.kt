package com.qiqiao.passwordvault.ui.export

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.ui.list.PasswordListActivity

// 导出完成页：成功提示 + 文件位置；完成后回到列表。
class ExportDoneActivity : PwdBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export_done)

        val fileName = intent.getStringExtra("fileName").orEmpty()
        findViewById<TextView>(R.id.tvFileName).text =
            getString(R.string.export_done_file, fileName)

        findViewById<Button>(R.id.btnFinish).setOnClickListener {
            // 清空返回栈，直接回到列表
            val intent = Intent(this, PasswordListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        }
    }
}
