package com.qiqiao.passwordvault.ui.unlock

import com.qiqiao.passwordvault.ui.PwdBaseActivity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.qiqiao.passwordvault.R
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.ui.list.PasswordListActivity
import java.util.concurrent.Executor
import javax.crypto.Cipher

// 解锁 / 首次初始化入口。真实密码校验（verifier）由 backend 提供。
// #QA-029：生物识别真实现——prepare 段 1 在后台拿 DECRYPT cipher（Keystore init），
// BiometricPrompt 携 CryptoObject 弹指纹认证，成功后段 2 携同一 cipher 解包 DEK（后台）。
// Keystore key 为 per-op 认证：cipher 必须经系统认证后才能 doFinal，缺授权上下文即 UserNotAuthenticated。
// （用户定稿：恢复 BiometricPrompt 系统弹窗版——FingerprintManager 免弹窗版体验不稳，已回退。）
class UnlockActivity : PwdBaseActivity() {

    private lateinit var etPassword: EditText
    private lateinit var btnUnlock: Button
    private lateinit var btnBiometric: ImageButton
    private lateinit var tvInit: TextView
    private lateinit var promptExecutor: Executor
    private var initPromptShown = false   // 首次安装设密引导只自动弹一次

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_unlock)

        etPassword = findViewById(R.id.etPassword)
        btnUnlock = findViewById(R.id.btnUnlock)
        btnBiometric = findViewById(R.id.btnBiometric)
        tvInit = findViewById(R.id.tvInit)
        promptExecutor = ContextCompat.getMainExecutor(this)
        refreshBrandLogo() // 顶部门面头像与列表页头像同步（未设置则回落 17°）

        // 已初始化则显示生物识别入口；未初始化显示"设置主密码"入口，
        // 并自动弹出设置密码引导（2026-09-06 用户需求：刚安装没有密码时主动提示设置）
        Vault.data.isInitialized { res ->
            val initialized = res.getOrDefault(false)
            runOnUiThread {
                tvInit.visibility = if (initialized) View.GONE else View.VISIBLE
                btnBiometric.visibility = if (initialized) View.VISIBLE else View.GONE
                if (!initialized && !initPromptShown) {
                    initPromptShown = true // 防 recreate 重弹（旋转/主题切换）
                    showInitMasterPasswordDialog()
                }
            }
        }

        btnUnlock.setOnClickListener {
            val pw = etPassword.text.toString()
            if (pw.isBlank()) return@setOnClickListener
            // Argon2id 派生在后台线程跑（契约参数 m=32MB/t=2，手机上约 1s）；给 loading 态防"点了没反应"重复点击。
            setBusy(true)
            Vault.data.unlockWithPassword(pw) { r ->
                runOnUiThread {
                    setBusy(false)
                    if (r.getOrDefault(false)) goList()
                    else {
                        // 审查 F2：被失败限流冷却挡住时，别再报"密码错误"（会误导用户以为记错密码）
                        val remainSec = (Vault.data.lockoutRemainingMs() + 999) / 1000
                        etPassword.error = if (remainSec > 0) {
                            getString(R.string.unlock_locked_out, remainSec)
                        } else {
                            getString(R.string.unlock_fail)
                        }
                    }
                }
            }
        }

        btnBiometric.setOnClickListener { startBiometricUnlock() }

        tvInit.setOnClickListener { showInitMasterPasswordDialog() }
    }

    // 顶部门面头像：与列表页 AvatarStore 同一来源（files/avatar.jpg）。
    // 有头像 → 圆形头像（RoundedBitmapDrawable）覆盖 17°；无头像 → 回落文字 LOGO。
    private fun refreshBrandLogo() {
        val img = findViewById<ImageView>(R.id.ivBrandLogo)
        val txt = findViewById<TextView>(R.id.tvBrandLogo)
        val bmp = com.qiqiao.passwordvault.util.AvatarStore.load(this)
        if (bmp != null) {
            val d = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp)
            d.isCircular = true
            img.setImageDrawable(d)
            img.visibility = View.VISIBLE
            txt.visibility = View.GONE
        } else {
            img.visibility = View.GONE
            txt.visibility = View.VISIBLE
        }
    }

    // 首次设置主密码（审计 P2#2 修复）：两段输入（设置→确认），最小 8 位（复用 MIN_NEW_PASSWORD_LEN，审查 F3 由 4 提到 8），
    // 两次一致才初始化——不再直接拿解锁输入框的单个值当作主密码（原实现可设 1 字符弱口令）。
    private fun showInitMasterPasswordDialog() {
        val dialog = com.qiqiao.passwordvault.ui.common.NeuDialog
        dialog.showInput(
            this,
            title = getString(R.string.init_title),
            hint = getString(R.string.unlock_hint),
            isPassword = true,
            onConfirm = { p1 ->
                when {
                    p1.length < com.qiqiao.passwordvault.util.MIN_NEW_PASSWORD_LEN -> {
                        Toast.makeText(this, R.string.init_too_short, Toast.LENGTH_SHORT).show()
                        false // 保持第一段打开，重输
                    }
                    else -> {
                        // 第一段通过 → 第二段确认（一致才真正初始化）
                        dialog.showInput(
                            this,
                            title = getString(R.string.init_confirm),
                            hint = getString(R.string.unlock_hint),
                            isPassword = true,
                            onConfirm = { p2 ->
                                when {
                                    p2 != p1 -> {
                                        Toast.makeText(this, R.string.init_mismatch, Toast.LENGTH_SHORT).show()
                                        false // 保持确认段打开
                                    }
                                    else -> {
                                        setBusy(true)
                                        Vault.data.initializeMasterPassword(p2) { r ->
                                            runOnUiThread {
                                                setBusy(false)
                                                if (r.getOrDefault(false)) showInitSuccessReminder()
                                                else Toast.makeText(
                                                    this, R.string.unlock_fail, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        true
                                    }
                                }
                            }
                        )
                        true // 关闭第一段，进入确认段
                    }
                }
            }
        )
    }

    // 设置成功提醒（2026-09-06 用户需求）：牢记主密码；忘记可用指纹解锁；无指纹则无法找回。
    // 用户点"知道了"才进主列表。
    private fun showInitSuccessReminder() {
        com.qiqiao.passwordvault.ui.common.NeuDialog.showConfirm(
            this,
            title = getString(R.string.init_success_title),
            message = getString(R.string.init_success_reminder),
            okText = getString(R.string.common_ok),
            onOk = { goList() }
        )
    }

    // #QA-029 生物识别解锁（两段式）。prepare 失败分支：
    //  - BiometricUnavailableException（未在设置页开启 / wrapped 为空）：提示先去设置开启
    //  - KeyPermanentlyInvalidatedException（系统指纹登记变更致 Keystore key 作废）：自愈 disable + 提示重开
    //  - 其它：generic 错误提示
    private fun startBiometricUnlock() {
        if (btnBiometric.isEnabled.not()) return
        setBusy(true)
        Vault.data.prepareBiometricUnlock { res ->
            runOnUiThread {
                setBusy(false)
                res.onSuccess { cipher ->
                    showBiometricPrompt(cipher) { authed ->
                        // 段 2（认证成功回调，主线程）：携同一 cipher 解包 DEK（后台）
                        Vault.data.unlockWithBiometric(authed) { r ->
                            runOnUiThread {
                                if (r.getOrDefault(false)) goList()
                                else Toast.makeText(
                                    this, R.string.unlock_fail, Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }.onFailure { e ->
                    val msgRes = when {
                        e is KeyPermanentlyInvalidatedException -> {
                            // 指纹登记已变更：Keystore key 永久失效。自愈：清配置，用户重新开启。
                            Vault.data.disableBiometric { }
                            R.string.biometric_key_invalidated
                        }
                        e.message?.contains("BIOMETRIC_UNAVAILABLE") == true -> R.string.biometric_not_enabled
                        else -> R.string.biometric_error
                    }
                    Toast.makeText(this, msgRes, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showBiometricPrompt(cipher: Cipher, onAuthenticated: (Cipher) -> Unit) {
        val prompt = BiometricPrompt(
            this, promptExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    // CryptoObject 携带的是 prepare 的同一 cipher 实例（认证授权绑定其上）
                    result.cryptoObject?.cipher?.let(onAuthenticated)
                }
                // 认证失败重试/用户取消/系统错误：系统弹窗自处理，无需业务动作（锁态保持不变）
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    // 验证期间的忙碌态：禁用全部入口 + 主按钮改"正在验证…"，消除 KDF 计算期（约 2s）的"卡住"感。
    private fun setBusy(busy: Boolean) {
        btnUnlock.isEnabled = !busy
        tvInit.isEnabled = !busy
        btnBiometric.isEnabled = !busy
        btnUnlock.text = getString(if (busy) R.string.unlock_verifying else R.string.unlock_btn)
    }

    private fun goList() {
        startActivity(Intent(this, PasswordListActivity::class.java))
        finish()
    }
}