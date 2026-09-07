package com.qiqiao.passwordvault.ui.settings

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.model.SettingsPatch
import com.qiqiao.passwordvault.ui.common.NeuShadowPrefs
import com.qiqiao.passwordvault.ui.common.NeuSurface
import com.qiqiao.passwordvault.ui.common.NeuSwitch
import com.qiqiao.passwordvault.ui.list.PasswordListActivity
import com.qiqiao.passwordvault.util.ChangeMasterError
import com.qiqiao.passwordvault.util.NavAnim
import com.qiqiao.passwordvault.util.ThemePrefs
import com.qiqiao.passwordvault.util.validateChangeMaster
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.util.concurrent.Executor
import javax.crypto.Cipher
import vault.WrongPasswordException

// 设置页：自动锁超时、剪贴板清除延时、主题、生物识别开关、导出/导入、分类管理、修改主密码。
class SettingsActivity : PwdBaseActivity() {

    private lateinit var etAutoLock: EditText
    private lateinit var etClipboard: EditText
    private lateinit var spTheme: Spinner
    private lateinit var swBiometric: NeuSwitch
    private lateinit var promptExecutor: Executor
    private var currentTheme = "system"

    // #QA-029 生物识别即时开关：guard 防程序性 setChecked 重入（load 初值 / 流程回写）
    private var suppressSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etAutoLock = findViewById(R.id.etAutoLock)
        etClipboard = findViewById(R.id.etClipboard)
        spTheme = findViewById(R.id.spTheme)
        // 赋值类字段（lateinit）：生物识别回调（rollbackSwitch 等）访问的是字段而非局部变量
        swBiometric = findViewById(R.id.swBiometric)
        promptExecutor = ContextCompat.getMainExecutor(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { saveAndExit() }
        // 显式保存按钮（2026-09-06 用户需求）：自动锁/剪贴板时间修改后需点"保存时间设置"落库
        findViewById<View>(R.id.btnSaveTimes).setOnClickListener { persistTimes(showToast = true) }
        // 数据管理四项（导出/导入/批量录入/分类管理）已迁至独立"数据"页（2026-09-06，底部导航入口）
        findViewById<View>(R.id.btnChangeMasterPassword).setOnClickListener {
            showChangeMasterPasswordDialog()
        }
        // 底部导航（2026-09-06 用户需求）：主页 / 数据 / ＋新增 / 设置；本页"设置"高亮；切页翻转动画。
        // 离开前先落库时间字段——此前底部导航直接跳页导致"改过一次后再改不生效"（修改被丢弃）。
        findViewById<View>(R.id.btnNavHome).setOnClickListener {
            persistTimes(showToast = false)
            navTo(
                Intent(this, PasswordListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                NavAnim.DEST_HOME
            )
        }
        findViewById<View>(R.id.btnNavData).setOnClickListener {
            persistTimes(showToast = false)
            navTo(Intent(this, com.qiqiao.passwordvault.ui.import.DataManageActivity::class.java), NavAnim.DEST_DATA)
        }
        findViewById<View>(R.id.btnNavAdd).setOnClickListener {
            persistTimes(showToast = false)
            navTo(Intent(this, com.qiqiao.passwordvault.ui.edit.EntryEditActivity::class.java), NavAnim.DEST_ADD)
        }
        val aboutVersion = runCatching {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("v1.0")
        findViewById<TextView>(R.id.tvAboutVersion).text = aboutVersion

        setupShadowControls()

        // #QA-029 生物识别即时开启/关闭（不走 saveAndExit 的 patch——需交互式 BiometricPrompt 授权）。
        // Keystore key 为 per-op 认证：开启 = 弹指纹授权 → wrap 当前会话 DEK 落库；关闭 = 清 wrapped + 删 key。
        swBiometric.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            swBiometric.isEnabled = false // 防连点，流程（含系统弹窗）结束恢复
            if (checked) runBiometricEnable() else runBiometricDisable()
        }

        Vault.data.getSettings { res ->
            res.onSuccess { s ->
                runOnUiThread {
                    etAutoLock.setText(s.autoLockTimeoutSec.toString())
                    etClipboard.setText(s.clipboardClearDelaySec.toString())
                    suppressSwitch = true  // 程序性赋初值，不触发即时流程
                    swBiometric.isChecked = s.biometricEnabled
                    suppressSwitch = false
                    currentTheme = s.theme
                    setupThemeSpinner() // 读取后装配下拉（初始值回读 + 用户选择生效）
                }
            }
        }
    }

    // 底部导航切页：方向感知左右滑动（NavAnim 单一事实源；本页 = 设置(3)）。
    private fun navTo(intent: Intent, to: Int) {
        NavAnim.go(this, intent, NavAnim.DEST_SETTINGS, to)
    }

    // 应用主题（保留原语义）：双守卫 + 双写 + 清栈回列表页新实例（不走 recreate，防误触发自动锁）
    private fun applyTheme(newTheme: String) {
        if (newTheme == currentTheme || suppressSwitch) return
        persistTimes(showToast = false) // 换主题前先落库时间字段（theme=null 不会覆盖本次主题写入）
        currentTheme = newTheme
        ThemePrefs.setTheme(this, newTheme)
        Vault.data.updateSettings(SettingsPatch(theme = newTheme)) { }
        val i = Intent(this, PasswordListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        finish()
    }

    // ---- 主题下拉（最初版 UI 定稿）：读取设置后装配 spinner（防启动自选触发），
    //      用户选择 = applyTheme（双守卫 + 双写 + 清栈回列表页新实例，不走 recreate 防误锁）----
    private fun setupThemeSpinner() {
        val entries = listOf(
            getString(R.string.settings_theme_light) to "light",
            getString(R.string.settings_theme_dark) to "dark",
            getString(R.string.settings_theme_morandi) to "morandi",
            getString(R.string.settings_theme_system) to "system"
        )
        spTheme.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, entries.map { it.first }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val key = entries[pos].second
                if (key != currentTheme) applyTheme(key) // 相同（程序性回读）忽略
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val pos = entries.indexOfFirst { it.second == currentTheme }.coerceAtLeast(0)
        spTheme.setSelection(pos)
    }

    // ---- #QA-029 生物识别即时开关：开启 = 指纹授权后 wrap 当前会话 DEK 落库；关闭 = 清配置 ----

    private fun runBiometricEnable() {
        // 段 1：后台准备 ENCRYPT cipher（Keystore init，可能抛 KeyPermanentlyInvalidatedException）
        Vault.data.prepareBiometricWrap { res ->
            runOnUiThread {
                res.onSuccess { cipher -> promptBiometricConfirm(cipher) }
                .onFailure { e ->
                    // 指纹登记变更 -> key 作废：自愈（清残留）+ 提示重开
                    val msg = if (e is KeyPermanentlyInvalidatedException) {
                        Vault.data.disableBiometric { }
                        R.string.biometric_key_invalidated
                    } else R.string.biometric_error
                    toast(msg)
                    rollbackSwitch(false)
                }
            }
        }
    }

    private fun promptBiometricConfirm(cipher: Cipher) {
        val prompt = BiometricPrompt(
            this, promptExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    // 段 2（认证成功）：wrap 当前会话 DEK 并落库
                    // 修复：cipher 缺失（极少数系统回调不带 CryptoObject）时也要回滚开关——
                    // 原实现直接 return，开关会卡在「开着但禁用」的假状态且无法再点。
                    val authed = result.cryptoObject?.cipher
                    if (authed == null) {
                        toast(R.string.biometric_error)
                        rollbackSwitch(false)
                        return
                    }
                    Vault.data.completeBiometricEnable(authed) { r ->
                        runOnUiThread {
                            val ok = r.getOrDefault(false)
                            toast(if (ok) R.string.biometric_enable_success else R.string.biometric_error)
                            // 启用成功 → 开关保持开（=ok）；失败才回滚关。此前误用 !ok：成功却拨回左，重进页面才看到已开
                            rollbackSwitch(ok)
                        }
                    }
                }
                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    // 用户取消 / 系统不可用：未真正开启，开关回滚关
                    rollbackSwitch(false)
                }
                override fun onAuthenticationFailed() { /* 指纹不匹配：系统内重试，不动作 */ }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_enable_title))
            .setSubtitle(getString(R.string.biometric_enable_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun runBiometricDisable() {
        Vault.data.disableBiometric { r ->
            runOnUiThread {
                val ok = r.getOrDefault(false)
                toast(if (ok) R.string.biometric_disable_success else R.string.biometric_error)
                rollbackSwitch(!ok) // 成功保持关；失败回滚开
            }
        }
    }

    // 流程结束回写开关（suppress 防重入）+ 恢复可点。true = 拨到开。
    private fun rollbackSwitch(on: Boolean) {
        suppressSwitch = true
        swBiometric.isChecked = on
        swBiometric.isEnabled = true
        suppressSwitch = false
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    // ---- 阴影调节（暗影/亮影/内阴影三组独立，NeuShadowPrefs 全局生效，拖动实时重绘）----
    // SeekBar 进度 → 参数映射：
    //   offset 0..20dp   → progress 0..20（1:1）
    //   blur   0..40dp   → progress 0..40（1:1）
    //   alpha 10..100%   → progress 0..90（+10 偏移）
    //   angle  0..360°   → progress 0..360（1:1）
    private fun setupShadowControls() {
        fun bindGroup(
            sbOffset: SeekBar, sbBlur: SeekBar, sbAlpha: SeekBar, sbAngle: SeekBar,
            tvOffset: TextView, tvBlur: TextView, tvAlpha: TextView, tvAngle: TextView,
            read: () -> NeuShadowPrefs.Quartet,
            update: (Float?, Float?, Int?, Float?) -> NeuShadowPrefs.Quartet
        ) {
            sbOffset.max = NeuShadowPrefs.MAX_OFFSET_DP.toInt()
            sbBlur.max = NeuShadowPrefs.MAX_BLUR_DP.toInt()
            sbAlpha.max = NeuShadowPrefs.MAX_ALPHA - NeuShadowPrefs.MIN_ALPHA
            sbAngle.max = NeuShadowPrefs.MAX_ANGLE

            fun refresh() {
                val q = read()
                tvOffset.text = getString(R.string.shadow_offset, q.offset.toInt())
                tvBlur.text = getString(R.string.shadow_blur, q.blur.toInt())
                tvAlpha.text = getString(R.string.shadow_alpha, q.alpha)
                tvAngle.text = getString(R.string.shadow_angle, q.angle.toInt())
            }

            val q = read()
            sbOffset.progress = q.offset.toInt()
            sbBlur.progress = q.blur.toInt()
            sbAlpha.progress = q.alpha - NeuShadowPrefs.MIN_ALPHA
            sbAngle.progress = q.angle.toInt()
            refresh()

            sbOffset.setOnSeekBarChangeListener(simpleListener { v -> update(v.toFloat(), null, null, null); refresh() })
            sbBlur.setOnSeekBarChangeListener(simpleListener { v -> update(null, v.toFloat(), null, null); refresh() })
            sbAlpha.setOnSeekBarChangeListener(simpleListener { v -> update(null, null, v + NeuShadowPrefs.MIN_ALPHA, null); refresh() })
            sbAngle.setOnSeekBarChangeListener(simpleListener { v -> update(null, null, null, v.toFloat()); refresh() })
        }

        bindGroup(
            findViewById(R.id.sbDarkOffset), findViewById(R.id.sbDarkBlur),
            findViewById(R.id.sbDarkAlpha), findViewById(R.id.sbDarkAngle),
            findViewById(R.id.tvDarkOffset), findViewById(R.id.tvDarkBlur),
            findViewById(R.id.tvDarkAlpha), findViewById(R.id.tvDarkAngle),
            read = {
                NeuShadowPrefs.Quartet(NeuShadowPrefs.darkOffsetDp, NeuShadowPrefs.darkBlurDp,
                    NeuShadowPrefs.darkAlphaPercent, NeuShadowPrefs.darkAngleDeg)
            },
            update = { o, b, a, g -> NeuShadowPrefs.updateDark(o, b, a, g) }
        )
        bindGroup(
            findViewById(R.id.sbLightOffset), findViewById(R.id.sbLightBlur),
            findViewById(R.id.sbLightAlpha), findViewById(R.id.sbLightAngle),
            findViewById(R.id.tvLightOffset), findViewById(R.id.tvLightBlur),
            findViewById(R.id.tvLightAlpha), findViewById(R.id.tvLightAngle),
            read = {
                NeuShadowPrefs.Quartet(NeuShadowPrefs.lightOffsetDp, NeuShadowPrefs.lightBlurDp,
                    NeuShadowPrefs.lightAlphaPercent, NeuShadowPrefs.lightAngleDeg)
            },
            update = { o, b, a, g -> NeuShadowPrefs.updateLight(o, b, a, g) }
        )
        bindGroup(
            findViewById(R.id.sbInsetDarkOffset), findViewById(R.id.sbInsetDarkBlur),
            findViewById(R.id.sbInsetDarkAlpha), findViewById(R.id.sbInsetDarkAngle),
            findViewById(R.id.tvInsetDarkOffset), findViewById(R.id.tvInsetDarkBlur),
            findViewById(R.id.tvInsetDarkAlpha), findViewById(R.id.tvInsetDarkAngle),
            read = {
                NeuShadowPrefs.Quartet(NeuShadowPrefs.insetDarkOffsetDp, NeuShadowPrefs.insetDarkBlurDp,
                    NeuShadowPrefs.insetDarkAlphaPercent, NeuShadowPrefs.insetDarkAngleDeg)
            },
            update = { o, b, a, g -> NeuShadowPrefs.updateInsetDark(o, b, a, g) }
        )
        bindGroup(
            findViewById(R.id.sbInsetLightOffset), findViewById(R.id.sbInsetLightBlur),
            findViewById(R.id.sbInsetLightAlpha), findViewById(R.id.sbInsetLightAngle),
            findViewById(R.id.tvInsetLightOffset), findViewById(R.id.tvInsetLightBlur),
            findViewById(R.id.tvInsetLightAlpha), findViewById(R.id.tvInsetLightAngle),
            read = {
                NeuShadowPrefs.Quartet(NeuShadowPrefs.insetLightOffsetDp, NeuShadowPrefs.insetLightBlurDp,
                    NeuShadowPrefs.insetLightAlphaPercent, NeuShadowPrefs.insetLightAngleDeg)
            },
            update = { o, b, a, g -> NeuShadowPrefs.updateInsetLight(o, b, a, g) }
        )

        findViewById<View>(R.id.btnShadowReset).setOnClickListener {
            NeuShadowPrefs.resetToDefault()
            setupShadowControls()
        }

        setupShadowModeToggle()
    }

    // ---- 阴影调节模式切换：分段控制器（iOS 式）"常规"=外阴影滑杆，"强阴影"=内阴影滑杆。
    //      选中段深紫底白字（对比拉满），样例与 SeekBar 组随切换。----
    private fun setupShadowModeToggle() {
        val segNormal = findViewById<TextView>(R.id.toggleRaised)
        val segStrong = findViewById<TextView>(R.id.toggleInset)
        val preview = findViewById<NeuSurface>(R.id.previewSample)
        val primary = ContextCompat.getColor(this, R.color.primary)
        val muted = ContextCompat.getColor(this, R.color.on_surface_variant)
        val pillRadius = resources.displayMetrics.density * 12f

        fun pill(): android.graphics.drawable.GradientDrawable {
            val g = android.graphics.drawable.GradientDrawable()
            g.cornerRadius = pillRadius
            g.setColor(primary)   // setColor(int) 而非 color 属性：后者绑定 ColorStateList 重载
            return g
        }

        fun tint(tv: TextView, color: Int) {
            tv.setTextColor(android.content.res.ColorStateList.valueOf(color))
        }

        fun applyMode(strongMode: Boolean) {
            findViewById<View>(R.id.groupRaisedDark).visibility = if (strongMode) View.GONE else View.VISIBLE
            findViewById<View>(R.id.groupRaisedLight).visibility = if (strongMode) View.GONE else View.VISIBLE
            findViewById<View>(R.id.groupInsetDark).visibility = if (strongMode) View.VISIBLE else View.GONE
            findViewById<View>(R.id.groupInsetLight).visibility = if (strongMode) View.VISIBLE else View.GONE

            segNormal.background = if (strongMode) null else pill()
            segStrong.background = if (strongMode) pill() else null
            tint(segNormal, if (strongMode) muted else android.graphics.Color.WHITE)
            tint(segStrong, if (strongMode) android.graphics.Color.WHITE else muted)

            preview.direction = if (strongMode) NeuSurface.Direction.INSET else NeuSurface.Direction.RAISED
            (preview.getChildAt(0) as TextView).text = getString(
                if (strongMode) R.string.shadow_preview_inset else R.string.shadow_preview_raised)
        }
        applyMode(false)

        segNormal.setOnClickListener { applyMode(false) }
        segStrong.setOnClickListener { applyMode(true) }
    }

    private fun simpleListener(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // 系统返回键/全面屏侧滑手势同样走保存（#QA-028）：此前保存只挂在自绘 btnBack 上，
    // 侧滑退出会静默丢失全部设置改动（真机复现：生物识别开关打开后退出重进变回关闭）。
    // ponytail: onBackPressed 已标记废弃但 API26 稳定，与 EntryEditActivity 用废弃 API 的先例一致 | 升级阈值：迁移 OnBackPressedDispatcher
    // lint: MissingSuperCall 豁免——本方法必然走 saveAndExit→finish 退出，不调用 super 是有意的（调用会导致二次 finish/重复保存）。
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        saveAndExit()
    }

    // 保存时间字段（自动锁 / 剪贴板）并即时应用自动锁。showToast=true 用于显式"保存时间设置"按钮。
    // theme 传 null：主题由 applyTheme 即时生效，此方法绝不覆盖主题（避免并行写覆盖）。
    private fun persistTimes(showToast: Boolean) {
        // 非法或空输入规整为 0（0=关闭），保证"改了就能存上"（2026-09-06 修复）
        val autoLockSec = (etAutoLock.text.toString().trim().toIntOrNull() ?: 0).coerceAtLeast(0)
        val clipboardSec = (etClipboard.text.toString().trim().toIntOrNull() ?: 0).coerceAtLeast(0)
        etAutoLock.setText(autoLockSec.toString())
        etClipboard.setText(clipboardSec.toString())
        // 即时应用自动锁：所有页面共用同一等待时间，无需等回列表/重启才生效
        com.qiqiao.passwordvault.MainApplication.updateAutoLockTimeoutSec(autoLockSec)
        val patch = SettingsPatch(
            autoLockTimeoutSec = autoLockSec,
            clipboardClearDelaySec = clipboardSec,
            theme = null,
            biometricEnabled = null // #QA-029：生物识别由开关即时流程直接写库（需交互授权），不从保存路径重复提交
        )
        Vault.data.updateSettings(patch) { res ->
            if (showToast) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(
                            if (res.isSuccess) R.string.settings_times_saved
                            else R.string.unlock_fail
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun saveAndExit() {
        persistTimes(showToast = false)
        finish()
    }

    // 修改主密码（契约 §3.3）：新拟态自绘弹窗（NeuSurface 卡 + 凹陷输入 + 凸起确认钮）。
    // 成功后仅 Toast，不强制跳解锁页（DEK 不变、保持登录态）。
    private fun showChangeMasterPasswordDialog() {
        val dp = resources.displayMetrics.density

        fun neuInput(hintRes: Int): EditText = EditText(this).apply {
            hint = getString(hintRes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setBackgroundResource(android.R.color.transparent)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.on_surface))
            setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.on_surface_variant))
        }

        fun neuInset(child: View): View {
            val ctx = this
            val surface = com.qiqiao.passwordvault.ui.common.NeuSurface(ctx).apply {
                direction = com.qiqiao.passwordvault.ui.common.NeuSurface.Direction.INSET
                cornerRadiusPx = 16 * dp
            }
            surface.addView(child, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            return surface
        }

        fun label(textRes: Int): TextView = TextView(this).apply {
            text = getString(textRes)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.on_surface_variant))
        }

        val etOld = neuInput(R.string.change_old_hint)
        val etNew = neuInput(R.string.change_new_hint)
        val etConfirm = neuInput(R.string.change_confirm_hint)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * dp).toInt()
            setPadding(pad, pad, pad, (8 * dp).toInt())
            clipChildren = false; clipToPadding = false

            val title = TextView(this@SettingsActivity).apply {
                text = getString(R.string.settings_change_master_password)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.on_surface))
            }
            addView(title)

