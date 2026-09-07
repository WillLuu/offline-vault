package vault.desktop

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import vault.AeadBlob
import vault.ExportCategory
import vault.ExportEntry
import vault.LockedException
import vault.MergeReport
import vault.SecretPlain
import vault.VaultException
import vault.WrongPasswordException
import vault.buildListCategoriesQuery
import vault.buildExportPayload
import vault.decodeVaultPayload
import vault.decryptAesGcm
import vault.deriveKey
import vault.encodeVaultPayload
import vault.encryptAesGcm
import vault.jsonDecode
import vault.jsonEncode
import vault.nowMillis
import vault.DEFAULT_KDF
import vault.parseVaultHeader
import vault.randomSalt
import vault.serializeVaultFile

// ============================================================================
// DesktopBackup：.vault 导出/导入合并（镜像 vault-android VaultBackup 语义，JDBC 版）。
//  - 格式编解码/合并决策全部走 vault-core（VaultFormat/VaultMerge）——跨端字节兼容的根基。
//  - 导出双路径：useMasterPassword=true 用登录 KEK（文件头写登录 salt/params，同源派生）；
//                false 用独立导出密码 + 随机 export_salt。
//  - 导入：GCM 认证失败 = WrongPasswordException；按 (name,username,分类) 合并、updated_at 取新。
//  - secret JSON 含 extras（非空才写键），与 Android 逐字段一致 → 自定义词条跨端不丢。
// ============================================================================

