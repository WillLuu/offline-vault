package com.qiqiao.passwordvault.util

import java.security.SecureRandom

// 字符集开关（对应生成器页四个勾选框）
data class CharsetFlags(
    val upper: Boolean = true,
    val lower: Boolean = true,
    val digit: Boolean = true,
    val symbol: Boolean = false
)

private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
private const val DIGIT = "0123456789"
private const val SYMBOL = "!@#$%^&*()-_=+[]{};:,.<>?"

// 构建字符池；全关返回空串
fun buildCharset(flags: CharsetFlags): String {
    val sb = StringBuilder()
    if (flags.upper) sb.append(UPPER)
    if (flags.lower) sb.append(LOWER)
    if (flags.digit) sb.append(DIGIT)
    if (flags.symbol) sb.append(SYMBOL)
    return sb.toString()
}

// 生成密码：长度夹紧到 [4,64]；字符集为空返回空串（非异常，由 UI 禁用生成）
fun generatePassword(length: Int, flags: CharsetFlags): String {
    val pool = buildCharset(flags)
    if (pool.isEmpty()) return ""   // ponytail: UI 已禁用生成按钮 | 触发升级阈值：需要自定义字符集
    val len = length.coerceIn(4, 64)
    val rng = SecureRandom()
    return buildString(len) { repeat(len) { append(pool[rng.nextInt(pool.length)]) } }
}

// 自检：空池 / 长度夹紧 / 字符集构成
fun testGenerator() {
    assert(buildCharset(CharsetFlags(false, false, false, false)).isEmpty()) { "全关应为空池" }
    assert(buildCharset(CharsetFlags(true, false, false, false)) == UPPER) { "仅大写" }
    assert(generatePassword(12, CharsetFlags(true, true, true, false)).length == 12) { "正常长度" }
    assert(generatePassword(2, CharsetFlags(true, false, false, false)).length == 4) { "短长度夹紧到 4" }
    assert(generatePassword(100, CharsetFlags(false, true, false, false)).length == 64) { "长长度夹紧到 64" }
    assert(generatePassword(10, CharsetFlags(false, false, false, false)).isEmpty()) { "空池返回空" }
    val onlyDigit = generatePassword(20, CharsetFlags(false, false, true, false))
    assert(onlyDigit.all { it in DIGIT }) { "仅数字字符集产出应全为数字" }
}
