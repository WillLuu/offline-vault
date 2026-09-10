package vault

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

// ============================================================================
// CategoryDao：分类访问（契约 §3.2）。具体类，零抽象。v2 全加密：
//  - 分类真名存于 name_blob（AES-GCM(JSON{name})），明文 name 列恒为空串。
//  - 读侧解密 name_blob（迁移期回退明文列）。写侧需 DEK（getDek）。
//  - v2 移除 uq_categories_name 唯一索引，唯一性由本类 createCategory/updateCategory 代码层校验。
//  - 非空分类禁止删除（存在未软删条目时返回 false）；"其他" 种子分类不可删。
// 副作用隔离：DB 读写在此；纯 SQL 构造在 SqlBuilders；纯编解码在 EntryBlob。
// ============================================================================

private const val PROTECTED_CATEGORY_NAME = "其他"

private val SEED_CATEGORIES = listOf(
    "支付" to 0, "社交" to 1, "工作" to 2, "娱乐" to 3, "邮箱" to 4, "其他" to 5
)

class CategoryDao(
    private val db: SQLiteDatabase,
    private val getDek: () -> ByteArray?   // 解锁态 DEK；null=锁定
) {
    private fun requireDek(): ByteArray = getDek() ?: throw LockedException()

    fun listCategories(): List<CategoryRow> {
        requireDek()
        val rows = mutableListOf<CategoryRow>()
        db.rawQuery(buildListCategoriesQuery(), null).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    CategoryRow(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        name = decodeName(c),
                        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        entryCount = c.getInt(c.getColumnIndexOrThrow("entry_count"))
                    )
                )
            }
        }
        // v2：name 已加密，SQL 只能按 sort_order 排；同 sort_order 的次序用解密名在内存兜底
        // （对齐 v1 `ORDER BY c.sort_order, c.name`）。
        return rows.sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }

    fun getCategory(id: Long): CategoryRow? {
        val dek = requireDek()
        db.rawQuery("SELECT id, name, name_blob, sort_order, created_at, 0 AS entry_count FROM categories WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return CategoryRow(c.getLong(0), decodeName(c), c.getInt(3), c.getLong(4), 0)
        }
    }

    // 唯一性代码层校验：同名（未软删）已存在则返回 -1（前端据此提示）。
    fun createCategory(name: String, sortOrder: Int): Long {
        val dek = requireDek()
        if (findByName(name) != null) return -1L
        val cv = ContentValues().apply {
            put("name", "")
            put("name_blob", encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes())
            put("sort_order", sortOrder)
            put("created_at", nowMillis())
        }
        return db.insert("categories", null, cv)
    }

    fun updateCategory(id: Long, name: String? = null, sortOrder: Int? = null): Boolean {
        val dek = requireDek()
        val cv = ContentValues()
        if (name != null) {
            val existing = findByName(name)
            if (existing != null && existing != id) return false // 同名冲突（非自身）
            cv.put("name", "")
            cv.put("name_blob", encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes())
        }
        sortOrder?.let { cv.put("sort_order", it) }
        if (cv.size() == 0) return false
        return db.update("categories", cv, "id = ?", arrayOf(id.toString())) > 0
    }

    fun reorderCategories(orderedIds: List<Long>): Boolean {
        if (orderedIds.isEmpty()) return true
        requireDek()
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { index, id ->
                val cv = ContentValues().apply { put("sort_order", index) }
                db.update("categories", cv, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }

    fun deleteCategory(id: Long): Boolean {
        requireDek()
        if (isProtected(id)) return false
        if (countActiveEntries(id) > 0) return false
        return db.delete("categories", "id = ?", arrayOf(id.toString())) > 0
    }

    fun seedDefaultsIfEmpty() {
        val dek = requireDek()
        db.rawQuery("SELECT COUNT(*) FROM categories", null).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) return
        }
        db.beginTransaction()
        try {
            for ((name, order) in SEED_CATEGORIES) {
                val cv = ContentValues().apply {
                    put("name", "")
                    put("name_blob", encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes())
                    put("sort_order", order)
                    put("created_at", nowMillis())
                }
                db.insert("categories", null, cv)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // 按解密名查 id（代码层唯一性 + "其他" 保护用）。
    private fun findByName(name: String): Long? {
        val dek = getDek() ?: return null
        db.rawQuery("SELECT id, name, name_blob FROM categories", null).use { c ->
            while (c.moveToNext()) if (decodeName(c) == name) return c.getLong(c.getColumnIndexOrThrow("id"))
        }
        return null
    }

    private fun decodeName(c: android.database.Cursor): String {
        val idxBlob = c.getColumnIndex("name_blob")
        if (idxBlob >= 0 && !c.isNull(idxBlob)) {
            return decodeCategoryNameBlob(decryptAesGcm(getDek()!!, AeadBlob.fromBytes(c.getBlob(idxBlob))))
        }
        // 迁移期回退明文列
        val idxName = c.getColumnIndex("name")
        return if (idxName >= 0) c.getString(idxName) ?: "" else ""
    }

    private fun isProtected(id: Long): Boolean = getCategory(id)?.name == PROTECTED_CATEGORY_NAME

    private fun countActiveEntries(id: Long): Int {
        db.rawQuery("SELECT COUNT(*) FROM password_entries WHERE category_id = ? AND is_deleted = 0", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
