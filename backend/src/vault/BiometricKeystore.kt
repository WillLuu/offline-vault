package vault

import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ============================================================================
// BiometricKeystore：Android Keystore 生物识别通道（契约 §4.5）。具体类，零抽象。
//  - 在 Android Keystore 生成 AES-256-GCM 密钥，设 setUserAuthenticationRequired(true) + 短时效生物识别绑定。
//  - 用该密钥包裹随机 DEK -> wrapped_dek_biometric（存 app_settings）；unlockWithBiometric 经系统弹窗确认后解包。
//  - 无 PIN 备援（已决策：契约 §10 第 5 项"主密码永远可用为兜底，生物识别无 PIN 备援"）。
//  注意：本类仅能用 Android Keystore 运行（JVM 无 AndroidKeyStore Provider），故不在 JVM 契约测试内执行；
//        其行为由 Android Instrumented 测试覆盖（见 README 待确认项）。
// ============================================================================

// ponytail: 生物识别密钥强绑定到 Keystore 且 setUserAuthenticationRequired(true)，无 PIN 备援；
// 主密码通道永久可用作兜底（契约 §10.5）。 | 升级阈值：若需求新增 PIN 备援，需放松 setUserAuthenticationRequired/增加 PIN Keyguard。
class BiometricKeystore(private val keyAlias: String = "vault_biometric_dek") {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    // 确保生物识别密钥存在；不存在则按契约参数生成（setUserAuthenticationRequired=true）。
    // 调用前需已通过 BiometricPrompt 获得授权上下文（Android 层触发）。
    fun ensureKey() {
        if (keyStore.containsAlias(keyAlias)) return
        val kg = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        val builder = android.security.keystore.KeyGenParameterSpec.Builder(
            keyAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true) // 必须生物识别/设备凭证确认
            .setInvalidatedByBiometricEnrollment(true) // 生物识别登记变更即作废，防复用
        kg.init(builder.build())
        kg.generateKey()
    }

    // ---- 两段式 API（#QA-029 实装）：Keystore key 为 per-op 认证（setUserAuthenticationRequired=true 且未设
    // validity 时长），cipher.init 必须发生在 BiometricPrompt 认证前（CryptoObject 携带同一实例），
    // doFinal 只能在认证成功的回调里调用——因此拆成 init*/…With(cipher) 两段，缺一即 UserNotAuthenticatedException。

    // 段 1（开启流程）：初始化 ENCRYPT cipher，供 BiometricPrompt.CryptoObject 携带。
    // 指纹录入变更时 key 作废 -> 此处抛 UserNotAuthenticatedException，由调用方自愈（删 key + 提示重新开启）。
    fun initWrapCipher(): Cipher {
        ensureKey()
        val key = keyStore.getKey(keyAlias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    // 段 2（开启流程，认证成功回调内）：用已授权的 cipher 包裹 DEK（nonce 取 cipher.iv）。
    fun wrapDekWith(cipher: Cipher, dek: ByteArray): ByteArray {
        val ct = cipher.doFinal(dek)
        return AeadBlob(cipher.iv, ct).toBytes()
    }

    // 段 1（解锁流程）：初始化 DECRYPT cipher（nonce 取自 wrapped blob）。
    fun initUnwrapCipher(wrapped: ByteArray): Cipher {
        val blob = AeadBlob.fromBytes(wrapped)
        val key = keyStore.getKey(keyAlias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.nonce))
        return cipher
    }

    // 段 2（解锁流程，认证成功回调内）：解包出 DEK。
    fun unwrapDekWith(cipher: Cipher, wrapped: ByteArray): ByteArray {
        val blob = AeadBlob.fromBytes(wrapped)
        return try {
            cipher.doFinal(blob.ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultException("生物识别解包失败：密钥或数据被篡改", e)
        }
    }

    // 包裹 DEK -> 字节（AeadBlob：nonce12 + ciphertext+tag16）。
    // ponytail: 旧单段版保留——仅适用于无认证门禁的 key；per-op key 下 doFinal 会抛 UserNotAuthenticated，
    // 初始化主密码流程已不再调用（生物识别一律经设置页 BiometricPrompt 开启）。| 升级阈值：无，删除前确认无调用方。
    fun wrapDek(dek: ByteArray): ByteArray {
        ensureKey()
        val key = keyStore.getKey(keyAlias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv // Keystore 生成随机 IV
        val ct = cipher.doFinal(dek)
        return AeadBlob(nonce, ct).toBytes()
    }

    // 解包 wrapped_dek_biometric -> DEK。调用前系统已通过生物识别弹窗授权（Android 层触发 BiometricPrompt）。
    fun unwrapDek(wrapped: ByteArray): ByteArray {
        val blob = AeadBlob.fromBytes(wrapped)
        val key = keyStore.getKey(keyAlias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.nonce))
        return try {
            cipher.doFinal(blob.ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultException("生物识别解包失败：密钥或数据被篡改", e)
        }
    }

    // 删除 Keystore 中生物识别密钥（关闭生物识别时调用）。
    fun deleteKey() {
        if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
    }
}
