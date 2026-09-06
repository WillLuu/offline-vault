package vault

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

// ============================================================================
// CategoryDao：分类访问（契约 §3.2）。具体类，零抽象。
//  - listCategories 用单条 LEFT JOIN ... GROUP BY 取条目计数（防 N+1，契约 §7）。
//  - 非空分类禁止删除（存在未软删条目时返回 false）；"其他" 种子分类不可删。
// 副作用隔离：DB 读写在此；纯 SQL 构造在 SqlBuilders。
// ============================================================================

// "其他" 种子分类名（不可删，契约 §3.2）。
// ponytail: 以 name=="其他" 识别种子不可删分类；schema 无 is_seed 列。 | 升级阈值：若需按 id 识别，须在 categories 增加 is_seed 列。
private const val PROTECTED_CATEGORY_NAME = "其他"

// 种子分类（首次初始化时写入）。
private val SEED_CATEGORIES = listOf(
    "支付" to 0, "社交" to 1, "工作" to 2, "娱乐" to 3, "邮箱" to 4, "其他" to 5
)

class CategoryDao(private val db: SQLiteDatabase) {

    // 列表：单条 JOIN+GROUP BY 取每条分类的条目计数。
    fun listCategories(): List<CategoryRow> {
        val rows = mutableListOf<CategoryRow>()
        db.rawQuery(buildListCategoriesQuery(), null).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    CategoryRow(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        sortOrder = c.getInt(c.getColumnIndexOrThrow("sort_order")),
                        createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        entryCount = c.getInt(c.getColumnIndexOrThrow("entry_count"))
                    )
                )
            }
        }
        return rows
    }

    fun getCategory(id: Long): CategoryRow? {
        db.query(
            "categories",
            arrayOf("id", "name", "sort_order", "created_at"),
            "id = ?", arrayOf(id.toString()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return CategoryRow(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), 0)
        }
    }

    fun createCategory(name: String, sortOrder: Int): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("sort_order", sortOrder)
            put("created_at", nowMillis())
        }
        return db.insert("categories", null, cv)
    }

    fun updateCategory(id: Long, name: String? = null, sortOrder: Int? = null): Boolean {
        val cv = ContentValues()
        name?.let { cv.put("name", it) }
        sortOrder?.let { cv.put("sort_order", it) }
        if (cv.size() == 0) return false
        return db.update("categories", cv, "id = ?", arrayOf(id.toString())) > 0
    }

    // 批量重排：传入期望的 id 顺序，逐条写入 sort_order = 下标（事务内完成）。
    fun reorderCategories(orderedIds: List<Long>): Boolean {
        if (orderedIds.isEmpty()) return true
        db.beginTransaction()
        try {
            orderedIds.forEachIndexed { index, id ->
                val cv = ContentValues().apply { put("sort_order", index) }
                db.update("categories", cv, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    // 非空分类禁止删除：存在未软删条目时返回 false（客户端需先改派条目）。
    fun deleteCategory(id: Long): Boolean {
        if (isProtected(id)) return false           // "其他" 不可删
        if (countActiveEntries(id) > 0) return false  // 非空不可删
        return db.delete("categories", "id = ?", arrayOf(id.toString())) > 0
    }

    // 种子分类（首次初始化时写入；已存在则不重复）。
    fun seedDefaultsIfEmpty() {
        db.rawQuery("SELECT COUNT(*) FROM categories", null).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) return
        }
        db.beginTransaction()
        try {
            for ((name, order) in SEED_CATEGORIES) createCategory(name, order)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun isProtected(id: Long): Boolean {
        db.query("categories", arrayOf("name"), "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (!c.moveToFirst()) return false
            return c.getString(0) == PROTECTED_CATEGORY_NAME
        }
    }

    private fun countActiveEntries(id: Long): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM password_entries WHERE category_id = ? AND is_deleted = 0",
            arrayOf(id.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
}
