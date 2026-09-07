package com.qiqiao.passwordvault.data

import com.qiqiao.passwordvault.model.*
import vault.LIST_NO_LIMIT
import javax.crypto.Cipher

// UI 层调用数据层的唯一边界（api-contract.md §3）。
// 真实实现由 backend/ 提供（be-muyuan）；前端现在用 MockVaultData 驱动。
// 零抽象：仅此一个接口（非 Repository/Factory/Strategy 抽象层）。
// 所有方法异步走回调（真实实现涉及 DB/文件/Keystore，必须离主线程）。
typealias Done<T> = (Result<T>) -> Unit

interface VaultData {

    // ---- 解锁 / 初始化（契约 §5）----
    fun isInitialized(cb: Done<Boolean>)
    fun initializeMasterPassword(password: String, cb: Done<Boolean>)
    fun unlockWithPassword(password: String, cb: Done<Boolean>)
    /** 密码解锁失败冷却剩余毫秒；0 表示当前未被限流（审查 F2，UI 据此提示"请 N 秒后重试"）。 */
    fun lockoutRemainingMs(): Long

    // ---- 生物识别（#QA-029 实装）：两段式 Cipher 流程 ----
    // Keystore key 为 per-op 认证：cipher 必须经 BiometricPrompt.CryptoObject 认证后才能 doFinal。
    // prepare* 段 1 返回已 init 的 Cipher；认证成功后携同一实例回调 complete*/unlockWithBiometric。
    fun prepareBiometricWrap(cb: Done<Cipher>)            // 设置页开启：ENCRYPT cipher
    fun completeBiometricEnable(cipher: Cipher, cb: Done<Boolean>)   // 认证成功：wrap 当前会话 DEK 落库
    fun prepareBiometricUnlock(cb: Done<Cipher>)          // 解锁页：DECRYPT cipher（未开启时失败）
    fun unlockWithBiometric(cipher: Cipher, cb: Done<Boolean>)       // 认证成功：解包 DEK 解锁
    fun disableBiometric(cb: Done<Boolean>)               // 关闭：清 wrapped + 删 Keystore key

    // 解锁会话（契约 §3.6 / §5）：锁定态所有 DAO 应失败。
    // 纯内存操作（清/读 DEK 标志），无需异步回调。
    fun lock()                 // 清除内存 DEK（切后台 / 超时触发）
    fun isLocked(): Boolean    // 是否已锁定

    // ---- 密码条目（契约 §3.1）----
    fun listEntries(
        search: String? = null,
        sortBy: SortKey = SortKey.NAME_ASC,
        categoryId: Long? = null,
        limit: Int = LIST_NO_LIMIT,   // 默认全量（审查 2.1 方案 A，见 backend models.kt 常量注释）
        offset: Int = 0,
        cb: Done<List<PasswordEntryRow>>
    )

    fun getEntry(id: Long, cb: Done<PasswordEntryRow?>)
    fun createEntry(input: EntryInput, cb: Done<Long>)
    fun updateEntry(id: Long, input: EntryInput, cb: Done<Boolean>)
    fun deleteEntry(id: Long, cb: Done<Boolean>)

    // ---- 分类（契约 §3.2）----
    fun listCategories(cb: Done<List<CategoryRow>>)
    fun createCategory(name: String, sortOrder: Int, cb: Done<Long>)
    fun updateCategory(id: Long, name: String? = null, sortOrder: Int? = null, cb: Done<Boolean>)
    fun deleteCategory(id: Long, cb: Done<Boolean>)
    // 批量重排：传入期望的分类 id 顺序，写入 sortOrder = 下标（持久化手动排序）
    fun reorderCategories(orderedIds: List<Long>, cb: Done<Boolean>)

    // ---- 设置（契约 §3.3）----
    fun getSettings(cb: Done<AppSettings>)
    fun updateSettings(patch: SettingsPatch, cb: Done<Boolean>)

    // 改主密码（契约 §3.3）：旧密码错误抛 WrongPasswordException（UI 呈现「旧密码错误」）。
    // 语义：DEK 不变、保持登录态、历史条目仍可解密——成功后前端不得强制跳解锁页。
    // 签名硬契约，与 be-muyuan 的 RealVaultData 桥接一字不差。
    fun changeMasterPassword(oldPassword: String, newPassword: String, cb: Done<Unit>)

    // ---- 备份（契约 §3.4）----
    fun exportVault(password: String, useMasterPassword: Boolean, cb: Done<String>) // 返回生成的文件名
    fun importVault(fileBytes: ByteArray, password: String, cb: Done<MergeReport>)
}
