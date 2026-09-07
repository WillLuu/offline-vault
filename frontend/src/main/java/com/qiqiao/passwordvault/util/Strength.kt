package com.qiqiao.passwordvault.util

// 密码强度评估（0..5 段，用于详情页强度条展示）。
// 纯 Kotlin 无 Android 依赖，遵循 SelfChecks 静默自检惯例。
// 评分规则（与模板 43 秘匣密码本强度条对齐）：
//   长度分： <8=0  8-11=1  12-15=2  >=16=3
//   类别分： 小写/大写/数字/符号 每含一类 +0.5（封顶 2）
//   总分四舍五入到 0..5。常见弱口令（全同字符/纯数字短串）强制 <=1。
fun passwordStrength(pw: String): Int {
    if (pw.isEmpty()) return 0
    var lenScore = when {
        pw.length >= 16 -> 3
        pw.length >= 12 -> 2
        pw.length >= 8 -> 1
        else -> 0
    }
    var cls = 0
    if (pw.any { it.isLowerCase() }) cls++
    if (pw.any { it.isUpperCase() }) cls++
    if (pw.any { it.isDigit() }) cls++
    if (pw.any { !it.isLetterOrDigit() }) cls++
    val clsScore = (cls * 0.5).coerceAtMost(2.0)
    var total = Math.round(lenScore + clsScore).toInt()
    // 弱口令惩罚：单一字符类别且长度 < 12，或全同字符
    if (pw.toSet().size == 1) total = total.coerceAtMost(1)
    if (cls <= 1 && pw.length < 12) total = total.coerceAtMost(1)
    return total.coerceIn(0, 5)
}

// 强度文案（与强度条档位对应，详情页直接展示）
fun strengthLabel(s: Int): String = when {
    s <= 1 -> "弱"
    s == 2 -> "中等"
    s == 3 -> "强"
    else -> "极强"
}

// 自检：边界 + 规则覆盖
fun testStrength() {
    assert(passwordStrength("") == 0) { "空串为 0" }
    assert(passwordStrength("123456") == 1) { "短纯数字应为弱" }
    assert(passwordStrength("aaaaaaaa") == 1) { "全同字符应为弱" }
    assert(passwordStrength("Abc12345") == 3) { "常规 8 位混合" }
    assert(passwordStrength("Abc123!@#xyz") == 4) { "12 位混合应为强（4 段）" }
    assert(passwordStrength("Tr0ub4dor&3x9KmQv2") == 5) { "16+ 位混合封顶" }
    assert(strengthLabel(0) == "弱" && strengthLabel(2) == "中等") { "文案映射" }
}
