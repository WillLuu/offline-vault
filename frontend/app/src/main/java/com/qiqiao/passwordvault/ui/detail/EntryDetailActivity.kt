package com.qiqiao.passwordvault.ui.detail

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.model.PasswordEntryRow
import com.qiqiao.passwordvault.ui.edit.EntryEditActivity
import com.qiqiao.passwordvault.util.ClipboardHelper
import com.qiqiao.passwordvault.util.formatTime
import com.qiqiao.passwordvault.util.maskPassword
import com.qiqiao.passwordvault.util.passwordStrength
import com.qiqiao.passwordvault.util.strengthLabel

// 条目详情：展示明文（密码可切换掩码）、复制密码、编辑、删除。
class EntryDetailActivity : PwdBaseActivity() {

    private var entry: PasswordEntryRow? = null
    private var revealed = false
    private var clipboardDelaySec = 30

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entry_detail)

        val id = intent.getLongExtra("id", -1)
        val tvPassword = findViewById<TextView>(R.id.tvPassword)
        val btnShowHide = findViewById<ImageButton>(R.id.btnShowHide)
        // 眼睛圆钮容器：揭示态切凹陷 + primary（与列表卡 eye 一致）
        val eyeContainer = findViewById<com.qiqiao.passwordvault.ui.common.NeuSurface>(R.id.eyeContainer)

        findViewById<Button>(R.id.btnEdit).setOnClickListener {
            startActivity(Intent(this, EntryEditActivity::class.java).putExtra("id", id))
        }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete(id) }

        btnShowHide.setOnClickListener {
            revealed = !revealed
            btnShowHide.contentDescription = getString(
                if (revealed) R.string.detail_hide else R.string.detail_show)
            tvPassword.text = if (revealed) entry?.password ?: "" else maskPassword()
            eyeContainer.direction = if (revealed)
                com.qiqiao.passwordvault.ui.common.NeuSurface.Direction.INSET
            else com.qiqiao.passwordvault.ui.common.NeuSurface.Direction.RAISED
            btnShowHide.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (revealed) R.color.primary else R.color.on_surface_variant))
        }

        val copy: (View) -> Unit = {
            entry?.password?.let {
                ClipboardHelper.copy(this, it, clipboardDelaySec)
                Toast.makeText(this, R.string.detail_copied, Toast.LENGTH_SHORT).show()
            }
        }
        // btnCopy 在模板 43 布局里是圆形图标钮（ImageButton），不能按旧 Button 取——否则 ClassCastException 闪退
        findViewById<View>(R.id.btnCopy).setOnClickListener(copy)
        findViewById<Button>(R.id.btnCopyBottom).setOnClickListener(copy)

        Vault.data.getSettings { res -> res.onSuccess { clipboardDelaySec = it.clipboardClearDelaySec } }
        // 详情加载移至 onResume（见下）——onCreate 后必经 onResume，首次进入不受影响
    }

    // 修复（2026-09-06 用户反馈）：编辑已有条目保存后 finish() 返回本页，原实现只在 onCreate
    // 加载一次，界面仍是旧数据，须退出重进才刷新。改为 onResume 每次重新加载：
    // 首次进入、编辑返回、删除确认弹窗关闭后均拿到最新值；单行查询开销可忽略。
    override fun onResume() {
        super.onResume()
        loadEntry()
    }

    private fun loadEntry() {
        val id = intent.getLongExtra("id", -1)
        val tvPassword = findViewById<TextView>(R.id.tvPassword)
        Vault.data.getEntry(id) { res ->
            res.onSuccess { row ->
                runOnUiThread {
                    if (row == null) { finish(); return@runOnUiThread }
                    entry = row
                    fill(row, tvPassword)
                }
            }.onFailure { runOnUiThread { finish() } }
        }
    }

    private fun fill(e: PasswordEntryRow, tvPassword: TextView) {
        findViewById<TextView>(R.id.tvName).text = e.name
        findViewById<TextView>(R.id.tvUser).text = if (e.username.isBlank()) "—" else e.username
        tvPassword.text = if (revealed) e.password else maskPassword()
        findViewById<TextView>(R.id.tvWebsite).text = if (e.website.isBlank()) "—" else e.website
        findViewById<TextView>(R.id.tvNotes).text = if (e.notes.isBlank()) "—" else e.notes
        findViewById<TextView>(R.id.tvCategory).text = e.categoryName
        findViewById<TextView>(R.id.tvCreated).text = formatTime(e.createdAt)
        findViewById<TextView>(R.id.tvUpdated).text = formatTime(e.updatedAt)
        updateStrength(e.password)
        fillExtraFields(e)
    }

    // 自定义词条展示（2026-09-06）：动态追加「标签：值」行；无自定义词条时隐藏容器。
    private fun fillExtraFields(e: PasswordEntryRow) {
        val container = findViewById<LinearLayout>(R.id.llExtra)
        container.removeAllViews()
        if (e.extras.isEmpty()) return
        val pad = (resources.displayMetrics.density * 2).toInt()
        e.extras.forEach { f ->
            val label = TextView(this).apply {
                text = f.label
                setTextColor(ContextCompat.getColor(this@EntryDetailActivity, R.color.on_surface_variant))
                textSize = 13f
                setPadding(0, pad, 0, 0)
            }
            val value = TextView(this).apply {
                text = f.value
                setTextColor(ContextCompat.getColor(this@EntryDetailActivity, R.color.on_surface))
                textSize = 15f
                setPadding(0, pad, 0, 0)
            }
            container.addView(label)
            container.addView(value)
        }
    }

    /**
     * 强度：立体五角星 5 颗（白色亮星 + 阴影星投影，用户定稿）。
     * 点亮颗数 = passwordStrength；亮星 = 白色（底层阴影星提供立体），未亮 = 阴影色 25% 凹陷灰星。
     */
    private fun updateStrength(pw: String) {
        val s = passwordStrength(pw)
        val bar = findViewById<View>(R.id.starBar) as android.widget.LinearLayout
        bar.removeAllViews()

        val dp = resources.displayMetrics.density
        val whiteColor = ContextCompat.getColor(this, R.color.white)
        val dimColor = ContextCompat.getColor(this, R.color.shadow_dark)
        val shadowColor = ContextCompat.getColor(this, R.color.shadow_dark)
        val cell = (20 * dp).toInt()

        repeat(5) { i ->
            val frame = android.widget.FrameLayout(this)
            val shadow = ImageView(this).apply {
                setImageResource(R.drawable.ic_star_24)
                imageTintList = ColorStateList.valueOf(shadowColor)
                alpha = 0.85f
                translationX = 1.5f * dp
                translationY = 1.5f * dp
            }
            val face = ImageView(this).apply {
                setImageResource(R.drawable.ic_star_24)
                if (i < s) {
                    imageTintList = ColorStateList.valueOf(whiteColor)
                    alpha = 1f
                } else {
                    imageTintList = ColorStateList.valueOf(dimColor)
                    alpha = 0.25f
                }
            }
            frame.addView(shadow, android.widget.FrameLayout.LayoutParams(cell, cell))
            frame.addView(face, android.widget.FrameLayout.LayoutParams(cell, cell))
            val lp = android.widget.LinearLayout.LayoutParams(cell, cell)
            lp.marginEnd = (4 * dp).toInt()
            bar.addView(frame, lp)
        }

        findViewById<TextView>(R.id.tvStrengthLabel).text =
            getString(R.string.detail_strength_fmt, strengthLabel(s))
    }

    private fun confirmDelete(id: Long) {
        // 2026-09-06：系统 AlertDialog 统一为自绘 NeuDialog（danger 确认字标红「删除」），
        // 与全 App 弹窗视觉语言一致（此前详情页删除是全仓最后一处系统弹窗）。
        com.qiqiao.passwordvault.ui.common.NeuDialog.showConfirm(
            this,
            message = getString(R.string.common_confirm_delete),
            danger = true,
            okText = getString(R.string.detail_delete),
            onOk = {
                Vault.data.deleteEntry(id) { res ->
                    runOnUiThread { if (res.getOrDefault(false)) finish() }
                }
            }
        )
    }
}
