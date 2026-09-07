package vault

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

// ============================================================================
// PasswordEntryDao：密码条目访问（契约 §3.1）。具体类，零抽象。
//  - 所有方法调用前必须由 UnlockManager 解锁（DEK 在内存）；锁定态 getDek() 返回 null -> 拒绝。
//  - listEntries 返回掩码行（password/website/notes 为 null），仅 getEntry 解密回填。
//  - secret_blob 明文 = JSON {password, website, notes}，整体 AES-256-GCM 加密（契约 §4.3）。
// 副作用隔离：本类负责 DB 读写（副作用）；纯 SQL 构造在 SqlBuilders，纯加密在 VaultCrypto。
// ============================================================================

class PasswordEntryDao(
    private val db: SQLiteDatabase,
    private val getDek: () -> ByteArray?   // 由 UnlockManager 提供活跃 DEK；null = 锁定
) {
    // 列表：搜索 + 排序 + 分类过滤 + 分页（掩码）。
    fun listEntries(
        search: String? = null,
        sortBy: SortKey = SortKey.NAME_ASC,
        categoryId: Long? = null,
        limit: Int = LIST_NO_LIMIT,   // 默认全量（审查 2.1 方案 A，见 models.kt 常量注释）
        offset: Int = 0
    ): List<PasswordEntryRow> {
        requireUnlocked()
        val (sql, args) = buildListEntriesQuery(search, sortBy, categoryId, limit, offset)
        val rows = mutableListOf<PasswordEntryRow>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) rows.add(rowFromCursor(c, masked = true))
        }
        return rows
    }

    // 详情：解密 secret_blob，回填 password/website/notes；JOIN categories 回填 category_name
    //（修复：详情页分类不显示——原先只查 category_id，category_name 恒为 null）。
    fun getEntry(id: Long): PasswordEntryRow? {
        requireUnlocked()
        db.rawQuery(
            """
            SELECT e.id, e.name, e.username, e.secret_blob, e.category_id,
                   e.created_at, e.updated_at, e.is_deleted, c.name AS category_name
            FROM password_entries e
            LEFT JOIN categories c ON e.category_id = c.id
            WHERE e.id = ? AND e.is_deleted = 0
            """.trimIndent(),
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val secret = decryptSecret(c.getBlob(c.getColumnIndexOrThrow("secret_blob")))
            return rowFromCursor(c, masked = false, secret = secret)
        }
    }

    // 新增：加密 {password, website, notes} -> secret_blob。
    fun createEntry(input: EntryInput): Long {
        val dek = requireUnlocked()
        val cv = ContentValues().apply {
            put("name", input.name)
            put("username", input.username)
            put("secret_blob", encryptAesGcm(dek, secretJson(input.password, input.website, input.notes, input.extras)).toBytes())
            putCategoryId(input.categoryId)
            val now = nowMillis()
            put("created_at", now)
            put("updated_at", now)
            put("is_deleted", 0)
        }
        return db.insert("password_entries", null, cv)
    }

    // 修改：重新加密 secret_blob，刷新 updated_at。
    fun updateEntry(id: Long, input: EntryInput): Boolean {
        val dek = requireUnlocked()
        val cv = ContentValues().apply {
            put("name", input.name)
            put("username", input.username)
            put("secret_blob", encryptAesGcm(dek, secretJson(input.password, input.website, input.notes, input.extras)).toBytes())
            putCategoryId(input.categoryId)
            put("updated_at", nowMillis())
        }
        return db.update("password_entries", cv, "id = ? AND is_deleted = 0", arrayOf(id.toString())) > 0
    }

    // 软删：is_deleted=1，刷新 updated_at（不物理删除）。
    fun deleteEntry(id: Long): Boolean {
        val cv = ContentValues().apply {
            put("is_deleted", 1)
            put("updated_at", nowMillis())
        }
        return db.update("password_entries", cv, "id = ? AND is_deleted = 0", arrayOf(id.toString())) > 0
    }

    // ---- 内部辅助 ----
    private fun requireUnlocked(): ByteArray {
        val dek = getDek() ?: throw LockedException()
        return dek
    }

    private fun ContentValues.putCategoryId(categoryId: Long?) {
        if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
    }

    // extras 仅在非空时写入 "extras" 键（2026-09-06 自定义词条；旧记录 JSON 无该键，读端向后兼容）。
    private fun secretJson(password: String, website: String, notes: String, extras: List<ExtraField>): ByteArray {
        val m = linkedMapOf<String, Any?>("password" to password, "website" to website, "notes" to notes)
        if (extras.isNotEmpty()) {
            m["extras"] = extras.map { mapOf("l" to it.label, "v" to it.value) }
        }
        return jsonEncode(m).toByteArray(Charsets.UTF_8)
    }

    private fun decryptSecret(blob: ByteArray): SecretPlain {
        val json = String(decryptAesGcm(getDek()!!, AeadBlob.fromBytes(blob)), Charsets.UTF_8)
        val m = jsonDecode(json) as Map<*, *>
        val extras = (m["extras"] as? List<*>)?.mapNotNull { e ->
            val em = e as? Map<*, *> ?: return@mapNotNull null
            val l = em["l"] as? String ?: return@mapNotNull null
            ExtraField(l, (em["v"] as? String) ?: "")
        } ?: emptyList()
        return SecretPlain(
            m["password"] as String,
            m["website"] as String,
            m["notes"] as String,
            extras
        )
    }

    private fun rowFromCursor(c: android.database.Cursor, masked: Boolean, secret: SecretPlain? = null): PasswordEntryRow {
        val idxCatName = c.getColumnIndex("category_name")
        val categoryName = if (idxCatName >= 0 && !c.isNull(idxCatName)) c.getString(idxCatName) else null
        val idxCatId = c.getColumnIndexOrThrow("category_id")
        val categoryId = if (c.isNull(idxCatId)) null else c.getLong(idxCatId)
        return PasswordEntryRow(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            username = c.getString(c.getColumnIndexOrThrow("username")),
            password = if (masked) null else secret?.password,
            website = if (masked) null else secret?.website,
            notes = if (masked) null else secret?.notes,
            categoryId = categoryId,
            categoryName = categoryName,
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            isDeleted = c.getInt(c.getColumnIndexOrThrow("is_deleted")) == 1,
            extras = if (masked) emptyList() else secret?.extras ?: emptyList()
        )
    }
}
