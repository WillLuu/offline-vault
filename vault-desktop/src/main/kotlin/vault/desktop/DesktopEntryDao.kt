package vault.desktop

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import vault.AeadBlob
import vault.EntryInput
import vault.LIST_NO_LIMIT
import vault.LockedException
import vault.PasswordEntryRow
import vault.SecretPlain
import vault.SortKey
import vault.buildGetEntryQuery
import vault.buildListEntriesQuery
import vault.decodeCategoryNameBlob
import vault.decodeEntryBlob
import vault.decryptAesGcm
import vault.encryptAesGcm
import vault.encodeEntryBlob
import vault.filterAndSortEntries
import vault.nowMillis

// ============================================================================
// DesktopEntryDao：密码条目 JDBC 访问（镜像 vault-android PasswordEntryDao v2 语义）。
//  - 条目 name/username 与 secret 一并加密进 secret_blob（EntryBlob v2）；明文列恒空。
//  - listEntries：结构取行 → 解密 → 内存搜索/排序/分页。锁定态 LockedException。
// ============================================================================

class DesktopEntryDao(
    private val conn: Connection,
    private val getDek: () -> ByteArray?
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
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> while (rs.next()) all.add(decryptRow(rs, dek, catName(order, rs))) }
        }
        return filterAndSortEntries(all, search, sortBy, categoryId, order, limit, offset)
            .map { it.copy(password = null, website = null, notes = null, extras = emptyList()) }
    }

    fun getEntry(id: Long): PasswordEntryRow? {
        val dek = requireUnlocked()
        conn.prepareStatement(buildGetEntryQuery()).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val catId = if (rs.getObject("category_id") == null) null else rs.getLong("category_id")
                return decryptRow(rs, dek, catId?.let { loadCategoryOrder(dek)[it]?.second })
            }
        }
    }

    fun createEntry(input: EntryInput): Long {
        val dek = requireUnlocked()
        val now = nowMillis()
        val catId = input.categoryId
        return conn.prepareStatement(
            "INSERT INTO password_entries (name, username, secret_blob, category_id, created_at, updated_at, is_deleted) VALUES ('', '', ?, ?, ?, ?, 0)",
            PreparedStatement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setBytes(1, encryptAesGcm(dek, encodeEntryBlob(input.name, input.username, SecretPlain(input.password, input.website, input.notes, input.extras))).toBytes())
            if (catId == null) ps.setNull(2, Types.INTEGER) else ps.setLong(2, catId)
            ps.setLong(3, now); ps.setLong(4, now)
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }
    }

    fun updateEntry(id: Long, input: EntryInput): Boolean {
        val dek = requireUnlocked()
        val catId = input.categoryId
        return conn.prepareStatement(
            "UPDATE password_entries SET name = '', username = '', secret_blob = ?, category_id = ?, updated_at = ? WHERE id = ? AND is_deleted = 0"
        ).use { ps ->
            ps.setBytes(1, encryptAesGcm(dek, encodeEntryBlob(input.name, input.username, SecretPlain(input.password, input.website, input.notes, input.extras))).toBytes())
            if (catId == null) ps.setNull(2, Types.INTEGER) else ps.setLong(2, catId)
            ps.setLong(3, nowMillis()); ps.setLong(4, id)
            ps.executeUpdate() > 0
        }
    }

    fun deleteEntry(id: Long): Boolean = conn.prepareStatement(
        "UPDATE password_entries SET is_deleted = 1, updated_at = ? WHERE id = ? AND is_deleted = 0"
    ).use { ps -> ps.setLong(1, nowMillis()); ps.setLong(2, id); ps.executeUpdate() > 0 }

    // ---- 内部 ----
    private fun requireUnlocked(): ByteArray = getDek() ?: throw LockedException()

    private fun decryptRow(rs: ResultSet, dek: ByteArray, catName: String?): PasswordEntryRow {
        val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(rs.getBytes("secret_blob"))))
        val colName = rs.getString("name") ?: ""
        val colUser = rs.getString("username") ?: ""
        return PasswordEntryRow(
            id = rs.getLong("id"),
            name = view.name.ifEmpty { colName },
            username = view.username.ifEmpty { colUser },
            password = view.secret.password, website = view.secret.website, notes = view.secret.notes,
            categoryId = if (rs.getObject("category_id") == null) null else rs.getLong("category_id"),
            categoryName = catName,
            createdAt = rs.getLong("created_at"), updatedAt = rs.getLong("updated_at"),
            isDeleted = false, extras = view.secret.extras
        )
    }

    private fun catName(order: Map<Long, Pair<Int, String>>, rs: ResultSet): String? {
        val catId = if (rs.getObject("category_id") == null) null else rs.getLong("category_id")
        return catId?.let { order[it]?.second }
    }

    private fun loadCategoryOrder(dek: ByteArray): Map<Long, Pair<Int, String>> {
        val m = HashMap<Long, Pair<Int, String>>()
        conn.prepareStatement("SELECT id, name, name_blob, sort_order FROM categories").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val blob = rs.getBytes("name_blob")
                    val name = if (blob != null) decodeCategoryNameBlob(decryptAesGcm(dek, AeadBlob.fromBytes(blob))) else (rs.getString("name") ?: "")
                    m[rs.getLong("id")] = rs.getInt("sort_order") to name
                }
            }
        }
        return m
    }
}
