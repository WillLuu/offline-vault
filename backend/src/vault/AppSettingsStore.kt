package vault

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

// ============================================================================
// AppSettingsStore：设置 + 密钥材料读取（契约 §3.3）。具体类，零抽象。
//  - 加密原语（deriveKey/wrapKey/...）为 VaultCrypto 顶层函数，本类直接调用（无 crypto 实例字段，避免多余抽象）。
//  - 密钥材料（kdf_salt / wrapped_dek / verifier）均经主密码 KEK 或 Keystore 保护，不落盘明文。
//  - 防静默失败：deriveKek 密码错误返回 null（不抛）；unwrapDek / changeMasterPassword 失败显式抛 VaultException。
// ============================================================================

class AppSettingsStore(private val db: SQLiteDatabase) {

    // 是否已初始化：app_settings 行存在且 initialized=1。
    fun isInitialized(): Boolean {
        db.query("app_settings", arrayOf("initialized"), "id = 1", null, null, null, null).use { c ->
            return c.moveToFirst() && c.getInt(0) == 1
        }
    }

    // 读取用户设置（不含任何密钥材料）。
    fun getSettings(): AppSettings {
        db.query(
            "app_settings",
            arrayOf("initialized", "auto_lock_timeout_sec", "clipboard_clear_delay_sec", "theme", "biometric_enabled"),
            "id = 1", null, null, null, null
        ).use { c ->
            c.moveToFirst()
            return AppSettings(
                initialized = c.getInt(0) == 1,
                autoLockTimeoutSec = c.getInt(1),
                clipboardClearDelaySec = c.getInt(2),
                theme = c.getString(3),
                biometricEnabled = c.getInt(4) == 1
            )
        }
    }

    // 局部更新设置（可空子集）。
    fun updateSettings(patch: SettingsPatch): Boolean {
        val cv = ContentValues()
        patch.autoLockTimeoutSec?.let { cv.put("auto_lock_timeout_sec", it) }
        patch.clipboardClearDelaySec?.let { cv.put("clipboard_clear_delay_sec", it) }
        patch.theme?.let { cv.put("theme", it) }
        patch.biometricEnabled?.let { cv.put("biometric_enabled", if (it) 1 else 0) }
        cv.put("updated_at", nowMillis())
        if (cv.size() <= 1) return false // 仅 updated_at、无实际变更
        return db.update("app_settings", cv, "id = 1", null) > 0
    }

    // 由主密码派生 KEK 并校验 verifier；失败（密码错误）返回 null。
    fun deriveKek(password: String): ByteArray? {
        val (params, salt, verifier) = readKdfAndVerifier() ?: return null
        val kek = deriveKey(password, salt, params)
        return if (checkVerifier(kek, AeadBlob.fromBytes(verifier))) kek else null
    }

    // 读取登录 KDF 参数 + 盐（供"用主密码导出"复用，使 .vault 与登录同源，禁止自研 Argon2）。
    fun getKdfMaterial(): Pair<KdfParams, ByteArray>? {
        return readKdfAndVerifier()?.let { it.first to it.second }
    }

    // 用 KEK 解包得到内存中的 DEK。
    fun unwrapDek(kek: ByteArray): ByteArray {
        val wrapped = readWrappedDek() ?: throw VaultException("缺少 wrapped_dek")
        return unwrapKey(kek, AeadBlob.fromBytes(wrapped))
    }

    // 改主密码：校验旧 KEK -> 解包 DEK -> 新盐新 KEK -> 重包 DEK + 新 verifier。
    // DEK 不变，条目 secret_blob 无需重加密（契约 §3.3 / §5）。
    fun changeMasterPassword(oldPassword: String, newPassword: String): Boolean {
        val kekOld = deriveKek(oldPassword) ?: return false   // 旧密码错误
        val dek = unwrapDek(kekOld)                            // DEK 不变
        val newSalt = randomSalt()
        val kekNew = deriveKey(newPassword, newSalt, DEFAULT_KDF)
        val newWrapped = wrapKey(kekNew, dek)
        val newVerifier = makeVerifier(kekNew)
        val cv = ContentValues().apply {
            put("kdf_salt", newSalt)
            put("wrapped_dek", newWrapped.toBytes())
            put("verifier", newVerifier.toBytes())
            put("updated_at", nowMillis())
        }
        val ok = db.update("app_settings", cv, "id = 1", null) > 0
        zeroBytes(kekOld); zeroBytes(kekNew) // 密钥材料用毕即清零
        return ok
    }

