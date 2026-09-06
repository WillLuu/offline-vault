package vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

// ============================================================================
// VaultCrypto：加密原语具体类（契约 §3.5）。零抽象、无 interface/Factory。
// 设计纪律：
//  - AES-256-GCM 用 JDK/Android 标准库 javax.crypto（零新增依赖）。
//  - 密钥派生 Argon2id 用 Bouncy Castle 纯 Java 实现（#QA-024 方案 A，2026-09-02 拍板执行）：
//    原依赖 argon2-jvm(JNA) 的原生库无 android-arm/aarch64 ABI，真机必崩 UnsatisfiedLinkError。
//    BC 为纯 Java、零 native、全 ABI 通用；不注册 JCA Provider、直接调用 lightweight API，
//    规避 Android 内置旧版 BC 的包名冲突。契约 §4.2「Argon2id」不破。
//    换库前提已实证：与 argon2-jvm 向量对齐 6/6 用例输出逐字节一致
//    （toolchain/align_check/AlignCheck.java，v19 / 32 字节 / 含 Unicode 与边界参数），
//    既有 .vault 与已存 KDF 参数完全兼容。APK 代价 +约5MB。
//  - 每次加密均使用独立随机 12 字节 nonce，随密文返回，严禁复用（契约 §4.3）。
// ============================================================================

// AEAD 块：nonce(12) + ciphertext(含 GCM tag 16 于尾部)。GCM 自带认证，无需额外 HMAC。
data class AeadBlob(val nonce: ByteArray, val ciphertext: ByteArray) {
    fun toBytes(): ByteArray = nonce + ciphertext
    companion object {
        const val NONCE_LEN = 12
        const val TAG_LEN = 16
        fun fromBytes(b: ByteArray): AeadBlob {
            checkThat(b.size >= NONCE_LEN + TAG_LEN) { "AeadBlob 长度不足（需 ≥ ${NONCE_LEN + TAG_LEN}，实 ${b.size}）" }
            return AeadBlob(b.copyOfRange(0, NONCE_LEN), b.copyOfRange(NONCE_LEN, b.size))
        }
    }
}

// KDF 参数（契约 §4.2）。
data class KdfParams(val algo: String, val memoryKb: Int, val iterations: Int, val parallelism: Int)

// 契约固化参数（决策阶梯：固定平台折中参数，不暴露 KDF 调参 UI，YAGNI）。
val DEFAULT_KDF = KdfParams("argon2id", 32768, 2, 1)

// verifier 明文哨兵：KEK 解出的密文必须等于它才视为密码正确。
private val VERIFIER_PLAINTEXT = "vault-unlock-verifier-v1".toByteArray(Charsets.UTF_8)

private const val GCM_IV_LEN = 12
private const val GCM_TAG_BITS = 128
private val secureRandom = SecureRandom()

// 随机 16 字节盐（用于 KDF / 导出盐）。
fun randomSalt(): ByteArray = ByteArray(16).also { secureRandom.nextBytes(it) }

// 随机 32 字节 DEK（AES-256）。
fun randomDek(): ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }

// 显式清零（内存安全：lock() 清零 DEK，不落盘、不进日志）。
fun zeroBytes(b: ByteArray) {
    b.fill(0)
}

// 由主密码派生 KEK（32 字节）。Argon2id m=32MB / t=2 / p=1（2026-09-06 提速档位，仍高于 OWASP 移动端下限）。
fun deriveKey(password: String, salt: ByteArray, params: KdfParams): ByteArray {
    checkThat(params.algo == "argon2id") { "不支持的 KDF: ${params.algo}" }
    return rawArgon2id(password.toByteArray(Charsets.UTF_8), salt, params)
}

// Argon2id 原始 32 字节派生（Bouncy Castle 纯 Java，#QA-024 方案 A）。
// version 显式锁 v19(0x13)、输出固定 32 字节——与 argon2-jvm 时代产物字节兼容（向量对齐已证）。
private fun rawArgon2id(password: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
    // 不注册 JCA Provider、直接用 BC lightweight API：规避 Android 内置旧版 org.bouncycastle 包名冲突。
    val bcParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withSalt(salt)
        .withParallelism(params.parallelism)
        .withMemoryAsKB(params.memoryKb)
        .withIterations(params.iterations)
        .build()
    val gen = Argon2BytesGenerator()
    gen.init(bcParams)
    val out = ByteArray(32)
    gen.generateBytes(password, out, 0, out.size)
    return out
}

