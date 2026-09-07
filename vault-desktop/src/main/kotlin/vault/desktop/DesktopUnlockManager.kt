package vault.desktop

import vault.VaultException
import vault.VaultSession
import vault.DEFAULT_KDF
import vault.deriveKey
import vault.makeVerifier
import vault.randomDek
import vault.randomSalt
import vault.wrapKey
import vault.zeroBytes

// ============================================================================
// DesktopUnlockManager：解锁/锁定会话（镜像 vault-android UnlockManager，去除生物识别通道）。
//  - 首次初始化：建盐 → KEK 派生 → 随机 DEK → 包 DEK + verifier → 写 app_settings(id=1) + 种子分类。
//  - 密码解锁：KEK → verifier 校验 → 解包 DEK 驻内存；旧档 KDF 成功后静默迁移（DEK 不变）。
//  - 失败限流（SEC-8，与 Android 同参数）：连续 5 次失败起冷却，每次 +30s 递增，封顶 5 分钟；
//    冷却期内连 KDF 都不执行（防白烧 CPU，也防限流形同虚设）。
//  - 生物识别：桌面端 M3 无凭证通道（PRD D1 决策 MVP=C 纯主密码）；主密码即唯一通道 + 永远可用。
// ============================================================================

private const val LOCKOUT_AFTER_FAILS = 5
private const val LOCKOUT_BASE_MS = 30_000L
private const val LOCKOUT_MAX_MS = 300_000L

class DesktopUnlockManager(private val db: DesktopDb) {
    private val settings = DesktopSettingsStore(db.connection)
    private val categoryDao = DesktopCategoryDao(db.connection)
    private val session = VaultSession()

    // 限流计数内存态、不落盘（与 Android 同一 ponytail 权衡：避免自伤锁死 + 不新增持久化面）。
    private var failedAttempts = 0
    private var blockUntilMs = 0L

    fun lockoutRemainingMs(): Long {
        val remain = blockUntilMs - System.currentTimeMillis()
        return if (remain > 0) remain else 0L
    }

    private fun resetLockout() { failedAttempts = 0; blockUntilMs = 0L }

    fun isInitialized(): Boolean = settings.isInitialized()

    fun initializeMasterPassword(password: String): Boolean {
        if (settings.isInitialized()) return false
        val salt = randomSalt()
        val kek = deriveKey(password, salt, DEFAULT_KDF)
        val dek = randomDek()
        val wrapped = wrapKey(kek, dek)
        val verifier = makeVerifier(kek)
        settings.writeInitialization(salt, wrapped, verifier)
        categoryDao.seedDefaultsIfEmpty()
        session.unlock(dek)
        zeroBytes(kek)
        return true
    }

    fun unlockWithPassword(password: String): Boolean {
        if (System.currentTimeMillis() < blockUntilMs) return false
        val kek = settings.deriveKek(password)
        if (kek == null) {
            failedAttempts++
            if (failedAttempts >= LOCKOUT_AFTER_FAILS) {
                val step = failedAttempts - LOCKOUT_AFTER_FAILS + 1
                blockUntilMs = System.currentTimeMillis() +
                    (LOCKOUT_BASE_MS * step).coerceAtMost(LOCKOUT_MAX_MS)
            }
            return false
        }
        val dek = settings.unwrapDek(kek)
        session.unlock(dek)
        if (!settings.usesDefaultKdf()) {
            settings.getKdfMaterial()?.second?.let { salt ->
                val kekNew = deriveKey(password, salt, DEFAULT_KDF)
                settings.migrateKdf(
                    DEFAULT_KDF, salt,
                    wrapKey(kekNew, dek).toBytes(),
                    makeVerifier(kekNew).toBytes()
                )
                zeroBytes(kekNew)
            }
        }
        zeroBytes(kek)
        resetLockout()
        return true
    }

    fun changeMasterPassword(oldPassword: String, newPassword: String): Boolean =
        settings.changeMasterPassword(oldPassword, newPassword)

    fun lock() = session.lock()
    fun isLocked(): Boolean = session.isLocked()
    fun getActiveDek(): ByteArray? = session.getActiveDek()

    fun settings(): DesktopSettingsStore = settings
}
