package vault.desktop

import java.sql.Connection
import java.sql.Types
import vault.AeadBlob
import vault.AppSettings
import vault.DEFAULT_KDF
import vault.KdfParams
import vault.SettingsPatch
import vault.VaultException
import vault.checkVerifier
import vault.deriveKey
import vault.makeVerifier
import vault.nowMillis
import vault.randomDek
import vault.randomSalt
import vault.unwrapKey
import vault.wrapKey
import vault.zeroBytes

// ============================================================================
// DesktopSettingsStore：app_settings 表访问（镜像 vault-android AppSettingsStore 语义，JDBC 版）。
//  - 密钥材料（kdf_salt / wrapped_dek / verifier）与 Android 完全同构——同一份 Schema.kt DDL。
//  - deriveKek 密码错误返回 null（不抛）；unwrapDek / changeMasterPassword 失败显式抛（防静默失败）。
//  - 生物识别列（wrapped_dek_biometric / biometric_enabled）保持 null/0：桌面端 M3 无凭证通道（PRD D1）。
// ============================================================================

class DesktopSettingsStore(private val conn: Connection) {

    fun isInitialized(): Boolean = queryOne("SELECT initialized FROM app_settings WHERE id = 1") { rs ->
        rs.getInt(1) == 1
    } ?: false

    fun getSettings(): AppSettings = queryOne(
        "SELECT initialized, auto_lock_timeout_sec, clipboard_clear_delay_sec, theme, biometric_enabled " +
            "FROM app_settings WHERE id = 1"
    ) { rs ->
        AppSettings(
            initialized = rs.getInt(1) == 1,
            autoLockTimeoutSec = rs.getInt(2),
            clipboardClearDelaySec = rs.getInt(3),
            theme = rs.getString(4),
            biometricEnabled = rs.getInt(5) == 1
        )
    } ?: throw VaultException("app_settings 行缺失：库未初始化")

    /** 局部更新设置（可空子集）。与 Android 版一致：仅 updated_at 变更时返回 false。 */
    fun updateSettings(patch: SettingsPatch): Boolean {
        val sets = mutableListOf<String>()
        val vals = mutableListOf<Any>()
        patch.autoLockTimeoutSec?.let { sets += "auto_lock_timeout_sec = ?"; vals += it }
        patch.clipboardClearDelaySec?.let { sets += "clipboard_clear_delay_sec = ?"; vals += it }
        patch.theme?.let { sets += "theme = ?"; vals += it }
        patch.biometricEnabled?.let { sets += "biometric_enabled = ?"; vals += if (it) 1 else 0 }
        if (sets.isEmpty()) return false
        sets += "updated_at = ?"; vals += nowMillis()
        return update("UPDATE app_settings SET ${sets.joinToString(", ")} WHERE id = 1", vals)
    }

    /** 由主密码派生 KEK 并校验 verifier；密码错误返回 null。 */
    fun deriveKek(password: String): ByteArray? {
        val (params, salt, verifier) = readKdfAndVerifier() ?: return null
        val kek = deriveKey(password, salt, params)
        return if (checkVerifier(kek, AeadBlob.fromBytes(verifier))) kek else null
    }

    /** 登录 KDF 参数 + 盐（"主密码同源导出"复用，禁止自研 Argon2）。 */
    fun getKdfMaterial(): Pair<KdfParams, ByteArray>? = readKdfAndVerifier()?.let { it.first to it.second }

    fun unwrapDek(kek: ByteArray): ByteArray {
        val wrapped = queryOne("SELECT wrapped_dek FROM app_settings WHERE id = 1") { rs -> rs.getBytes(1) }
            ?: throw VaultException("缺少 wrapped_dek")
        return unwrapKey(kek, AeadBlob.fromBytes(wrapped))
    }

