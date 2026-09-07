package com.qiqiao.passwordvault

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import com.qiqiao.passwordvault.data.RealVaultData
import com.qiqiao.passwordvault.data.Vault
import com.qiqiao.passwordvault.ui.common.NeuShadowPrefs
import com.qiqiao.passwordvault.ui.unlock.UnlockActivity
import com.qiqiao.passwordvault.util.ClipboardHelper
import com.qiqiao.passwordvault.util.ThemePrefs

// 应用入口：注入数据层 + 即时应用主题（来自 SharedPreferences，避免等异步加载）
// + 应用级生命周期：切后台立即锁、回前台若已锁跳回解锁、前台空闲超时锁（契约 §5）。
class MainApplication : Application() {

    // 前台 Activity 计数：仅在计数归零（真正切后台）时锁，避免页间跳转误锁。
    // ponytail: 用 Application.ActivityLifecycleCallbacks 的计数器，不引 lifecycle-process 库 | 触发升级阈值：需进程级精确可见性时改 ProcessLifecycleOwner
    private var foregroundCount = 0

    // ---- 前台空闲超时锁（契约 §5）----
    // 最简方案：单 Handler + postDelayed；触屏（ACTION_DOWN）重置计时；到点 lock() 并跳解锁页。
    private val mainHandler = Handler(Looper.getMainLooper())
    private var idleTimeoutSec = 0       // 缓存设置值；0 = 不启用前台超时锁
    private var idleTimerArmed = false
    private var lastForeground: Activity? = null   // 到期跳转用（最近一个前台 Activity）

    private val idleLock = object : Runnable {
        override fun run() {
            idleTimerArmed = false
            if (Vault.data.isLocked()) return
            Vault.data.lock()
            jumpToUnlock()
        }
    }

    // ---- 主题切换豁免：延迟锁兜底（审计 2026-09-06 P2#1 修复）----
    // 5s 窗口本为放行"主题重建"瞬间的前台计数归零；但真后台若借窗口停留会长期不锁（DEK 留内存）。
    // 兜底：窗口内仅当计数归零时安排 1.5s 延迟锁——主题重建的新页面 onStart 到达即取消（过渡毫秒级）；
    // 若确实切到后台，1.5s 后照常 lock()，封死旁路。锁定后不主动跳解锁：回前台时 onActivityStarted 统一跳。
    private val pendingLockDelayMs = 1500L
    private val pendingLock = Runnable {
        if (foregroundCount == 0 && !Vault.data.isLocked()) {
            Vault.data.lock()
        }
    }

    private fun cancelPendingLock() {
        mainHandler.removeCallbacks(pendingLock)
    }

    private fun schedulePendingLock() {
        cancelPendingLock()
        mainHandler.postDelayed(pendingLock, pendingLockDelayMs)
    }

    // ---- 系统文件选择器（SAF/GET_CONTENT）期间不打断会话（修复：导出选位置/导入选文件被踢回解锁页）----
    // 导出/导入主动调 externalPickerStarted()；选择器是本应用发起的系统 UI，属"任务内瞬态后台"：
    //  - 选择器打开期间不立即锁、不触发 1.5s pending（用户在文件夹里可能待几十秒）；
    //  - 返回本应用（onActivityStarted）即清除；
    //  - 兜底：若用户一直停留在选择器/后台超过 PICKER_MAX_MS（120s），强制 lock()，防无限期解锁驻留。
    private var pickerUntil = 0L
    private val pickerTimeoutLock = Runnable {
        if (foregroundCount == 0 && !Vault.data.isLocked()) {
            Vault.data.lock()
        }
    }

    private fun beginExternalPickerFlow() {
        pickerUntil = SystemClock.uptimeMillis() + PICKER_MAX_MS
        cancelIdleTimer()
        mainHandler.postDelayed(pickerTimeoutLock, PICKER_MAX_MS)
    }

    private fun endExternalPickerFlow() {
        pickerUntil = 0L
        mainHandler.removeCallbacks(pickerTimeoutLock)
    }

