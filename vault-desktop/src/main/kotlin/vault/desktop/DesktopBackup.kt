package vault.desktop

import java.sql.Connection
import java.sql.Types
import vault.AeadBlob
import vault.ExportCategory
import vault.ExportEntry
import vault.LockedException
import vault.MergeReport
import vault.VaultException
import vault.WrongPasswordException
import vault.decodeCategoryNameBlob
import vault.decodeEntryBlob
import vault.decryptAesGcm
import vault.deriveKey
import vault.buildExportPayload
import vault.decodeVaultPayload
import vault.encodeEntryBlob
import vault.encodeVaultPayload
import vault.encryptAesGcm
import vault.nowMillis
import vault.DEFAULT_KDF
import vault.parseVaultHeader
import vault.randomSalt
import vault.serializeVaultFile

// ============================================================================
// DesktopBackup：.vault 导出/导入合并（镜像 vault-android VaultBackup v2，JDBC 版）。
//  - 读条目/分类时解密 blob；写入时加密进 blob、明文列留空。
//  - .vault 格式与 v1 一致（本就全量加密）→ 跨端/跨版本兼容不变。
// ============================================================================

class DesktopBackup(
    private val conn: Connection,
    private val getDek: () -> ByteArray?,
    private val settings: DesktopSettingsStore
) {
    fun exportVault(password: String, useMasterPassword: Boolean): ByteArray {
        val dek = getDek() ?: throw LockedException()
        val json = encodeVaultPayload(buildExportPayload(readCategories(dek), readEntriesWithSecret(dek)))
        if (useMasterPassword) {
            val kek = settings.deriveKek(password) ?: throw WrongPasswordException()
            val mat = settings.getKdfMaterial() ?: throw VaultException("未初始化：无法用主密码导出")
            return serializeVaultFile(mat.first, mat.second, encryptAesGcm(kek, json.toByteArray(Charsets.UTF_8)))
        }
        val exportSalt = randomSalt()
        val key = deriveKey(password, exportSalt, DEFAULT_KDF)
        return serializeVaultFile(DEFAULT_KDF, exportSalt, encryptAesGcm(key, json.toByteArray(Charsets.UTF_8)))
    }

    fun importVault(file: ByteArray, password: String): MergeReport {
        val dek = getDek() ?: throw LockedException()
        val header = parseVaultHeader(file)
        val key = deriveKey(password, header.exportSalt, header.kdfParams)
        val json = try { String(decryptAesGcm(key, header.blob), Charsets.UTF_8) }
        catch (e: VaultException) { throw WrongPasswordException() }
        return applyMerge(dek, decodeVaultPayload(json))
    }

    // ---- 读（解密） ----
    private fun readCategories(dek: ByteArray): List<ExportCategory> {
        val out = mutableListOf<ExportCategory>()
        conn.prepareStatement("SELECT name, name_blob, sort_order FROM categories").use { ps ->
            ps.executeQuery().use { rs -> while (rs.next()) out.add(ExportCategory(decodeCatName(rs, dek), rs.getInt("sort_order"))) }
        }
        return out
    }

    private fun readEntriesWithSecret(dek: ByteArray): List<ExportEntry> {
        val order = categoryOrder(dek)
        val out = mutableListOf<ExportEntry>()
        conn.prepareStatement("SELECT name, username, secret_blob, category_id, created_at, updated_at FROM password_entries WHERE is_deleted = 0").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(rs.getBytes("secret_blob"))))
                    val catId = if (rs.getObject("category_id") == null) null else rs.getLong("category_id")
                    out.add(
                        ExportEntry(
                            name = view.name.ifEmpty { rs.getString("name") },
                            username = view.username.ifEmpty { rs.getString("username") },
                            categoryName = catId?.let { order[it]?.second ?: "" } ?: "",
                            createdAt = rs.getLong("created_at"), updatedAt = rs.getLong("updated_at"),
                            secret = view.secret
                        )
                    )
                }
            }
        }
        return out
    }

    private fun categoryOrder(dek: ByteArray): Map<Long, Pair<Int, String>> {
        val m = HashMap<Long, Pair<Int, String>>()
        conn.prepareStatement("SELECT id, name, name_blob, sort_order FROM categories").use { ps ->
            ps.executeQuery().use { rs -> while (rs.next()) m[rs.getLong("id")] = rs.getInt("sort_order") to decodeCatName(rs, dek) }
        }
        return m
    }

    private fun decodeCatName(rs: java.sql.ResultSet, dek: ByteArray): String {
        val blob = rs.getBytes("name_blob")
        return if (blob != null) decodeCategoryNameBlob(decryptAesGcm(dek, AeadBlob.fromBytes(blob))) else (rs.getString("name") ?: "")
    }

    // ---- 合并应用 ----
    private fun applyMerge(dek: ByteArray, payload: vault.VaultPayload): MergeReport {
        val catDao = DesktopCategoryDao(conn, getDek)
        val currentCats = catDao.listCategories()
        val currentEntries = readCurrentEntryMetas(dek)
        val catDec = vault.decideCategoryMerge(currentCats, payload.categories)
        val entDec = vault.decideEntryMerge(currentEntries, payload.entries)

        var catAdded = 0; var catMerged = 0
        val nameToId = currentCats.associate { it.name to it.id }.toMutableMap()
        for ((_, imp) in catDec.merged) { catDao.updateCategory(nameToId[imp.name]!!, sortOrder = imp.sortOrder); catMerged++ }
        for (imp in catDec.added) {
            if (nameToId[imp.name] == null) { nameToId[imp.name] = catDao.createCategory(imp.name, imp.sortOrder); catAdded++ }
        }

        var added = 0; var updated = 0
        val keyToId = currentEntries.associate { Triple(it.name, it.username, it.categoryName ?: "") to it.id }.toMutableMap()
        for (imp in entDec.added) { insertEntry(dek, imp, nameToId[imp.categoryName]); added++ }
        for ((_, imp) in entDec.updated) {
            val id = keyToId[Triple(imp.name, imp.username, imp.categoryName)] ?: continue
            updateEntryById(dek, id, imp, nameToId[imp.categoryName]); updated++
        }
        return MergeReport(catAdded, catMerged, added, updated, entDec.skipped)
    }

    private fun insertEntry(dek: ByteArray, e: ExportEntry, categoryId: Long?) {
        conn.prepareStatement(
            "INSERT INTO password_entries (name, username, secret_blob, category_id, created_at, updated_at, is_deleted) VALUES ('', '', ?, ?, ?, ?, 0)"
        ).use { ps ->
            ps.setBytes(1, encryptAesGcm(dek, encodeEntryBlob(e.name, e.username, e.secret)).toBytes())
            if (categoryId == null) ps.setNull(2, Types.INTEGER) else ps.setLong(2, categoryId)
            ps.setLong(3, e.createdAt); ps.setLong(4, e.updatedAt)
            ps.executeUpdate()
        }
    }

    private fun updateEntryById(dek: ByteArray, id: Long, e: ExportEntry, categoryId: Long?) {
        conn.prepareStatement(
            "UPDATE password_entries SET name = '', username = '', secret_blob = ?, category_id = ?, updated_at = ? WHERE id = ? AND is_deleted = 0"
        ).use { ps ->
            ps.setBytes(1, encryptAesGcm(dek, encodeEntryBlob(e.name, e.username, e.secret)).toBytes())
            if (categoryId == null) ps.setNull(2, Types.INTEGER) else ps.setLong(2, categoryId)
            ps.setLong(3, e.updatedAt); ps.setLong(4, id)
            ps.executeUpdate()
        }
    }

    private fun readCurrentEntryMetas(dek: ByteArray): List<vault.EntryMeta> {
        val order = categoryOrder(dek)
        val out = mutableListOf<vault.EntryMeta>()
        conn.prepareStatement("SELECT id, name, username, secret_blob, category_id, updated_at FROM password_entries WHERE is_deleted = 0").use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(rs.getBytes("secret_blob"))))
                    val catId = if (rs.getObject("category_id") == null) null else rs.getLong("category_id")
                    out.add(vault.EntryMeta(rs.getLong("id"), view.name.ifEmpty { rs.getString("name") }, view.username.ifEmpty { rs.getString("username") }, catId?.let { order[it]?.second }, rs.getLong("updated_at")))
                }
            }
        }
        return out
    }
}
