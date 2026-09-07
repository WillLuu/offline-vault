package vault.desktop

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import vault.AeadBlob
import vault.EntryInput
import vault.ExtraField
import vault.LIST_NO_LIMIT
import vault.LockedException
import vault.PasswordEntryRow
import vault.SecretPlain
import vault.SortKey
import vault.buildListEntriesQuery
import vault.decryptAesGcm
import vault.encryptAesGcm
import vault.jsonDecode
import vault.jsonEncode
import vault.nowMillis

// ============================================================================
// DesktopEntryDao：密码条目 JDBC 访问（镜像 vault-android PasswordEntryDao 语义）。
//  - 读取 SQL 复用 vault-core buildListEntriesQuery（单一事实源，两端零漂移）。
//  - secret_blob = AES-256-GCM(JSON{password,website,notes[,extras]})，编解码与 Android 逐字节同构。
//  - 锁定态（getDek()==null）一律 LockedException 拒绝（契约 §3 / G4 安全对等）。
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
        requireUnlocked()
        val (sql, args) = buildListEntriesQuery(search, sortBy, categoryId, limit, offset)
        val rows = mutableListOf<PasswordEntryRow>()
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> while (rs.next()) rows.add(rowFrom(rs, masked = true)) }
        }
        return rows
    }

    fun getEntry(id: Long): PasswordEntryRow? {
        requireUnlocked()
        conn.prepareStatement(
            "SELECT e.id, e.name, e.username, e.secret_blob, e.category_id, " +
                "e.created_at, e.updated_at, e.is_deleted, c.name AS category_name " +
                "FROM password_entries e LEFT JOIN categories c ON e.category_id = c.id " +
                "WHERE e.id = ? AND e.is_deleted = 0"
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val secret = decryptSecret(rs.getBytes("secret_blob"))
                return rowFrom(rs, masked = false, secret = secret)
            }
        }
    }

    fun createEntry(input: EntryInput): Long {
        val dek = requireUnlocked()
        val now = nowMillis()
        val catId = input.categoryId
        return insertReturnId(
            "INSERT INTO password_entries (name, username, secret_blob, category_id, created_at, updated_at, is_deleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0)"
        ) { ps ->
            ps.setString(1, input.name)
            ps.setString(2, input.username)
            ps.setBytes(3, encryptAesGcm(dek, secretJson(input.password, input.website, input.notes, input.extras)).toBytes())
            if (catId == null) ps.setNull(4, Types.INTEGER) else ps.setLong(4, catId)
            ps.setLong(5, now); ps.setLong(6, now)
        }
    }

    fun updateEntry(id: Long, input: EntryInput): Boolean {
        val dek = requireUnlocked()
        val catId = input.categoryId
        return conn.prepareStatement(
            "UPDATE password_entries SET name = ?, username = ?, secret_blob = ?, category_id = ?, updated_at = ? " +
                "WHERE id = ? AND is_deleted = 0"
        ).use { ps ->
            ps.setString(1, input.name)
            ps.setString(2, input.username)
            ps.setBytes(3, encryptAesGcm(dek, secretJson(input.password, input.website, input.notes, input.extras)).toBytes())
            if (catId == null) ps.setNull(4, Types.INTEGER) else ps.setLong(4, catId)
            ps.setLong(5, nowMillis()); ps.setLong(6, id)
            ps.executeUpdate() > 0
        }
    }

    /** 软删（与 Android 一致：不物理删除）。 */
    fun deleteEntry(id: Long): Boolean = conn.prepareStatement(
        "UPDATE password_entries SET is_deleted = 1, updated_at = ? WHERE id = ? AND is_deleted = 0"
    ).use { ps -> ps.setLong(1, nowMillis()); ps.setLong(2, id); ps.executeUpdate() > 0 }

    // ---- 内部 ----

    private fun requireUnlocked(): ByteArray = getDek() ?: throw LockedException()

    // extras 仅非空时写入（与 Android PasswordEntryDao.secretJson 逐字段一致，保证跨端 secret 语义相同）。
    private fun secretJson(password: String, website: String, notes: String, extras: List<ExtraField>): ByteArray {
        val m = linkedMapOf<String, Any?>("password" to password, "website" to website, "notes" to notes)
        if (extras.isNotEmpty()) m["extras"] = extras.map { mapOf("l" to it.label, "v" to it.value) }
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
        return SecretPlain(m["password"] as String, m["website"] as String, m["notes"] as String, extras)
    }

    private fun rowFrom(rs: java.sql.ResultSet, masked: Boolean, secret: SecretPlain? = null): PasswordEntryRow {
        val catIdx = try { rs.findColumn("category_name") } catch (_: Exception) { 0 }
        val categoryName = if (catIdx > 0 && rs.getObject(catIdx) != null) rs.getString(catIdx) else null
        val categoryId = if (rs.getObject("category_id") == null) null else rs.getLong("category_id")
        return PasswordEntryRow(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            username = rs.getString("username"),
            password = if (masked) null else secret?.password,
            website = if (masked) null else secret?.website,
            notes = if (masked) null else secret?.notes,
            categoryId = categoryId,
            categoryName = categoryName,
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            isDeleted = rs.getInt("is_deleted") == 1,
            extras = if (masked) emptyList() else secret?.extras ?: emptyList()
        )
    }

    private fun insertReturnId(sql: String, bind: (PreparedStatement) -> Unit): Long =
        conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { ps ->
            bind(ps); ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }
}