            fun addLabeledField(l: TextView, f: View, gapTop: Int) {
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = gapTop
                addView(l, lp)
                val lp2 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp2.topMargin = (6 * dp).toInt()
                addView(neuInset(f), lp2)
            }
            addLabeledField(label(R.string.change_old_hint), etOld, (18 * dp).toInt())
            addLabeledField(label(R.string.change_new_hint), etNew, (14 * dp).toInt())
            addLabeledField(label(R.string.change_confirm_hint), etConfirm, (14 * dp).toInt())
        }

        // 按钮行：取消（文字）+ 确认（凸起胶囊主钮）
        val btnCancel = TextView(this).apply {
            text = getString(R.string.common_cancel)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.on_surface_variant))
            gravity = android.view.Gravity.CENTER
            isClickable = true
        }
        val btnOk = TextView(this).apply {
            text = getString(R.string.common_ok)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD, android.graphics.Typeface.BOLD)
            // values-night 资源限定符自动适配深浅主题的主色
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.primary))
            gravity = android.view.Gravity.CENTER
            isClickable = true
        }

        // 浮层卡片：普通深色投影（neu 亮影白 halo 浮在暗遮罩上呈白晕，用户定稿弃用）
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 26 * dp
            cardElevation = 8 * dp
            setCardBackgroundColor(com.qiqiao.passwordvault.ui.common.NeuDialog.surfaceColor(this@SettingsActivity))
            strokeWidth = 0
        }
        card.addView(content, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        buttonRow.addView(btnCancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = (18 * dp).toInt()
        })
        // 确认钮：普通卡片（轻投影），弹窗内嵌套控件不用 neu 凸起（白亮影糊边，用户定稿）
        val okSurface = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 22 * dp
            cardElevation = 2 * dp
            strokeWidth = 0
            setCardBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.surface))
        }
        okSurface.addView(btnOk, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            (44 * dp).toInt()).apply {
            marginStart = (22 * dp).toInt(); marginEnd = (22 * dp).toInt()
        })
        buttonRow.addView(okSurface)
        content.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (20 * dp).toInt()
        })

        val dlg = android.app.Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        // 外层透明容器给凸起 halo 留空间（直接以卡片为 content 时窗口裁掉四角 halo → 白色边角残留）
        val root = android.widget.FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false
            val haloPad = (20 * dp).toInt()
            setPadding(haloPad, haloPad, haloPad, haloPad)
        }
        root.addView(card, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        dlg.setContentView(root)
        dlg.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                resources.displayMetrics.widthPixels,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        btnCancel.setOnClickListener { dlg.dismiss() }
        btnOk.setOnClickListener {
            val old = etOld.text.toString()
            val new = etNew.text.toString()
            when (validateChangeMaster(old, new, etConfirm.text.toString())) {
                ChangeMasterError.OLD_BLANK -> etOld.error = getString(R.string.change_old_required)
                ChangeMasterError.NEW_BLANK -> etNew.error = getString(R.string.change_new_required)
                ChangeMasterError.NEW_TOO_SHORT -> etNew.error = getString(R.string.change_new_too_short)
                ChangeMasterError.MISMATCH -> etConfirm.error = getString(R.string.init_mismatch)
                null -> {
                    dlg.dismiss()
                    Vault.data.changeMasterPassword(old, new) { r ->
                        runOnUiThread {
                            val e = r.exceptionOrNull()
                            val text = when {
                                r.isSuccess -> getString(R.string.change_success)
                                e is WrongPasswordException -> getString(R.string.change_wrong_old)
                                else -> e?.message?.takeIf { it.isNotBlank() }
                                    ?: getString(R.string.change_failed)
                            }
                            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        dlg.show()
    }
}
