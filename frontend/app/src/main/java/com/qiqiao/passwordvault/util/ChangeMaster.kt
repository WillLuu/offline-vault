package com.qiqiao.passwordvault.util

// 修改主密码 UI 层输入校验（信任边界：旧密码非空、新密码非空、新密码长度≥8、两次一致——不得省）。
// 纯函数，无 Android 依赖，供 JVM 自检（kotlinc + java -ea）。
enum class ChangeMasterError { OLD_BLANK, NEW_BLANK, NEW_TOO_SHORT, MISMATCH }

// 新密码最小长度：与后端 RealVaultData.changeMasterPassword 前置校验对齐
// （backend/src/vault/RealVaultData.kt 私有常量 MIN_NEW_PASSWORD_LEN = 8）。
// UI 层先拦截，避免过短新密码进后端后失败被误报「旧密码错误」（#QA-023）。
// 2026-09-06 审查 F3：由 4 提到 8，与独立导出密码 MIN_EXPORT_PW_LEN(8) 策略一致——
// 主密码保护整库（比单次导出更敏感），下限不应低于导出密码。
const val MIN_NEW_PASSWORD_LEN = 8

// 返回第一个违规项；合法返回 null。
// 与 Mock/后端判空语义一致：全空白视为空（isBlank），避免「空格当密码」绕过。
fun validateChangeMaster(oldPassword: String, newPassword: String, confirmPassword: String): ChangeMasterError? =
    when {
        oldPassword.isBlank() -> ChangeMasterError.OLD_BLANK
        newPassword.isBlank() -> ChangeMasterError.NEW_BLANK
        newPassword.length < MIN_NEW_PASSWORD_LEN -> ChangeMasterError.NEW_TOO_SHORT
        newPassword != confirmPassword -> ChangeMasterError.MISMATCH
        else -> null
    }

// 自检：五边界 + 通过态（静默，无输出即通过）
// 2026-09-06 修复：MIN_NEW_PASSWORD_LEN 已由 4 提到 8（审查 F3），原 4 位样例会命中 NEW_TOO_SHORT
// 导致 MISMATCH/通过态断言失败（开 -ea 跑 SelfChecks 必抛）——样例同步为 8 位边界。
fun testChangeMaster() {
    assert(validateChangeMaster("", "a", "a") == ChangeMasterError.OLD_BLANK) { "旧密码为空" }
    assert(validateChangeMaster("   ", "a", "a") == ChangeMasterError.OLD_BLANK) { "旧密码全空白视为空" }
    assert(validateChangeMaster("old", "", "a") == ChangeMasterError.NEW_BLANK) { "新密码为空" }
    assert(validateChangeMaster("old", "  ", "  ") == ChangeMasterError.NEW_BLANK) { "新密码全空白视为空" }
    assert(validateChangeMaster("old", "abc1234", "abc1234") == ChangeMasterError.NEW_TOO_SHORT) { "新密码 7 位(<8)拦截" }
    assert(validateChangeMaster("old", "a", "b") == ChangeMasterError.NEW_TOO_SHORT) { "过短优先于不一致" }
    assert(validateChangeMaster("old", "abcd1234", "abcd1235") == ChangeMasterError.MISMATCH) { "长度达标后两次不一致" }
    assert(validateChangeMaster("old", "abcd1234", "abcd1234") == null) { "合法通过(8 位边界)" }
}