    private val touchReset = View.OnTouchListener { _, e ->
        if (e.action == MotionEvent.ACTION_DOWN) armIdleTimer()
        false // 不消费事件，避免影响页面正常交互
    }
    // ponytail: 仅监听 Activity decorView 触屏重置计时；软键盘/硬件键盘/无障碍(TalkBack)输入、弹窗触屏均不重置 | 触发升级阈值：需覆盖键盘输入时改 dispatchKeyEvent/全局事件过滤

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 数据层注入（UI 零改动）：后端 RealVaultData（be-muyuan-2，同包 com.qiqiao.passwordvault.data）
        // 桥接 UI 与 vault.* DAO；源码集引入与 argon2-jvm/jna 依赖已在 app/build.gradle.kts 落地。
        // MockVaultData 仍保留作 debug flavor 兜底（见 backend/README.md）。
        Vault.data = RealVaultData(applicationContext)
        ThemePrefs.applyTheme(this)
        NeuShadowPrefs.init(this)
        installCrashLog()
        registerActivityLifecycleCallbacks(lockCallbacks)
    }

    // 诊断用：未捕获异常落盘到 app 专属外部目录（无需存储权限），
    // 设备 logcat 被 ROM 策略屏蔽时（本机 redmagic 实测 buffer 0 readable）由此拿崩溃栈。
    private fun installCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val dir = java.io.File(getExternalFilesDir(null), "diagnostics")
                dir.mkdirs()
                val log = java.io.File(dir, "crash.log")
                // 轮转（审计 P3/#QA-012）：超过 512KB 归档为 crash.log.old，避免无限膨胀
                if (log.exists() && log.length() > 512 * 1024) {
                    java.io.File(dir, "crash.log.old").delete()
                    log.renameTo(java.io.File(dir, "crash.log.old"))
                }
                log.appendText(
                    "\n==== ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)} thread=${t.name} ====\n" +
                    android.util.Log.getStackTraceString(e)
                )
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    private val lockCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) {
            foregroundCount++
            cancelPendingLock() // 新页面已到：主题重建过渡结束，取消窗口内延迟锁
            if (pickerUntil > 0L) endExternalPickerFlow() // 从系统选择器返回：会话保持，结束选择器豁免
            if (foregroundCount == 1) {
                // 从后台返回前台：若已锁定且当前不是解锁页，则跳回解锁（清栈）
                if (Vault.data.isLocked() && a !is UnlockActivity) {
                    jumpToUnlock()
                    return
                }
            }
            // 未锁（解锁后/正常前台，含页间跳转）：重读设置（回前台用最新值）并重置空闲计时
            refreshIdleTimeout()
        }

        override fun onActivityStopped(a: Activity) {
            foregroundCount = if (foregroundCount > 0) foregroundCount - 1 else 0
            // 配置变更（旋转等）≠ 真正切后台（#QA-022）：不触发锁、不取消空闲计时，
            // 交给新实例的 onStarted 补位计数、onResumed 重挂计时。
            // 注意：必须先正常递减计数再守卫，不能提前 return——否则旋转后计数会 +1 漂移，
            // 导致后续切后台永远不归零、永不锁（修复不得引入此回归）。
            // ponytail: 旋转窗口（onStop→新 onStart，毫秒级）内空闲计时未取消，恰逢超时会误锁 | 触发升级阈值：在 onActivityResumed 复核 foregroundCount>0 再 arm 可消除
            if (a.isChangingConfigurations()) return
            // 主题切换的 recreate 在部分 ROM 上 isChangingConfigurations=false（红魔实测），
            // 前台计数会短暂归零误触发自动锁——5 秒豁免窗口内不立即锁；
            // 但真后台会借窗口长期不锁（P2#1）→ 窗口内计数归零时安排 1.5s 延迟锁兜底：
            // 新页面 onStart 到达即取消（主题重建过渡），真后台 1.5s 后照常 lock()。
            // 主题切换豁免 或 系统文件选择器打开中：不打断（主题=瞬态前台归零用 1.5s pending 兜底；
            // 选择器=用户在系统 UI 内可停留较久，靠 120s TTL 强制锁兜底，返回即清除豁免）
            val now = SystemClock.uptimeMillis()
            val inThemeWindow = now - ThemePrefs.lastThemeChangeAt < 5000
            val inPickerFlow = pickerUntil > now
            if (inThemeWindow || inPickerFlow) {
                if (foregroundCount == 0) {
                    cancelIdleTimer()
                    if (inThemeWindow) {
                        ClipboardHelper.flushNow(this@MainApplication)
                        schedulePendingLock()
                    }
                    // inPickerFlow：不排 pending，避免打断用户选文件夹；TTL 已兜底
                }
                return
            }
            // 应用切后台：立即锁（契约 §5：切后台 = 立即锁，不等超时）
            if (foregroundCount == 0) {
                cancelIdleTimer()
                ClipboardHelper.flushNow(this@MainApplication) // 兜底清剪贴板（P2#3）
                Vault.data.lock()
            }
        }

        override fun onActivityResumed(a: Activity) {
            lastForeground = a
            // 装饰层触屏监听保留（兼容非 PwdBase 的瞬态窗口）；真正的全页触屏重置见
            // PwdBaseActivity.dispatchTouchEvent → MainApplication.userInteracted()。
            a.window.decorView.setOnTouchListener(touchReset)
            // 2026-09-06 用户需求：自动锁对所有页面统一生效——页面一恢复前台即开始新一轮空闲计时，
            // 任意触屏会重置（onUserInteracted）；到点 lock() 并跳解锁页。
            armIdleTimer()
        }

        // 页间暂停仍属前台：不在此取消计时，由下一 Activity 的 onResume 重置；
        // 弹窗/键盘等非 Activity 触屏不重置计时，属可接受边界（见 ponytail）。
        override fun onActivityPaused(a: Activity) {}

        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    // 从设置读取超时值；锁定态不可读（Mock/真实 DAO 均拒绝），保持上次值并取消计时。
    // 2026-09-06：改为读后即 arm——所有页面统一生效（到点 lock+跳解锁）。
    private fun refreshIdleTimeout() {
        if (Vault.data.isLocked()) { cancelIdleTimer(); return }
        Vault.data.getSettings { res ->
            res.onSuccess { s ->
                idleTimeoutSec = s.autoLockTimeoutSec
                armIdleTimer()
            }
        }
    }

    // 启动/重置空闲计时；0 = 不启用前台超时锁（仅取消既有计时）
    private fun armIdleTimer() {
        mainHandler.removeCallbacks(idleLock)
        idleTimerArmed = false
        if (idleTimeoutSec <= 0) return
        mainHandler.postDelayed(idleLock, idleTimeoutSec * 1000L)
        idleTimerArmed = true
    }

    private fun cancelIdleTimer() {
        mainHandler.removeCallbacks(idleLock)
        idleTimerArmed = false
    }

    // 到期/回前台已锁：跳解锁页（清栈），复用契约 §5 既有跳转模式
    private fun jumpToUnlock() {
        val from = lastForeground ?: return
        if (from is UnlockActivity) return
        val i = Intent(from, UnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        from.startActivity(i)
        from.finish()
    }

    // ---- 全应用统一空闲锁（2026-09-06 用户需求：所有页面共用同一等待时间）----
    // 由 PwdBaseActivity.dispatchTouchEvent 捕获任意控件触屏 → userInteracted() 重置计时；
    // 页面恢复前台（onActivityResumed）也重置并开始新一轮；到点 lock()+跳解锁页。

    /** 任意页面任意位置触屏（含 EditText 等被控件消费的触摸）都重置空闲计时。 */
    private fun onUserInteracted() {
        if (Vault.data.isLocked()) return
        armIdleTimer()
    }

    /** 设置页保存自动锁秒数后即时应用（无需重启/切页才生效）。0 = 关闭前台空闲锁。 */
    private fun applyAutoLockTimeout(sec: Int) {
        idleTimeoutSec = if (sec > 0) sec else 0
        armIdleTimer()   // sec<=0 时 arm 内部会清除已排定时器
    }

    companion object {
        private var instance: MainApplication? = null
        private const val PICKER_MAX_MS = 120_000L

        /** 系统文件选择器打开前调用：选择器期间豁免自动锁（导出 SAF / 导入选文件）。 */
        fun externalPickerStarted() {
            instance?.beginExternalPickerFlow()
        }

        /** 任意页面全局触屏重置空闲计时（PwdBaseActivity 调用）。 */
        fun userInteracted() {
            instance?.onUserInteracted()
        }

        /** 设置页修改自动锁超时后即时应用（所有页面共用同一值）。 */
        fun updateAutoLockTimeoutSec(sec: Int) {
            instance?.applyAutoLockTimeout(sec)
        }
    }
}
