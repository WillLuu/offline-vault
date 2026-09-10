package vault

import android.database.sqlite.SQLiteDatabase
import javax.crypto.Cipher

// ============================================================================
// UnlockManager：解锁 / 锁定会话（契约 §3.6 / §5）。具体类，零抽象。Android 胶水层。
//  - 组合 VaultSession（内存 DEK 持有，纯） + AppSettingsStore（密钥材料，Android DB） + BiometricKeystore（可选）。
//  - 首次初始化：建盐 -> 派生 KEK -> 随机 DEK -> 包 DEK+verifier(+生物识别) -> 写 app_settings(id=1)。
//  - 解锁：密码路径派生 KEK -> 校验 verifier -> 解包 DEK 驻留内存；生物识别路径 Keystore 解包 DEK。
//  - 锁定：立即清零内存 DEK（切后台/超时触发，契约 §5）。
//  - 内存安全：KEK 用毕即 zeroBytes；DEK 仅在内存，lock() 清零。
// ============================================================================

// 密码解锁失败限流参数（审查 F2）：连续失败 5 次起进入冷却，每次 +30s 递增，上限 5 分钟。
// 目的：抬高"持设备者反复试主密码"的离线成本——原实现唯一节流只有 Argon2id 单次派生开销。
private const val LOCKOUT_AFTER_FAILS = 5
private const val LOCKOUT_BASE_MS = 30_000L
private const val LOCKOUT_MAX_MS = 300_000L

