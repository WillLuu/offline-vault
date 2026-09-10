package vault

// ============================================================================
// EntryBlob.kt：v2 全加密的库内密文载荷编解码（纯函数，零 Android 依赖，JVM 可测）。
//
// 条目 secret_blob（v2）= AES-GCM( DEK, JSON{ n, u, p, w, t, x? } )
//   n=name  u=username  p=password  w=website  t=notes  x=extras(仅非空时写)
// 分类 name_blob（v2）  = AES-GCM( DEK, JSON{ n } )
//
// 向后兼容：v1 条目 secret_blob 的 JSON 为 { password, website, notes, extras? }，无 n/u。
//   decodeEntryBlob 据"是否含 n 键"区分 v1/v2；v1 解出的 name/username 为空串，
//   由读侧回退到（迁移前尚存的）明文列，故迁移进行到一半也能正确读取。
// ============================================================================

// 解码后的条目视图：明文元数据 + secret。
data class EntryBlobView(val name: String, val username: String, val secret: SecretPlain)

// 编码 v2 条目 blob（始终写全字段，含 name/username）。
fun encodeEntryBlob(name: String, username: String, secret: SecretPlain): ByteArray {
    val m = linkedMapOf<String, Any?>(
        "n" to name,
        "u" to username,
        "p" to secret.password,
        "w" to secret.website,
        "t" to secret.notes
    )
    if (secret.extras.isNotEmpty()) {
        m["x"] = secret.extras.map { mapOf("l" to it.label, "v" to it.value) }
    }
    return jsonEncode(m).toByteArray(Charsets.UTF_8)
}

// 解码条目 blob（自动识别 v1/v2）。
fun decodeEntryBlob(bytes: ByteArray): EntryBlobView {
    val m = jsonDecode(String(bytes, Charsets.UTF_8)) as? Map<*, *>
        ?: throw VaultException("secret_blob 非法：非 JSON 对象")
    return if (m.containsKey("n")) {
        // v2
        EntryBlobView(
            name = m["n"] as? String ?: "",
            username = m["u"] as? String ?: "",
            secret = SecretPlain(
                password = m["p"] as? String ?: "",
                website = m["w"] as? String ?: "",
                notes = m["t"] as? String ?: "",
                extras = parseExtrasFromBlob(m["x"])
            )
        )
    } else {
        // v1：name/username 不在 blob 内（回退明文列由调用方处理）
        EntryBlobView(
            name = "",
            username = "",
            secret = SecretPlain(
                password = m["password"] as? String ?: "",
                website = m["website"] as? String ?: "",
                notes = m["notes"] as? String ?: "",
                extras = parseExtrasFromBlob(m["extras"])
            )
        )
    }
}

// 分类名 blob（v2）。
fun encodeCategoryNameBlob(name: String): ByteArray =
    jsonEncode(linkedMapOf<String, Any?>("n" to name)).toByteArray(Charsets.UTF_8)

fun decodeCategoryNameBlob(bytes: ByteArray): String {
    val m = jsonDecode(String(bytes, Charsets.UTF_8)) as? Map<*, *>
        ?: throw VaultException("name_blob 非法：非 JSON 对象")
    return m["n"] as? String ?: ""
}

// extras 解析（v2 键 "x" / v1 键 "extras" 共用；缺失/畸形回退空列表）。
private fun parseExtrasFromBlob(raw: Any?): List<ExtraField> =
    (raw as? List<*>)?.mapNotNull { e ->
        val em = e as? Map<*, *> ?: return@mapNotNull null
        val l = em["l"] as? String ?: return@mapNotNull null
        ExtraField(l, (em["v"] as? String) ?: "")
    } ?: emptyList()

// 自检：v2 往返、v1 兼容解码、extras 往返。
fun entryBlobSelfTest() {
    // v2 往返（含 extras）
    val secret = SecretPlain("p@ss\"w", "https://x", "note\n1", listOf(ExtraField("邮箱", "a@b.c"), ExtraField("手机", "138")))
    val enc = encodeEntryBlob("GitHub", "me@x.com", secret)
    val dec = decodeEntryBlob(enc)
    checkThat(dec.name == "GitHub" && dec.username == "me@x.com") { "v2 name/username 往返失败" }
    checkThat(dec.secret == secret) { "v2 secret 往返失败：${dec.secret} vs $secret" }

    // v2 无 extras：不应写 x 键，往返仍等价（extras 空）
    val enc2 = encodeEntryBlob("n", "u", SecretPlain("p", "w", "t"))
    val dec2 = decodeEntryBlob(enc2)
    checkThat(dec2.secret.extras.isEmpty() && dec2.name == "n") { "v2 空 extras 往返失败" }

    // v1 兼容：旧 JSON（无 n）应解出空 name/username + 正确 secret
    val v1Json = """{"password":"pp","website":"ww","notes":"nn","extras":[{"l":"备注","v":"vv"}]}"""
    val v1dec = decodeEntryBlob(v1Json.toByteArray(Charsets.UTF_8))
    checkThat(v1dec.name == "" && v1dec.username == "") { "v1 解码 name 应为空" }
    checkThat(v1dec.secret.password == "pp" && v1dec.secret.notes == "nn") { "v1 secret 解码失败" }
    checkThat(v1dec.secret.extras == listOf(ExtraField("备注", "vv"))) { "v1 extras 解码失败" }

    // 分类名 blob 往返
    checkThat(decodeCategoryNameBlob(encodeCategoryNameBlob("支付")) == "支付") { "分类名 blob 往返失败" }
}