    // 首次初始化写入（id=1，initialized=1）。
    fun writeInitialization(salt: ByteArray, wrappedDek: AeadBlob, verifier: AeadBlob, biometricWrapped: ByteArray?) {
        val cv = ContentValues().apply {
            put("id", 1)
            put("initialized", 1)
            put("kdf_algo", "argon2id")
            put("kdf_memory_kb", DEFAULT_KDF.memoryKb)
            put("kdf_iterations", DEFAULT_KDF.iterations)
            put("kdf_parallelism", DEFAULT_KDF.parallelism)
            put("kdf_salt", salt)
            put("wrapped_dek", wrappedDek.toBytes())
            put("verifier", verifier.toBytes())
            if (biometricWrapped == null) putNull("wrapped_dek_biometric") else put("wrapped_dek_biometric", biometricWrapped)
            put("biometric_enabled", if (biometricWrapped != null) 1 else 0)
            put("auto_lock_timeout_sec", 60)
            put("clipboard_clear_delay_sec", 30)
            put("theme", "system")
            val now = nowMillis()
            put("created_at", now)
            put("updated_at", now)
        }
        db.insert("app_settings", null, cv)
    }

    fun getBiometricWrapped(): ByteArray? {
        db.query("app_settings", arrayOf("wrapped_dek_biometric"), "id = 1", null, null, null, null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null
        }
    }

    // 当前登录 KDF 档位是否已是 DEFAULT_KDF（解锁迁移判断用）。
    fun usesDefaultKdf(): Boolean = readKdfAndVerifier()?.first == DEFAULT_KDF

    // KDF 档位迁移（解锁成功时静默调用，密码已验、DEK 在手）：重写 kdf 参数列 + 同盐重包 DEK/verifier。
    fun migrateKdf(target: KdfParams, salt: ByteArray, wrappedDek: ByteArray, verifier: ByteArray): Boolean {
        val cv = ContentValues().apply {
            put("kdf_memory_kb", target.memoryKb)
            put("kdf_iterations", target.iterations)
            put("kdf_parallelism", target.parallelism)
            put("kdf_salt", salt)
            put("wrapped_dek", wrappedDek)
            put("verifier", verifier)
            put("updated_at", nowMillis())
        }
        return db.update("app_settings", cv, "id = 1", null) > 0
    }

    fun setBiometricWrapped(blob: ByteArray?) {
        val cv = ContentValues().apply {
            if (blob == null) putNull("wrapped_dek_biometric") else put("wrapped_dek_biometric", blob)
            put("biometric_enabled", if (blob != null) 1 else 0)
            put("updated_at", nowMillis())
        }
        db.update("app_settings", cv, "id = 1", null)
    }

    // ---- 内部读取 ----
    private fun readKdfAndVerifier(): Triple<KdfParams, ByteArray, ByteArray>? {
        db.query(
            "app_settings",
            arrayOf("kdf_memory_kb", "kdf_iterations", "kdf_parallelism", "kdf_salt", "verifier"),
            "id = 1", null, null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            val params = KdfParams("argon2id", c.getInt(0), c.getInt(1), c.getInt(2))
            return Triple(params, c.getBlob(3), c.getBlob(4))
        }
    }

    private fun readWrappedDek(): ByteArray? {
        db.query("app_settings", arrayOf("wrapped_dek"), "id = 1", null, null, null, null).use { c ->
            return if (c.moveToFirst()) c.getBlob(0) else null
        }
    }
}