    /** 改主密码：校验旧 KEK → 解包 DEK → 新盐新 KEK → 重包 DEK + 新 verifier（DEK 不变）。 */
    fun changeMasterPassword(oldPassword: String, newPassword: String): Boolean {
        val kekOld = deriveKek(oldPassword) ?: return false
        val dek = unwrapDek(kekOld)
        val newSalt = randomSalt()
        val kekNew = deriveKey(newPassword, newSalt, DEFAULT_KDF)
        val newWrapped = wrapKey(kekNew, dek)
        val newVerifier = makeVerifier(kekNew)
        val ok = update(
            "UPDATE app_settings SET kdf_salt = ?, wrapped_dek = ?, verifier = ?, updated_at = ? WHERE id = 1",
            listOf(newSalt, newWrapped.toBytes(), newVerifier.toBytes(), nowMillis())
        )
        zeroBytes(kekOld); zeroBytes(kekNew)
        return ok
    }

    /** 首次初始化写入（id=1，initialized=1）。生物识别列置 null/0（桌面 M3 无凭证通道）。 */
    fun writeInitialization(salt: ByteArray, wrappedDek: AeadBlob, verifier: AeadBlob) {
        val now = nowMillis()
        executeInsert(
            "INSERT INTO app_settings (id, initialized, kdf_algo, kdf_memory_kb, kdf_iterations, kdf_parallelism, " +
                "kdf_salt, wrapped_dek, verifier, wrapped_dek_biometric, biometric_enabled, " +
                "auto_lock_timeout_sec, clipboard_clear_delay_sec, theme, created_at, updated_at) " +
                "VALUES (1,1,'argon2id',?,?,?,?,?,?,NULL,0,60,30,'system',?,?)",
            listOf(
                DEFAULT_KDF.memoryKb, DEFAULT_KDF.iterations, DEFAULT_KDF.parallelism,
                salt, wrappedDek.toBytes(), verifier.toBytes(), now, now
            )
        )
    }

    fun usesDefaultKdf(): Boolean = readKdfAndVerifier()?.first == DEFAULT_KDF

    /** KDF 档位迁移（解锁成功后静默调用；与 Android 版语义一致）。 */
    fun migrateKdf(target: KdfParams, salt: ByteArray, wrappedDek: ByteArray, verifier: ByteArray): Boolean =
        update(
            "UPDATE app_settings SET kdf_memory_kb = ?, kdf_iterations = ?, kdf_parallelism = ?, " +
                "kdf_salt = ?, wrapped_dek = ?, verifier = ?, updated_at = ? WHERE id = 1",
            listOf(target.memoryKb, target.iterations, target.parallelism, salt, wrappedDek, verifier, nowMillis())
        )

    // ---- 内部 JDBC 辅助（与契约测试 harness 同一范式） ----

    private fun readKdfAndVerifier(): Triple<KdfParams, ByteArray, ByteArray>? = queryOne(
        "SELECT kdf_memory_kb, kdf_iterations, kdf_parallelism, kdf_salt, verifier FROM app_settings WHERE id = 1"
    ) { rs ->
        Triple(KdfParams("argon2id", rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getBytes(4), rs.getBytes(5))
    }

    private inline fun <T> queryOne(sql: String, map: (java.sql.ResultSet) -> T): T? =
        conn.prepareStatement(sql).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null } }

    private fun update(sql: String, vals: List<Any>): Boolean =
        conn.prepareStatement(sql).use { ps -> bindAll(ps, vals); ps.executeUpdate() > 0 }

    private fun executeInsert(sql: String, vals: List<Any>) {
        conn.prepareStatement(sql).use { ps -> bindAll(ps, vals); ps.executeUpdate() }
    }

    private fun bindAll(ps: java.sql.PreparedStatement, vals: List<Any>) {
        vals.forEachIndexed { i, v ->
            when (v) {
                is Int -> ps.setInt(i + 1, v)
                is Long -> ps.setLong(i + 1, v)
                is String -> ps.setString(i + 1, v)
                is ByteArray -> ps.setBytes(i + 1, v)
                else -> ps.setObject(i + 1, v)
            }
        }
    }
}