class UnlockManager(
    private val db: SQLiteDatabase,
    private val biometric: BiometricKeystore? = null
) {
    private val settings = AppSettingsStore(db)
    private val session = VaultSession()
    private val categoryDao = CategoryDao(db) { session.getActiveDek() }

    // 连续密码错误计数与冷却截止时刻（审查 F2）。
    // ponytail: 计数仅内存态（进程重启即清零），不落盘——刻意避免新增持久化面，也避免
    // "输错几次就把自己永久锁死"的自伤风险。 | 升级阈值：威胁模型升级到"设备丢失+可重启进程绕过"
    // 时，再改为加密计数器落盘并配合时间源防回滚。
    private var failedAttempts = 0
    private var blockUntilMs = 0L

    /** 距离解除冷却的剩余毫秒；0 表示当前未被限流（供 UI 提示"请 N 秒后重试"）。 */
    fun lockoutRemainingMs(): Long {
        val remain = blockUntilMs - System.currentTimeMillis()
        return if (remain > 0) remain else 0L
    }

    private fun resetLockout() {
        failedAttempts = 0
        blockUntilMs = 0L
    }

    // 首次初始化主密码。已初始化则返回 false（防重复初始化）。解锁成功后进入解锁态。
    // #QA-029：不再在初始化时 wrap DEK——生物识别 key 为 per-op 认证（无 BiometricPrompt 授权 doFinal 必抛
    // UserNotAuthenticated），初始化时无授权上下文。生物识别改为设置页 opt-in（开启时弹指纹确认）。
    fun initializeMasterPassword(password: String): Boolean {
        if (settings.isInitialized()) return false
        val salt = randomSalt()
        val kek = deriveKey(password, salt, DEFAULT_KDF)
        val dek = randomDek()
        val wrapped = wrapKey(kek, dek)
        val verifier = makeVerifier(kek)
        settings.writeInitialization(salt, wrapped, verifier, null)
        session.unlock(dek) // 先解锁：种子分类名需加密（v2 需 DEK）
        // 首次初始化即写入种子分类（契约 §3.2：支付/社交/工作/娱乐/邮箱/其他，"其他"不可删）。
        categoryDao.seedDefaultsIfEmpty()
        zeroBytes(kek) // KEK 不落盘，用毕清零
        return true
    }

    // 密码解锁：派生 KEK -> 校验 verifier -> 解包 DEK 驻留内存。失败（密码错）返回 false。
    // 2026-09-06 提速：成功后若旧库仍是上一档 KDF（m64/t3），同密码静默迁移至 DEFAULT_KDF
    // （m32/t2），重包 DEK/verifier（DEK 不变，条目无需重加密）。仅首次该库密码解锁时执行一次。
    fun unlockWithPassword(password: String): Boolean {
        // 冷却期内直接拒绝，连 KDF 派生都不做：既抬高反复试密码的成本，也避免白烧 CPU
        // （否则限流形同虚设——攻击者仍能稳定地每次都付满算力）。
        if (System.currentTimeMillis() < blockUntilMs) return false
        val kek = settings.deriveKek(password)
        if (kek == null) {
            // 密码错误：累计失败次数，达阈值后进入冷却（按超出次数递增，封顶）。
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
        migrateVaultDataIfNeeded(db, dek) // v1→v2 数据迁移（幂等；解锁后需 DEK）
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
        resetLockout() // 解锁成功：清空失败计数与冷却
        return true
    }

    // 生物识别解锁：Keystore 解包 wrapped_dek_biometric -> DEK 驻留内存（#QA-029 实装）。
    // 两段式：先 prepareBiometricUnlockCipher() 拿 cipher 交前端 BiometricPrompt.CryptoObject；
    // 指纹认证成功后前端携同一 cipher 回调本方法完成解包。无配置时抛 BiometricUnavailableException。
    // ---- 生物识别（#QA-029 实装）：两段式 Cipher 流程，Keystore key 为 per-op 认证 ----

    // 段 1（开启，设置页）：初始化 ENCRYPT cipher 交前端 BiometricPrompt.CryptoObject。
    fun prepareBiometricWrapCipher(): Cipher {
        val bio = biometric ?: throw BiometricUnavailableException()
        return bio.initWrapCipher()
    }

    // 段 2（开启，认证成功回调内）：用已授权 cipher 包裹当前会话 DEK 并落库。会话须处解锁态（DEK 在内存）。
    fun completeBiometricEnable(cipher: Cipher): Boolean {
        val bio = biometric ?: throw BiometricUnavailableException()
        val dek = session.getActiveDek() ?: throw VaultException("会话已锁定，无法开启生物识别")
        val wrapped = bio.wrapDekWith(cipher, dek)
        settings.setBiometricWrapped(wrapped)
        return true
    }

    // 段 1（解锁，解锁页）：初始化 DECRYPT cipher 交前端 BiometricPrompt.CryptoObject。
    // 无生物识别配置 / 未开启（wrapped 为空）时抛 BiometricUnavailableException。
    fun prepareBiometricUnlockCipher(): Cipher {
        val bio = biometric ?: throw BiometricUnavailableException()
        val wrapped = settings.getBiometricWrapped() ?: throw BiometricUnavailableException()
        return bio.initUnwrapCipher(wrapped)
    }

    // 段 2（解锁，认证成功回调内）：解包 DEK 驻留内存。
    fun unlockWithBiometric(cipher: Cipher): Boolean {
        val bio = biometric ?: throw BiometricUnavailableException()
        val wrapped = settings.getBiometricWrapped() ?: throw BiometricUnavailableException()
        val dek = bio.unwrapDekWith(cipher, wrapped)
        session.unlock(dek)
        migrateVaultDataIfNeeded(db, dek) // v1→v2 数据迁移（幂等；生物识别解锁后同样需迁移）
        return true
    }

    // 关闭生物识别：清 wrapped_dek_biometric + 删 Keystore key（指纹登记变更失效时也走此处重置）。
    fun disableBiometric() {
        settings.setBiometricWrapped(null)
        biometric?.deleteKey()
    }

    // 锁定：立即清除内存 DEK（切后台/超时触发，契约 §5）。
    fun lock() = session.lock()

    fun isLocked(): Boolean = session.isLocked()

    // 活跃 DEK；null = 已锁定，DAO 据此拒绝数据访问。
    fun getActiveDek(): ByteArray? = session.getActiveDek()
}
