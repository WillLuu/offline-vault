package com.qiqiao.passwordvault.util

// 列表页掩码：固定 8 个圆点，不泄露明文长度（防长度推断攻击）
fun maskPassword(): String = "••••••••"

// 掩码或占位：空/ null 密码显示短横，避免界面出现空白误导
fun maskOrPlaceholder(plain: String?): String =
    if (plain.isNullOrEmpty()) "—" else "••••••••"

// 自检：边界（空 / null）处理
fun testMask() {
    assert(maskPassword() == "••••••••") { "mask 长度必须为 8" }
    assert(maskOrPlaceholder("") == "—") { "空串应显示占位" }
    assert(maskOrPlaceholder(null) == "—") { "null 应显示占位" }
    assert(maskOrPlaceholder("secret123") == "••••••••") { "明文应掩码" }
}
