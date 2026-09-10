package vault.desktop

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import vault.AeadBlob
import vault.CategoryRow
import vault.LockedException
import vault.buildListCategoriesQuery
import vault.decryptAesGcm
import vault.decodeCategoryNameBlob
import vault.encryptAesGcm
import vault.encodeCategoryNameBlob
import vault.nowMillis

// ============================================================================
// DesktopCategoryDao：分类 JDBC 访问（镜像 vault-android CategoryDao v2 语义）。
//  - 分类真名存于 name_blob（AES-GCM），明文 name 列恒空；读侧解密 blob（迁移期回退明文列）。
//  - v2 无 uq_categories_name 唯一索引，唯一性代码层校验。
//  - "其他" 种子不可删、非空分类不可删；重排事务内写 sort_order=下标。
// ============================================================================

private const val PROTECTED_CATEGORY_NAME = "其他"
private val SEED_CATEGORIES = listOf("支付" to 0, "社交" to 1, "工作" to 2, "娱乐" to 3, "邮箱" to 4, "其他" to 5)

class DesktopCategoryDao(
    private val conn: Connection,
    private val getDek: () -> ByteArray?
) {
    private fun requireDek(): ByteArray = getDek() ?: throw LockedException()

    fun listCategories(): List<CategoryRow> {
        requireDek()
        val rows = mutableListOf<CategoryRow>()
        conn.prepareStatement(buildListCategoriesQuery()).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) rows.add(
                    CategoryRow(rs.getLong("id"), decodeName(rs), rs.getInt("sort_order"), rs.getLong("created_at"), rs.getInt("entry_count"))
                )
            }
        }
        // v2：name 已加密，SQL 仅按 sort_order；同序用解密名内存兜底（对齐 v1 ORDER BY sort_order, name）。
        return rows.sortedWith(compareBy({ it.sortOrder }, { it.name }))
    }

    fun createCategory(name: String, sortOrder: Int): Long {
        val dek = requireDek()
        if (findByName(name) != null) return -1L
        return conn.prepareStatement(
            "INSERT INTO categories (name, name_blob, sort_order, created_at) VALUES ('', ?, ?, ?)",
            PreparedStatement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setBytes(1, encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes())
            ps.setInt(2, sortOrder); ps.setLong(3, nowMillis())
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }
    }

    fun updateCategory(id: Long, name: String? = null, sortOrder: Int? = null): Boolean {
        val dek = requireDek()
        if (name != null) {
            val existing = findByName(name)
            if (existing != null && existing != id) return false
        }
        val sets = mutableListOf<String>(); val bind = mutableListOf<Pair<Int, Any?>>()
        var idx = 1
        if (name != null) {
            sets += "name = ''"; sets += "name_blob = ?"; bind += idx to encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes(); idx++
        }
        sortOrder?.let { sets += "sort_order = ?"; bind += idx to it; idx++ }
        if (sets.isEmpty()) return false
        return conn.prepareStatement("UPDATE categories SET ${sets.joinToString(", ")} WHERE id = ?").use { ps ->
            bind.forEach { (i, v) -> when (v) { is ByteArray -> ps.setBytes(i, v); is Int -> ps.setInt(i, v) } }
            ps.setLong(idx, id)
            ps.executeUpdate() > 0
        }
    }

    fun reorderCategories(orderedIds: List<Long>): Boolean {
        if (orderedIds.isEmpty()) return true
        requireDek()
        val prev = conn.autoCommit; conn.autoCommit = false
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
        requireDek()
        if (isProtected(id)) return false
        if (countActiveEntries(id) > 0) return false
        return conn.prepareStatement("DELETE FROM categories WHERE id = ?").use { ps -> ps.setLong(1, id); ps.executeUpdate() > 0 }
    }

    fun seedDefaultsIfEmpty() {
        val dek = requireDek()
        val count = conn.prepareStatement("SELECT COUNT(*) FROM categories").use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
        if (count > 0) return
        val prev = conn.autoCommit; conn.autoCommit = false
        try {
            for ((name, order) in SEED_CATEGORIES) {
                conn.prepareStatement("INSERT INTO categories (name, name_blob, sort_order, created_at) VALUES ('', ?, ?, ?)").use { ps ->
                    ps.setBytes(1, encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes())
                    ps.setInt(2, order); ps.setLong(3, nowMillis()); ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (e: Exception) { conn.rollback(); throw e } finally { conn.autoCommit = prev }
    }

    private fun decodeName(rs: ResultSet): String {
        val blob = rs.getBytes("name_blob")
        return if (blob != null) decodeCategoryNameBlob(decryptAesGcm(getDek()!!, AeadBlob.fromBytes(blob)))
        else rs.getString("name") ?: ""
    }

    private fun findByName(name: String): Long? {
        val dek = getDek() ?: return null
        conn.prepareStatement("SELECT id, name, name_blob FROM categories").use { ps ->
            ps.executeQuery().use { rs -> while (rs.next()) { if (decodeName(rs) == name) return rs.getLong("id") } }
        }
        return null
    }

    private fun isProtected(id: Long): Boolean =
        conn.prepareStatement("SELECT name, name_blob FROM categories WHERE id = ?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next() && decodeName(rs) == PROTECTED_CATEGORY_NAME }
        }

    private fun countActiveEntries(id: Long): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM password_entries WHERE category_id = ? AND is_deleted = 0").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
}
