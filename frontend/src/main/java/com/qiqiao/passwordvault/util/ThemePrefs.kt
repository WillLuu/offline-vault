package com.qiqiao.passwordvault.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

// 主题持久化与应用：与设置页 spinner 四态一致（light / dark / system / morandi）。
// - applyTheme：在 MainApplication.onCreate 调用，确保首屏即应用持久主题；
// - setTheme：在设置页 spinner 选中时即时调用，write + 立即 setDefaultNightMode + 记录 theme，
//   无需重启 Activity（AppCompatDelegate 为进程级生效，AppCompat 自动重建可见 Activity）；
// - morandi 无系统限定符：ThemePrefs 保证 base = NIGHT_NO（亮色资源基调），由 PwdBaseActivity
//   在 super.onCreate 前 setTheme(Theme.PasswordVault.Morandi) 运行时换肤（第三主题的唯一机制）。
//
// ponytail: 共用一个私有 SharedPreferences 文件，不引 androidx.preference / PreferenceManager | 触发升级阈值：需与设置页其他偏好统一治理时改 PreferenceManager.getDefaultSharedPreferences
object ThemePrefs {

    private const val PREFS_NAME = "passwordvault_prefs"
    private const val KEY_THEME = "theme_mode"
    private const val DEFAULT_THEME = "system"

    private val VALID_THEMES = setOf("light", "dark", "system", "morandi")

    private val MODE_LIGHT = AppCompatDelegate.MODE_NIGHT_NO            // 1  亮色
    private val MODE_DARK = AppCompatDelegate.MODE_NIGHT_YES            // 2  暗色
    private val MODE_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM // -1 跟随系统

    // 当前持久主题值（已兜底）；供 PwdBaseActivity 判断是否应用 morandi 换肤。
    fun currentTheme(context: Context): String = sanitizeTheme(readTheme(context))

    // 读持久主题并应用（Application 启动入口）。key 缺失 → 默认 system。
    fun applyTheme(context: Context) {
        val theme = currentTheme(context)
        AppCompatDelegate.setDefaultNightMode(nightModeForTheme(theme))
    }

    // 设置页即时写入并应用（spinner 选中即生效，无需重建 Activity）。
    fun setTheme(context: Context, theme: String) {
        val safe = sanitizeTheme(theme)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, safe)
            .apply()
        // 记录切换时刻：切主题的 recreate 在部分 ROM 上 isChangingConfigurations=false，
        // 前台计数归零误触发自动锁（"改个主题就要重新输密码"）——主锁逻辑按此窗口豁免。
        lastThemeChangeAt = android.os.SystemClock.uptimeMillis()
        AppCompatDelegate.setDefaultNightMode(nightModeForTheme(safe))
    }

    /** 最近一次主题切换时刻（uptimeMillis）；MainApplication 锁定判定用豁免窗口。 */
    @Volatile
    var lastThemeChangeAt: Long = 0L
        private set

    private fun readTheme(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)

    // 脏数据兜底：越界值回退 system（防 SharedPreferences 被篡改 / 旧版数据导致崩溃）。
    private fun sanitizeTheme(theme: String?): String =
        if (theme != null && theme in VALID_THEMES) theme else DEFAULT_THEME

    // 纯映射：主题值 → AppCompatDelegate night mode。越界即抛（内部不变量防护）。
    private fun nightModeForTheme(theme: String): Int {
        if (theme !in VALID_THEMES) throw IllegalArgumentException("越界主题值: $theme")
        return when (theme) {
            "light", "morandi" -> MODE_LIGHT   // morandi 基于亮色资源基调 + PwdBaseActivity 运行时换肤
            "dark" -> MODE_DARK
            else -> MODE_SYSTEM   // system（及兜底）
        }
    }

    // 静默自检：assert 全过即静默退出；任一失败抛 AssertionError（需 -ea 启用）。
    fun testThemePrefs() {
        assert(MODE_LIGHT == AppCompatDelegate.MODE_NIGHT_NO) { "MODE_LIGHT 常量漂移" }
        assert(MODE_DARK == AppCompatDelegate.MODE_NIGHT_YES) { "MODE_DARK 常量漂移" }
        assert(MODE_SYSTEM == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) { "MODE_SYSTEM 常量漂移" }
        assert(nightModeForTheme("light") == MODE_LIGHT)
        assert(nightModeForTheme("dark") == MODE_DARK)
        assert(nightModeForTheme("system") == MODE_SYSTEM)
        assert(nightModeForTheme("morandi") == MODE_LIGHT) { "morandi 应基于亮色基调" }
        assert(runCatching { nightModeForTheme("bogus") }.isFailure) { "越界值应抛异常" }
        assert(sanitizeTheme("bogus") == DEFAULT_THEME) { "脏数据应兜底 system" }
        assert(sanitizeTheme(null) == DEFAULT_THEME)
        assert(sanitizeTheme("light") == "light")
        assert(sanitizeTheme("morandi") == "morandi")
    }
}