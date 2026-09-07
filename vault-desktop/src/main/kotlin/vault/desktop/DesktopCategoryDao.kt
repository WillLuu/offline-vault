package vault.desktop

import java.sql.Connection
import java.sql.PreparedStatement
import vault.CategoryRow
import vault.buildListCategoriesQuery
import vault.nowMillis

// ============================================================================
// DesktopCategoryDao：分类 JDBC 访问（镜像 vault-android CategoryDao 语义）。
//  - 读取 SQL 复用 vault-core buildListCategoriesQuery（单条 LEFT JOIN 防 N+1）。
//  - "其他" 种子不可删、非空分类不可删（契约 §3.2）；重排事务内逐条写 sort_order=下标。
// ============================================================================

private const val PROTECTED_CATEGORY_NAME = "其他"

private val SEED_CATEGORIES = listOf(
    "支付" to 0, "社交" to 1, "工作" to 2, "娱乐" to 3, "邮箱" to 4, "其他" to 5
)

class DesktopCategoryDao(private val conn: Connection) {

    fun listCategories(): List<CategoryRow> {
        val rows = mutableListOf<CategoryRow>()
        conn.prepareStatement(buildListCategoriesQuery()).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) rows.add(
                    CategoryRow(
                        id = rs.getLong("id"),
                        name = rs.getString("name"),
                        sortOrder = rs.getInt("sort_order"),
                        createdAt = rs.getLong("created_at"),
                        entryCount = rs.getInt("entry_count")
                    )
                )
            }
        }
        return rows
    }

    fun createCategory(name: String, sortOrder: Int): Long =
        conn.prepareStatement(
            "INSERT INTO categories (name, sort_order, created_at) VALUES (?, ?, ?)",
            PreparedStatement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setString(1, name); ps.setInt(2, sortOrder); ps.setLong(3, nowMillis())
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }

    fun updateCategory(id: Long, name: String? = null, sortOrder: Int? = null): Boolean {
        val sets = mutableListOf<String>(); val vals = mutableListOf<Any>()
        name?.let { sets += "name = ?"; vals += it }
        sortOrder?.let { sets += "sort_order = ?"; vals += it }
        if (sets.isEmpty()) return false
        return conn.prepareStatement("UPDATE categories SET ${sets.joinToString(", ")} WHERE id = ?").use { ps ->
            vals.forEachIndexed { i, v -> when (v) { is String -> ps.setString(i + 1, v); is Int -> ps.setInt(i + 1, v) } }
            ps.setLong(vals.size + 1, id)
            ps.executeUpdate() > 0
        }
    }

    /** 批量重排：sort_order = 下标，事务内完成（与 Android 一致）。 */
    fun reorderCategories(orderedIds: List<Long>): Boolean {
        if (orderedIds.isEmpty()) return true
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            orderedIds.forEachIndexed { index, id ->
                conn.prepareStatement("UPDATE categories SET sort_order = ? WHERE id = ?").use { ps ->
                    ps.setInt(1, index); ps.setLong(2, id); ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (e: Exception) { conn.rollback(); throw e } finally { conn.autoCommit = prev }
        return true
    }

    fun deleteCategory(id: Long): Boolean {
        if (isProtected(id)) return false
        if (countActiveEntries(id) > 0) return false
        return conn.prepareStatement("DELETE FROM categories WHERE id = ?").use { ps ->
            ps.setLong(1, id); ps.executeUpdate() > 0
        }
    }

    fun seedDefaultsIfEmpty() {
        val count = conn.prepareStatement("SELECT COUNT(*) FROM categories").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (count > 0) return
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            for ((name, order) in SEED_CATEGORIES) createCategory(name, order)
            conn.commit()
        } catch (e: Exception) { conn.rollback(); throw e } finally { conn.autoCommit = prev }
    }

    private fun isProtected(id: Long): Boolean =
        conn.prepareStatement("SELECT name FROM categories WHERE id = ?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next() && rs.getString(1) == PROTECTED_CATEGORY_NAME }
        }

    private fun countActiveEntries(id: Long): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM password_entries WHERE category_id = ? AND is_deleted = 0").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
}
