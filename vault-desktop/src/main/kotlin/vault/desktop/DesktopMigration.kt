package vault.desktop

import java.sql.Connection
import vault.AeadBlob
import vault.SQL_COUNT_UNMIGRATED_CATEGORIES
import vault.SQL_COUNT_UNMIGRATED_ENTRIES
import vault.decodeEntryBlob
import vault.decryptAesGcm
import vault.encodeCategoryNameBlob
import vault.encodeEntryBlob
import vault.encryptAesGcm

// ============================================================================
// 桌面端 v1→v2 数据迁移（需 DEK，解锁后执行）。镜像 Android migrateVaultDataIfNeeded。
// 幂等：仅当存在未迁移行才动作；单事务，异常回滚。
// ============================================================================

fun migrateVaultDataIfNeededJdbc(conn: Connection, dek: ByteArray) {
    var pendingEntries = 0; var pendingCats = 0
    conn.prepareStatement(SQL_COUNT_UNMIGRATED_ENTRIES).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) pendingEntries = rs.getInt(1) } }
    conn.prepareStatement(SQL_COUNT_UNMIGRATED_CATEGORIES).use { ps -> ps.executeQuery().use { rs -> if (rs.next()) pendingCats = rs.getInt(1) } }
    if (pendingEntries == 0 && pendingCats == 0) return

    val prev = conn.autoCommit; conn.autoCommit = false
    try {
        val rows = ArrayList<Triple<Long, String, String>>()
        conn.prepareStatement("SELECT id, name, username FROM password_entries WHERE name <> '' OR username <> ''").use { ps ->
            ps.executeQuery().use { rs -> while (rs.next()) rows.add(Triple(rs.getLong(1), rs.getString(2) ?: "", rs.getString(3) ?: "")) }
        }
        for ((id, colName, colUser) in rows) {
            var blob: ByteArray? = null
            conn.prepareStatement("SELECT secret_blob FROM password_entries WHERE id = ?").use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> if (rs.next()) blob = rs.getBytes(1) } }
            val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(blob!!)))
            val newBlob = encryptAesGcm(dek, encodeEntryBlob(view.name.ifEmpty { colName }, view.username.ifEmpty { colUser }, view.secret)).toBytes()
            conn.prepareStatement("UPDATE password_entries SET name = '', username = '', secret_blob = ? WHERE id = ?").use { ps ->
                ps.setBytes(1, newBlob); ps.setLong(2, id); ps.executeUpdate()
            }
        }
        val cats = ArrayList<Pair<Long, String>>()
        conn.prepareStatement("SELECT id, name FROM categories WHERE name <> '' AND name_blob IS NULL").use { ps ->
            ps.executeQuery().use { rs -> while (rs.next()) cats.add(rs.getLong(1) to (rs.getString(2) ?: "")) }
        }
        for ((id, name) in cats) {
            conn.prepareStatement("UPDATE categories SET name = '', name_blob = ? WHERE id = ?").use { ps ->
                ps.setBytes(1, encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes()); ps.setLong(2, id); ps.executeUpdate()
            }
        }
        conn.commit()
    } catch (e: Exception) { conn.rollback(); throw e } finally { conn.autoCommit = prev }
}