// AES-256-GCM 加密：独立随机 nonce，输出 AeadBlob（ciphertext 含尾部 16 字节 tag）。
fun encryptAesGcm(key: ByteArray, plaintext: ByteArray): AeadBlob {
    checkThat(key.size == 32) { "AES-256 要求 32 字节密钥，实 ${key.size}" }
    val nonce = ByteArray(GCM_IV_LEN).also { secureRandom.nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
    val ct = cipher.doFinal(plaintext)
    return AeadBlob(nonce, ct)
}

// AES-256-GCM 解密：GCM 认证失败（密钥错/数据损坏）= 抛 VaultException，绝不静默返回错误明文。
fun decryptAesGcm(key: ByteArray, blob: AeadBlob): ByteArray {
    checkThat(key.size == 32) { "AES-256 要求 32 字节密钥，实 ${key.size}" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, blob.nonce))
    return try {
        cipher.doFinal(blob.ciphertext)
    } catch (e: javax.crypto.AEADBadTagException) {
        throw VaultException("GCM 认证失败：密钥错误或数据被篡改", e)
    }
}

fun wrapKey(kek: ByteArray, dek: ByteArray): AeadBlob = encryptAesGcm(kek, dek)
fun unwrapKey(kek: ByteArray, blob: AeadBlob): ByteArray = decryptAesGcm(kek, blob)

fun makeVerifier(kek: ByteArray): AeadBlob = encryptAesGcm(kek, VERIFIER_PLAINTEXT)
fun checkVerifier(kek: ByteArray, blob: AeadBlob): Boolean {
    return try {
        decryptAesGcm(kek, blob).contentEquals(VERIFIER_PLAINTEXT)
    } catch (e: VaultException) {
        false
    }
}

// 自检即文档：加密原语不变量（静默、无网络/DB/文件依赖）。
fun vaultCryptoSelfTest() {
    val kek = deriveKey("master-pass", randomSalt(), DEFAULT_KDF)
    checkThat(kek.size == 32) { "KEK 应为 32 字节" }

    // 派生确定性：相同密码+盐 => 相同密钥
    val salt = randomSalt()
    assertBytesEq(deriveKey("pw", salt, DEFAULT_KDF), deriveKey("pw", salt, DEFAULT_KDF), "Argon2id 应确定性")
    // 不同盐 => 不同密钥
    checkThat(!deriveKey("pw", salt, DEFAULT_KDF).contentEquals(deriveKey("pw", randomSalt(), DEFAULT_KDF))) { "不同盐应派生不同密钥" }
    // 不同密码 => 不同密钥
    checkThat(!deriveKey("pw", salt, DEFAULT_KDF).contentEquals(deriveKey("other", salt, DEFAULT_KDF))) { "不同密码应派生不同密钥" }

    // AES-GCM 往返
    val pt = "secret-data-123".toByteArray()
    val blob = encryptAesGcm(kek, pt)
    assertBytesEq(decryptAesGcm(kek, blob), pt, "AES-GCM 往返失败")
    checkThat(blob.ciphertext.size == pt.size + AeadBlob.TAG_LEN) { "ciphertext 应 = 明文 + 16 字节 tag" }

    // nonce 不复用：两次加密 nonce 不同（随机 12 字节碰撞概率可忽略）
    val b2 = encryptAesGcm(kek, pt)
    checkThat(!blob.nonce.contentEquals(b2.nonce)) { "两次加密应使用不同 nonce" }

    // wrap/unwrap
    val dek = randomDek()
    val wrapped = wrapKey(kek, dek)
    assertBytesEq(unwrapKey(kek, wrapped), dek, "wrap/unwrap 失败")

    // verifier
    val ver = makeVerifier(kek)
    checkThat(checkVerifier(kek, ver)) { "正确 KEK 应通过 verifier" }
    val wrongKek = deriveKey("wrong", randomSalt(), DEFAULT_KDF)
    checkThat(!checkVerifier(wrongKek, ver)) { "错误 KEK 应不通过 verifier" }

    // 篡改密文 => 解密抛错（防静默失败）
    val tampered = AeadBlob(blob.nonce.copyOf(), blob.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })
    var threw = false
    try { decryptAesGcm(kek, tampered) } catch (e: VaultException) { threw = true }
    checkThat(threw) { "篡改密文后解密必须抛错，而非静默返回错误明文" }
}