class DesktopBackup(
    private val conn: Connection,
    private val getDek: () -> ByteArray?,
    private val settings: DesktopSettingsStore
) {

    fun exportVault(password: String, useMasterPassword: Boolean): ByteArray {
        val dek = getDek() ?: throw LockedException()
        val categories = readCategories()
        val entries = readEntriesWithSecret(dek)
        val json = encodeVaultPayload(buildExportPayload(categories, entries))
        if (useMasterPassword) {
            val kek = settings.deriveKek(password) ?: throw WrongPasswordException()
            val mat = settings.getKdfMaterial() ?: throw VaultException("未初始化：无法用主密码导出")
            val blob = encryptAesGcm(kek, json.toByteArray(Charsets.UTF_8))
            return serializeVaultFile(mat.first, mat.second, blob) // 头写登录 salt+params，导入端同源派生
        }
        val exportSalt = randomSalt()
        val key = deriveKey(password, exportSalt, DEFAULT_KDF)
        val blob = encryptAesGcm(key, json.toByteArray(Charsets.UTF_8))
        return serializeVaultFile(DEFAULT_KDF, exportSalt, blob)
    }

    fun importVault(file: ByteArray, password: String): MergeReport {
        val dek = getDek() ?: throw LockedException()
        val header = parseVaultHeader(file)
        val key = deriveKey(password, header.exportSalt, header.kdfParams)
        val json = try {
            String(decryptAesGcm(key, header.blob), Charsets.UTF_8)
        } catch (e: VaultException) {
            throw WrongPasswordException() // 认证失败 = 密码错误/文件损坏
        }
        val payload = decodeVaultPayload(json)
        return applyMerge(dek, payload)
    }

    // ---- 读（DB） ----

    private fun readCategories(): List<ExportCategory> {
        val out = mutableListOf<ExportCategory>()
        conn.prepareStatement(buildListCategoriesQuery()).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) out.add(ExportCategory(rs.getString("name"), rs.getInt("sort_order")))
            }
        }
        return out
    }

    private fun readEntriesWithSecret(dek: ByteArray): List<ExportEntry> {
        val out = mutableListOf<ExportEntry>()
        conn.prepareStatement(
            "SELECT name, username, category_id, created_at, updated_at, secret_blob " +
                "FROM password_entries WHERE is_deleted = 0"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val catId = if (rs.getObject("category_id") == null) null else rs.getLong("category_id")
                    val catName = if (catId == null) "" else (categoryNameById(catId) ?: "")
                    out.add(
                        ExportEntry(
                            name = rs.getString("name"),
                            username = rs.getString("username"),
                            categoryName = catName,
                            createdAt = rs.getLong("created_at"),
                            updatedAt = rs.getLong("updated_at"),
                            secret = decryptSecret(rs.getBytes("secret_blob"), dek)
                        )
                    )
                }
            }
        }
        return out
    }

    private fun categoryNameById(id: Long): String? =
        conn.prepareStatement("SELECT name FROM categories WHERE id = ?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun decryptSecret(blob: ByteArray, dek: ByteArray): SecretPlain {
        val json = String(decryptAesGcm(dek, AeadBlob.fromBytes(blob)), Charsets.UTF_8)
        val m = jsonDecode(json) as Map<*, *>
        val extras = (m["extras"] as? List<*>)?.mapNotNull { e ->
            val em = e as? Map<*, *> ?: return@mapNotNull null
            val l = em["l"] as? String ?: return@mapNotNull null
            vault.ExtraField(l, (em["v"] as? String) ?: "")
        } ?: emptyList()
        return SecretPlain(m["password"] as String, m["website"] as String, m["notes"] as String, extras)
    }

    // ---- 合并应用（副作用：写 DB；决策走 core decideCategoryMerge/decideEntryMerge） ----

    private fun applyMerge(dek: ByteArray, payload: vault.VaultPayload): MergeReport {
        val catDao = DesktopCategoryDao(conn)
        val currentCats = catDao.listCategories()
        val currentEntries = readCurrentEntryMetas()
        val catDec = vault.decideCategoryMerge(currentCats, payload.categories)
        val entDec = vault.decideEntryMerge(currentEntries, payload.entries)

        var catAdded = 0; var catMerged = 0
        val nameToId = currentCats.associate { it.name to it.id }.toMutableMap()
        for ((_, imp) in catDec.merged) { catDao.updateCategory(nameToId[imp.name]!!, sortOrder = imp.sortOrder); catMerged++ }
        for (imp in catDec.added) {
            // 新分类追加到现有最大 sort_order 之后（与 Android 批量录入同纪律，避免插队/冲突）
            val id = catDao.createCategory(imp.name, imp.sortOrder)
            nameToId[imp.name] = id; catAdded++
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

    private fun secretJson(s: SecretPlain): ByteArray {
        val m = linkedMapOf<String, Any?>("password" to s.password, "website" to s.website, "notes" to s.notes)
        if (s.extras.isNotEmpty()) m["extras"] = s.extras.map { mapOf("l" to it.label, "v" to it.value) }
        return jsonEncode(m).toByteArray(Charsets.UTF_8)
    }

    private fun insertEntry(dek: ByteArray, e: ExportEntry, categoryId: Long?) {
        conn.prepareStatement(
            "INSERT INTO password_entries (name, username, secret_blob, category_id, created_at, updated_at, is_deleted) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0)"
        ).use { ps ->
            ps.setString(1, e.name); ps.setString(2, e.username)
            ps.setBytes(3, encryptAesGcm(dek, secretJson(e.secret)).toBytes())
            if (categoryId == null) ps.setNull(4, Types.INTEGER) else ps.setLong(4, categoryId)
            ps.setLong(5, e.createdAt); ps.setLong(6, e.updatedAt)
            ps.executeUpdate()
        }
    }

    private fun updateEntryById(dek: ByteArray, id: Long, e: ExportEntry, categoryId: Long?) {
        conn.prepareStatement(
            "UPDATE password_entries SET name = ?, username = ?, secret_blob = ?, category_id = ?, updated_at = ? " +
                "WHERE id = ? AND is_deleted = 0"
        ).use { ps ->
            ps.setString(1, e.name); ps.setString(2, e.username)
            ps.setBytes(3, encryptAesGcm(dek, secretJson(e.secret)).toBytes())
            if (categoryId == null) ps.setNull(4, Types.INTEGER) else ps.setLong(4, categoryId)
            ps.setLong(5, e.updatedAt); ps.setLong(6, id)
            ps.executeUpdate()
        }
    }

    private fun readCurrentEntryMetas(): List<vault.EntryMeta> {
        val out = mutableListOf<vault.EntryMeta>()
        conn.prepareStatement(
            "SELECT e.id, e.name, e.username, c.name AS category_name, e.updated_at " +
                "FROM password_entries e LEFT JOIN categories c ON e.category_id = c.id WHERE e.is_deleted = 0"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val catName = if (rs.getObject(4) == null) null else rs.getString(4)
                    out.add(vault.EntryMeta(rs.getLong(1), rs.getString(2), rs.getString(3), catName, rs.getLong(5)))
                }
            }
        }
        return out
    }
}
