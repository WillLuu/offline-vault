package vault

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

// ============================================================================
// VaultBackup：导出 / 导入合并（契约 §3.4）。具体类，零抽象。v2 全加密：
//  - 读取条目/分类时解密 blob（EntryBlob v2）；写入时加密进 blob、明文列留空。
//  - 导出：useMasterPassword=true 用主密码同源派生 KEK（复用 deriveKek，文件头写登录 kdf_salt）；
//          false 用独立导出密码 + export_salt；二者均加密全量 categories+entries -> .vault。
//  - 导入：解密文件 -> 按合并主键合并 -> MergeReport。GCM 认证失败 => WRONG_PASSWORD。
//  .vault 格式与 v1 完全一致（本就是全量加密），跨端/跨版本兼容不变。
// ============================================================================

class VaultBackup(
    private val db: SQLiteDatabase,
    private val getDek: () -> ByteArray?,        // 解锁态 DEK；null=锁定
    private val settings: AppSettingsStore
) {
    fun exportVault(password: String, useMasterPassword: Boolean): ByteArray {
        val dek = getDek() ?: throw LockedException()
        val categories = readCategories(dek)
        val entries = readEntriesWithSecret(dek)
        val json = encodeVaultPayload(buildExportPayload(categories, entries))
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

    // ---- 读（DB，解密） ----
    private fun readCategories(dek: ByteArray): List<ExportCategory> {
        val out = mutableListOf<ExportCategory>()
        db.rawQuery("SELECT name, name_blob, sort_order FROM categories", null).use { c ->
            while (c.moveToNext()) out.add(ExportCategory(decodeCatName(c, dek), c.getInt(c.getColumnIndexOrThrow("sort_order"))))
        }
        return out
    }

    private fun readEntriesWithSecret(dek: ByteArray): List<ExportEntry> {
        val order = categoryOrder(dek)
        val out = mutableListOf<ExportEntry>()
        db.rawQuery("SELECT name, username, secret_blob, category_id, created_at, updated_at FROM password_entries WHERE is_deleted = 0", null).use { c ->
            while (c.moveToNext()) {
                val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(c.getBlob(c.getColumnIndexOrThrow("secret_blob")))))
                val idxCat = c.getColumnIndex("category_id")
                val catName = if (idxCat >= 0 && !c.isNull(idxCat)) (order[c.getLong(idxCat)]?.second ?: "") else ""
                out.add(
                    ExportEntry(
                        name = view.name.ifEmpty { c.getString(0) },
                        username = view.username.ifEmpty { c.getString(1) },
                        categoryName = catName,
                        createdAt = c.getLong(4), updatedAt = c.getLong(5),
                        secret = view.secret
                    )
                )
            }
        }
        return out
    }

    private fun categoryOrder(dek: ByteArray): Map<Long, Pair<Int, String>> {
        val m = HashMap<Long, Pair<Int, String>>()
        db.rawQuery("SELECT id, name, name_blob, sort_order FROM categories", null).use { c ->
            while (c.moveToNext()) m[c.getLong(0)] = c.getInt(3) to decodeCatName(c, dek)
        }
        return m
    }

    private fun decodeCatName(c: android.database.Cursor, dek: ByteArray): String {
        val idxBlob = c.getColumnIndex("name_blob")
        return if (idxBlob >= 0 && !c.isNull(idxBlob))
            decodeCategoryNameBlob(decryptAesGcm(dek, AeadBlob.fromBytes(c.getBlob(idxBlob))))
        else c.getString(c.getColumnIndexOrThrow("name")) ?: ""
    }

    // ---- 合并应用（副作用：写 DB） ----
    private fun applyMerge(dek: ByteArray, payload: VaultPayload): MergeReport {
        val catDao = CategoryDao(db, getDek)
        val currentCats = catDao.listCategories()
        val currentEntries = readCurrentEntryMetas(dek)
        val catDec = decideCategoryMerge(currentCats, payload.categories)
        val entDec = decideEntryMerge(currentEntries, payload.entries)

        var catAdded = 0; var catMerged = 0
        val nameToId = currentCats.associate { it.name to it.id }.toMutableMap()
        for ((_, imp) in catDec.merged) { catDao.updateCategory(nameToId[imp.name]!!, sortOrder = imp.sortOrder); catMerged++ }
        for (imp in catDec.added) {
            var id = nameToId[imp.name]
            if (id == null) { id = catDao.createCategory(imp.name, imp.sortOrder); nameToId[imp.name] = id; catAdded++ }
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
        val cv = ContentValues().apply {
            put("name", ""); put("username", "")
            put("secret_blob", encryptAesGcm(dek, encodeEntryBlob(e.name, e.username, e.secret)).toBytes())
            if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
            put("created_at", e.createdAt); put("updated_at", e.updatedAt); put("is_deleted", 0)
        }
        db.insert("password_entries", null, cv)
    }

    private fun updateEntryById(dek: ByteArray, id: Long, e: ExportEntry, categoryId: Long?) {
        val cv = ContentValues().apply {
            put("name", ""); put("username", "")
            put("secret_blob", encryptAesGcm(dek, encodeEntryBlob(e.name, e.username, e.secret)).toBytes())
            if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
            put("updated_at", e.updatedAt)
        }
        db.update("password_entries", cv, "id = ?", arrayOf(id.toString()))
    }

    private fun readCurrentEntryMetas(dek: ByteArray): List<EntryMeta> {
        val order = categoryOrder(dek)
        val out = mutableListOf<EntryMeta>()
        db.rawQuery("SELECT id, name, username, secret_blob, category_id, updated_at FROM password_entries WHERE is_deleted = 0", null).use { c ->
            while (c.moveToNext()) {
                val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(c.getBlob(c.getColumnIndexOrThrow("secret_blob")))))
                val idxCat = c.getColumnIndex("category_id")
                val catName = if (idxCat >= 0 && !c.isNull(idxCat)) order[c.getLong(idxCat)]?.second else null
                out.add(EntryMeta(c.getLong(0), view.name.ifEmpty { c.getString(1) }, view.username.ifEmpty { c.getString(2) }, catName, c.getLong(5)))
            }
        }
        return out
    }
}
