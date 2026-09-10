package vault

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

// ============================================================================
// PasswordEntryDao：密码条目访问（契约 §3.1）。具体类，零抽象。v2 全加密：
//  - 条目 name/username 与 secret 一并加密进 secret_blob（EntryBlob v2 JSON）；明文列恒为空串。
//  - listEntries：按结构（is_deleted/category_id）取行 → 解密 → 内存搜索/排序/分页（Query.filterAndSortEntries）。
//    返回掩码行（password/website/notes 置 null）。锁定态 getDek()==null → LockedException。
//  副作用隔离：DB 读写在此；纯 SQL 在 SqlBuilders；纯编解码/检索在 EntryBlob/Query。
// ============================================================================

class PasswordEntryDao(
    private val db: SQLiteDatabase,
    private val getDek: () -> ByteArray?   // 由 UnlockManager 提供活跃 DEK；null = 锁定
) {
    fun listEntries(
        search: String? = null,
        sortBy: SortKey = SortKey.NAME_ASC,
        categoryId: Long? = null,
        limit: Int = LIST_NO_LIMIT,
        offset: Int = 0
    ): List<PasswordEntryRow> {
        val dek = requireUnlocked()
        val (sql, args) = buildListEntriesQuery(categoryId)
        val order = loadCategoryOrder(dek)
        val all = mutableListOf<PasswordEntryRow>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) all.add(decryptRow(c, dek, catName(order, c)))
        }
        // 掩码：列表不回填 password/website/notes/extras。
        return filterAndSortEntries(all, search, sortBy, categoryId, order, limit, offset)
            .map { it.copy(password = null, website = null, notes = null, extras = emptyList()) }
    }

    fun getEntry(id: Long): PasswordEntryRow? {
        val dek = requireUnlocked()
        db.rawQuery(buildGetEntryQuery(), arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            val catId = colLong(c, "category_id")
            val catName = catId?.let { categoryNameById(dek, it) }
            return decryptRow(c, dek, catName)
        }
    }

    fun createEntry(input: EntryInput): Long {
        val dek = requireUnlocked()
        val cv = ContentValues().apply {
            put("name", "")
            put("username", "")
            put("secret_blob", encryptAesGcm(dek, encodeEntryBlob(input.name, input.username, SecretPlain(input.password, input.website, input.notes, input.extras))).toBytes())
            putCategoryId(input.categoryId)
            val now = nowMillis()
            put("created_at", now); put("updated_at", now); put("is_deleted", 0)
        }
        return db.insert("password_entries", null, cv)
    }

    fun updateEntry(id: Long, input: EntryInput): Boolean {
        val dek = requireUnlocked()
        val cv = ContentValues().apply {
            put("name", "")
            put("username", "")
            put("secret_blob", encryptAesGcm(dek, encodeEntryBlob(input.name, input.username, SecretPlain(input.password, input.website, input.notes, input.extras))).toBytes())
            putCategoryId(input.categoryId)
            put("updated_at", nowMillis())
        }
        return db.update("password_entries", cv, "id = ? AND is_deleted = 0", arrayOf(id.toString())) > 0
    }

    fun deleteEntry(id: Long): Boolean {
        val cv = ContentValues().apply { put("is_deleted", 1); put("updated_at", nowMillis()) }
        return db.update("password_entries", cv, "id = ? AND is_deleted = 0", arrayOf(id.toString())) > 0
    }

    // ---- 内部 ----
    private fun requireUnlocked(): ByteArray = getDek() ?: throw LockedException()

    private fun ContentValues.putCategoryId(categoryId: Long?) {
        if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
    }

    // 解密 blob → name/username（v1 blob 无 n/u 时回退明文列）+ secret。
    private fun decryptRow(c: Cursor, dek: ByteArray, catName: String?): PasswordEntryRow {
        val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(c.getBlob(c.getColumnIndexOrThrow("secret_blob")))))
        val colName = c.getString(c.getColumnIndexOrThrow("name")) ?: ""
        val colUser = c.getString(c.getColumnIndexOrThrow("username")) ?: ""
        return PasswordEntryRow(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            name = view.name.ifEmpty { colName },
            username = view.username.ifEmpty { colUser },
            password = view.secret.password, website = view.secret.website, notes = view.secret.notes,
            categoryId = colLong(c, "category_id"),
            categoryName = catName,
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at")),
            isDeleted = false,
            extras = view.secret.extras
        )
    }

    private fun colLong(c: Cursor, col: String): Long? {
        val i = c.getColumnIndex(col)
        return if (i >= 0 && !c.isNull(i)) c.getLong(i) else null
    }

    private fun catName(order: Map<Long, Pair<Int, String>>, c: Cursor): String? =
        colLong(c, "category_id")?.let { order[it]?.second }

    // 载入分类顺序映射（id -> (sort_order, 解密名)），供 CATEGORY 排序与 categoryName 回填。
    private fun loadCategoryOrder(dek: ByteArray): Map<Long, Pair<Int, String>> {
        val m = HashMap<Long, Pair<Int, String>>()
        db.rawQuery("SELECT id, name, name_blob, sort_order FROM categories", null).use { c ->
            while (c.moveToNext()) {
                val idxBlob = c.getColumnIndex("name_blob")
                val name = if (idxBlob >= 0 && !c.isNull(idxBlob))
                    decodeCategoryNameBlob(decryptAesGcm(dek, AeadBlob.fromBytes(c.getBlob(idxBlob))))
                else c.getString(c.getColumnIndexOrThrow("name")) ?: ""
                m[c.getLong(c.getColumnIndexOrThrow("id"))] = c.getInt(c.getColumnIndexOrThrow("sort_order")) to name
            }
        }
        return m
    }

    private fun categoryNameById(dek: ByteArray, id: Long): String? =
        loadCategoryOrder(dek)[id]?.second
}
