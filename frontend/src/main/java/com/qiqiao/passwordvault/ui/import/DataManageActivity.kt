package com.qiqiao.passwordvault.ui.import

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.ui.PwdBaseActivity
import com.qiqiao.passwordvault.ui.category.CategoryManageActivity
import com.qiqiao.passwordvault.ui.edit.EntryEditActivity
import com.qiqiao.passwordvault.ui.export.ExportBackupActivity
import com.qiqiao.passwordvault.ui.list.PasswordListActivity
import com.qiqiao.passwordvault.ui.settings.SettingsActivity
import com.qiqiao.passwordvault.util.NavAnim

/**
 * 数据管理页（2026-09-06 用户需求）：导出 / 导入 / 批量录入 / 分类管理 + 密码统计卡。
 * 底部导航目的地之一（主页 / 数据管理 / ＋新增 / 设置），本页"数据管理"高亮。
 */
class DataManageActivity : PwdBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_manage)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnExport).setOnClickListener {
            startActivity(Intent(this, ExportBackupActivity::class.java))
        }
        findViewById<View>(R.id.btnImport).setOnClickListener {
            startActivity(Intent(this, ImportBackupActivity::class.java))
        }
        findViewById<View>(R.id.btnBatchImport).setOnClickListener {
            startActivity(Intent(this, BatchImportActivity::class.java))
        }
        findViewById<View>(R.id.btnCategoryManage).setOnClickListener {
            startActivity(Intent(this, CategoryManageActivity::class.java))
        }
        setupNavBar()
    }

    override fun onResume() {
        super.onResume()
        refreshStats() // 返回本页时刷新（导入/录入/分类变化后计数最新）
    }

    // 密码统计（2026-09-06：从头像点击迁入本页）：总条数 + 每个分类各多少条（entryCount 由后端 JOIN 提供）。
    private fun refreshStats() {
        val tv = findViewById<TextView>(R.id.tvStats)
        tv.text = getString(R.string.stats_loading)
        Vault.data.listEntries { totalRes ->
            val total = totalRes.getOrDefault(emptyList()).size
            Vault.data.listCategories { catRes ->
                runOnUiThread {
                    val cats = catRes.getOrDefault(emptyList())
                    val sb = StringBuilder()
                    sb.append("密码总条数：").append(total)
                    if (cats.isNotEmpty()) {
                        cats.forEach { c ->
                            sb.append("\n").append(c.name).append("：").append(c.entryCount).append(" 条")
                        }
                        val uncategorized = total - cats.sumOf { it.entryCount }
                        if (uncategorized > 0) sb.append("\n未分类：").append(uncategorized).append(" 条")
                    }
                    tv.text = sb.toString()
                }
            }
        }
    }

    private fun setupNavBar() {
        // 主页：回到列表（CLEAR_TOP 复用栈底实例，避免叠层）；序号 1→0 = 后退 = 右移
        findViewById<View>(R.id.btnNavHome).setOnClickListener {
            navTo(Intent(this, PasswordListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), NavAnim.DEST_HOME)
        }
        // btnNavData = 当前页
        findViewById<View>(R.id.btnNavAdd).setOnClickListener {
            navTo(Intent(this, EntryEditActivity::class.java), NavAnim.DEST_ADD)
        }
        findViewById<View>(R.id.btnNavSettings).setOnClickListener {
            navTo(Intent(this, SettingsActivity::class.java), NavAnim.DEST_SETTINGS)
        }
    }

    // 底部导航切页：方向感知左右滑动（NavAnim 单一事实源；本页 = 数据(1)）。
    private fun navTo(intent: Intent, to: Int) {
        NavAnim.go(this, intent, NavAnim.DEST_DATA, to)
    }
}
