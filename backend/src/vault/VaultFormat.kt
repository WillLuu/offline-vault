package vault

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ============================================================================
// .vault 导出文件格式（契约 §4.4）。纯字节序列化，无 Android 依赖，可在 JVM 测试。
//   magic     : 4 字节  "VLT1"
//   version   : 1 字节  0x01
//   kdf_algo  : 1 字节  1=argon2id
//   kdf_params: mem(4B BE) | iter(4B BE) | par(1B) | saltLen(2B BE) | salt(var)
//   nonce     : 12 字节  (导出密钥的 GCM nonce)
//   ciphertext: 变长     AES-256-GCM(导出密钥, 荷载) ；GCM tag(16B) 附于末尾
// 加密失败(GCM 认证不通过) = 密码错误/文件损坏 -> 导入返回 WRONG_PASSWORD。
// 不额外加 HMAC（GCM 已提供认证，冗余 YAGNI，契约 §4.4）。
// ============================================================================

const val VAULT_MAGIC = "VLT1"
private const val VAULT_VERSION: Byte = 0x01
private const val KDF_ALGO_ARGON2ID: Byte = 0x01

data class VaultFileHeader(
    val kdfParams: KdfParams,
    val exportSalt: ByteArray,
    val blob: AeadBlob
)

// 序列化（export_salt 即文件内 kdf_params 的 salt 字段；与登录 KEK 解耦，文件自包含）。
fun serializeVaultFile(kdfParams: KdfParams, exportSalt: ByteArray, blob: AeadBlob): ByteArray {
    checkThat(exportSalt.size in 8..65535) { "export_salt 长度越界" }
    val magic = VAULT_MAGIC.toByteArray(Charsets.US_ASCII)
    val paramsSize = 4 + 4 + 1 + 2 + exportSalt.size
    val buf = ByteBuffer.allocate(
        magic.size + 1 + 1 + paramsSize + blob.nonce.size + blob.ciphertext.size
    ).order(ByteOrder.BIG_ENDIAN)
    buf.put(magic)
    buf.put(VAULT_VERSION)
    buf.put(KDF_ALGO_ARGON2ID)
    buf.putInt(kdfParams.memoryKb)
    buf.putInt(kdfParams.iterations)
    buf.put(kdfParams.parallelism.toByte())
    buf.putShort(exportSalt.size.toShort())
    buf.put(exportSalt)
    buf.put(blob.nonce)
    buf.put(blob.ciphertext)
    return buf.array()
}

// 解析文件头 + 密文块。格式错误必须显式抛 VaultException（防静默失败底线，契约 §7）。
// 长度字段统一前置校验（#QA-004）：每个变长/定长字段读取前先 requireBytes 确认剩余字节足够，
// 越界（超长/截断；saltLen 读 short 经 &0xFFFF 后恒非负，负值在 requireBytes 的 n<0 分支防御）即抛
// WrongPasswordException——.vault 结构损坏 = 文件损坏，导入语义即 WRONG_PASSWORD（契约 §4.4）。
// 逐字段精确校验，禁止整段 try/catch 转抛掩盖（保持可读、可定位到具体字段）。
fun parseVaultHeader(bytes: ByteArray): VaultFileHeader {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    requireBytes(buf, 4)
    val magic = ByteArray(4)
    buf.get(magic)
    if (!magic.contentEquals(VAULT_MAGIC.toByteArray(Charsets.US_ASCII))) {
        throw VaultException("magic 不匹配，非 .vault 文件")
    }
    requireBytes(buf, 1)
    val version = buf.get()
    if (version != VAULT_VERSION) throw VaultException("不支持的 .vault 版本: $version")
    requireBytes(buf, 1)
    val algo = buf.get()
    if (algo != KDF_ALGO_ARGON2ID) throw VaultException("不支持的 kdf_algo: $algo")
    requireBytes(buf, 8)
    val memoryKb = buf.getInt()
    val iterations = buf.getInt()
    requireBytes(buf, 1)
    val parallelism = buf.get().toInt() and 0xFF
    requireBytes(buf, 2)
    val saltLen = buf.getShort().toInt() and 0xFFFF
    // 盐长度下限与 serializeVaultFile 对称（导出盐 8..65535）；65535 上限由 requireBytes 剩余字节兜底。
    if (saltLen < MIN_SALT_LEN) throw WrongPasswordException()
    requireBytes(buf, saltLen)
    val salt = ByteArray(saltLen)
    buf.get(salt)
    requireBytes(buf, AeadBlob.NONCE_LEN)
    val nonce = ByteArray(AeadBlob.NONCE_LEN)
    buf.get(nonce)
    val ctLen = bytes.size - buf.position() // 恒非负（bytes.size >= position），故无负值分支
    if (ctLen < AeadBlob.TAG_LEN) throw WrongPasswordException() // 密文块过短（结构损坏）
    val ciphertext = ByteArray(ctLen)
    buf.get(ciphertext)
    return VaultFileHeader(KdfParams("argon2id", memoryKb, iterations, parallelism), salt, AeadBlob(nonce, ciphertext))
}

// 统一前置校验：读取 n 字节前先确认剩余足够；n<0（防御）或不足 => .vault 结构损坏 => WRONG_PASSWORD（契约 §4.4）。
private fun requireBytes(buf: ByteBuffer, n: Int) {
    if (n < 0 || buf.remaining() < n) throw WrongPasswordException()
}

// 导出盐长度下限（与 serializeVaultFile 的 checkThat(exportSalt.size in 8..65535) 对称）。
private const val MIN_SALT_LEN = 8

// 自检：序列化/解析往返 + magic 校验。
fun vaultFormatSelfTest() {
    val salt = randomSalt()
    val key = deriveKey("export-pw", salt, DEFAULT_KDF)
    val blob = encryptAesGcm(key, "payload-bytes".toByteArray())
    val file = serializeVaultFile(DEFAULT_KDF, salt, blob)
    val header = parseVaultHeader(file)
    assertBytesEq(header.exportSalt, salt, ".vault salt 往返失败")
    assertBytesEq(decryptAesGcm(key, header.blob), "payload-bytes".toByteArray(), ".vault 荷载解密失败")

    // 错误 magic 必须抛错
    val bad = file.copyOf().also { it[0] = 'X'.code.toByte() }
    var threw = false
    try { parseVaultHeader(bad) } catch (e: VaultException) { threw = true }
    checkThat(threw) { "magic 错误必须抛错" }

    // #QA-004：畸形长度字段必须抛 VaultException（WRONG_PASSWORD 语义），而非 BufferUnderflowException。
    // 篡改 saltLen=65535（saltLen 位于偏移 15-16：magic4+version1+algo1+mem4+iter4+par1）。
    val saltTampered = file.copyOf()
    saltTampered[15] = 0xFF.toByte()
    saltTampered[16] = 0xFF.toByte()
    var threwSalt = false
    try { parseVaultHeader(saltTampered) } catch (e: VaultException) { threwSalt = true }
    checkThat(threwSalt) { "saltLen=65535 越界必须抛 VaultException 而非 BufferUnderflowException" }

    // 截断密文块使 ctLen < TAG_LEN（结构损坏）。
    val truncated = file.copyOfRange(0, file.size - AeadBlob.TAG_LEN)
    var threwTrunc = false
    try { parseVaultHeader(truncated) } catch (e: VaultException) { threwTrunc = true }
    checkThat(threwTrunc) { "密文块过短必须抛 VaultException" }
}
