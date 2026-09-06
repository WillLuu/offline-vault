package com.qiqiao.passwordvault.util

// 解析主题：system 结合系统深色标志解析为布尔（true=深色）
// 未知值兜底为 system，避免脏数据导致崩溃
fun resolveTheme(theme: String, systemIsDark: Boolean): Boolean {
    return when (theme) {
        "dark" -> true
        "light" -> false
        else -> systemIsDark   // system 及未知值
    }
}

// 自检：三态 + 兜底
fun testTheme() {
    assert(resolveTheme("dark", false)) { "dark" }
    assert(!resolveTheme("light", true)) { "light" }
    assert(resolveTheme("system", true)) { "system + 深色系统" }
    assert(!resolveTheme("system", false)) { "system + 浅色系统" }
    assert(resolveTheme("unknown", true)) { "未知值兜底为 system" }
}
