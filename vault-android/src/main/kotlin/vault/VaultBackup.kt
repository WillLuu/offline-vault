package vault

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

// ============================================================================
// VaultBackup：导出 / 导入合并（契约 §3.4）。具体类，零抽象。
//  - 导出：useMasterPassword=true 用主密码经 Argon2id 派生 KEK（与登录同源，复用 deriveKek，文件头写登录 kdf_salt）；
//          false 用独立导出密码 + 文件内 export_salt 派生密钥；二者均加密全量 categories+entries -> .vault。
//  - 导入：解密文件 -> 按合并主键合并进当前保险库 -> 返回 MergeReport。
// 副作用隔离：纯合并决策(decideCategoryMerge/decideEntryMerge)与格式编解码在 VaultMerge/VaultFormat；
//            本类负责 DB 读取 / 写入（副作用）与密钥派生。
//  合并主键（契约 §3.4）：
//   分类：按 name；命中保留现有 id 并取导入 sort_order；未命中新建。
//   条目：按 (name, username, category_name)；命中按 updated_at 取较新者覆盖；未命中插入。
//   is_deleted=1 的条目不导入（导出仅含 is_deleted=0，格式无 is_deleted 字段，天然满足）。
// ============================================================================

class VaultBackup(
    private val db: SQLiteDatabase,
    private val getDek: () -> ByteArray?,        // 解锁态 DEK；null=锁定
    private val settings: AppSettingsStore
) {

    // 导出：全量 categories + entries（含解密后的 secret）-> 用导出密钥整体加密 -> .vault 字节。
    fun exportVault(password: String, useMasterPassword: Boolean): ByteArray {
        val dek = getDek() ?: throw LockedException()
        // ② useMasterPassword=true：用主密码经 Argon2id 重新派生 KEK（与登录同源，复用 AppSettingsStore.deriveKek），
        //   文件头写登录 kdf_salt+params，导入端据同一主密码同源派生，用户只记一个主密码（非独立导出密码）。
        // ponytail: 复用 deriveKek 的 verifier 校验确保主密码正确后再导出（防 typo 产出打不开的文件）；
        //   KEK 直接加密导出荷载，与独立导出密码路径一致，不额外"先包 DEK 再加密"，减少一层无收益抽象，同源安全性等价。
        //   | 升级阈值：若需导出文件内嵌 wrapped_dek 以支持离线 KEK 轮换/双因子导出，再扩展文件头字段。
        if (useMasterPassword) {
            val kek = settings.deriveKek(password) ?: throw WrongPasswordException() // 主密码错误
            val mat = settings.getKdfMaterial() ?: throw VaultException("未初始化：无法用主密码导出")
            val categories = readCategories()
            val entries = readEntriesWithSecret(dek)
            val json = encodeVaultPayload(buildExportPayload(categories, entries))
            val blob = encryptAesGcm(kek, json.toByteArray(Charsets.UTF_8))
            return serializeVaultFile(mat.first, mat.second, blob) // 头写登录 salt+params，导入同源派生
        }
        val categories = readCategories()
        val entries = readEntriesWithSecret(dek)
        val payload = buildExportPayload(categories, entries)
        val json = encodeVaultPayload(payload)
        val exportSalt = randomSalt()
        val key = deriveKey(password, exportSalt, DEFAULT_KDF)
        val blob = encryptAesGcm(key, json.toByteArray(Charsets.UTF_8))
        return serializeVaultFile(DEFAULT_KDF, exportSalt, blob)
    }

    // 导入：解密文件 -> 按合并主键合并 -> 返回 MergeReport。GCM 认证失败 => WRONG_PASSWORD。
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
        db.rawQuery(buildListCategoriesQuery(), null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ExportCategory(
                        c.getString(c.getColumnIndexOrThrow("name")),
                        c.getInt(c.getColumnIndexOrThrow("sort_order"))
                    )
                )
            }
        }
        return out
    }

    private fun readEntriesWithSecret(dek: ByteArray): List<ExportEntry> {
        val out = mutableListOf<ExportEntry>()
        db.query(
            "password_entries",
            arrayOf("name", "username", "category_id", "created_at", "updated_at", "secret_blob"),
            "is_deleted = 0", null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                val idxCat = c.getColumnIndex("category_id")
                val catName = if (c.isNull(idxCat)) "" else (categoryNameById(c.getLong(idxCat)) ?: "")
                val secret = decryptSecret(c.getBlob(c.getColumnIndexOrThrow("secret_blob")), dek)
                out.add(
                    ExportEntry(
                        name = c.getString(0),
                        username = c.getString(1),
                        categoryName = catName,
                        createdAt = c.getLong(3),
                        updatedAt = c.getLong(4),
                        secret = secret
                    )
                )
            }
        }
        return out
    }

    private fun categoryNameById(id: Long): String? {
        db.query("categories", arrayOf("name"), "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun decryptSecret(blob: ByteArray, dek: ByteArray): SecretPlain {
        val json = String(decryptAesGcm(dek, AeadBlob.fromBytes(blob)), Charsets.UTF_8)
        val m = jsonDecode(json) as Map<*, *>
        val extras = (m["extras"] as? List<*>)?.mapNotNull { e ->
            val em = e as? Map<*, *> ?: return@mapNotNull null
            val l = em["l"] as? String ?: return@mapNotNull null
            ExtraField(l, (em["v"] as? String) ?: "")
        } ?: emptyList()
        return SecretPlain(
            m["password"] as String,
            m["website"] as String,
            m["notes"] as String,
            extras
        )
    }

    // ---- 合并应用（副作用：写 DB） ----
    private fun applyMerge(dek: ByteArray, payload: VaultPayload): MergeReport {
        val catDao = CategoryDao(db)
        val currentCats = catDao.listCategories()
        val currentEntries = readCurrentEntryMetas()
        val catDec = decideCategoryMerge(currentCats, payload.categories)
        val entDec = decideEntryMerge(currentEntries, payload.entries)

        var catAdded = 0
        var catMerged = 0
        val nameToId = currentCats.associate { it.name to it.id }.toMutableMap()

        // 分类：命中（取导入 sort_order）+ 新增
        for ((_, imp) in catDec.merged) {
            catDao.updateCategory(nameToId[imp.name]!!, sortOrder = imp.sortOrder)
            catMerged++
        }
        for (imp in catDec.added) {
            val id = catDao.createCategory(imp.name, imp.sortOrder)
            nameToId[imp.name] = id
            catAdded++
        }

        // 条目：新增 + 更新（命中按现有 id 覆盖，用导入较新 updated_at）
        var added = 0
        var updated = 0
        val keyToId = currentEntries.associate { Triple(it.name, it.username, it.categoryName ?: "") to it.id }.toMutableMap()
        for (imp in entDec.added) {
            insertEntry(dek, imp, nameToId[imp.categoryName])
            added++
        }
        for ((_, imp) in entDec.updated) {
            val id = keyToId[Triple(imp.name, imp.username, imp.categoryName)] ?: continue // 防御：理论上必存在
            updateEntryById(dek, id, imp, nameToId[imp.categoryName])
            updated++
        }
        return MergeReport(catAdded, catMerged, added, updated, entDec.skipped)
    }

    private fun secretJson(s: SecretPlain): ByteArray {
        val m = linkedMapOf<String, Any?>("password" to s.password, "website" to s.website, "notes" to s.notes)
        if (s.extras.isNotEmpty()) {
            m["extras"] = s.extras.map { mapOf("l" to it.label, "v" to it.value) }
        }
        return jsonEncode(m).toByteArray(Charsets.UTF_8)
    }

    private fun insertEntry(dek: ByteArray, e: ExportEntry, categoryId: Long?) {
        val cv = ContentValues().apply {
            put("name", e.name)
            put("username", e.username)
            put("secret_blob", encryptAesGcm(dek, secretJson(e.secret)).toBytes())
            if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
            put("created_at", e.createdAt)
            put("updated_at", e.updatedAt)
            put("is_deleted", 0)
        }
        db.insert("password_entries", null, cv)
    }

    private fun updateEntryById(dek: ByteArray, id: Long, e: ExportEntry, categoryId: Long?) {
        val cv = ContentValues().apply {
            put("name", e.name)
            put("username", e.username)
            put("secret_blob", encryptAesGcm(dek, secretJson(e.secret)).toBytes())
            if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
            put("updated_at", e.updatedAt) // 用导入的较新 updated_at
        }
        db.update("password_entries", cv, "id = ?", arrayOf(id.toString()))
    }

    private fun readCurrentEntryMetas(): List<EntryMeta> {
        val out = mutableListOf<EntryMeta>()
        db.rawQuery(
            """
            SELECT e.id, e.name, e.username, c.name AS category_name, e.updated_at
            FROM password_entries e LEFT JOIN categories c ON e.category_id = c.id
            WHERE e.is_deleted = 0
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                // c.name 显式别名 category_name，避免与 e.name 列名冲突；按别名读取，语义无歧义。
                val idxCatName = c.getColumnIndexOrThrow("category_name")
                out.add(
                    EntryMeta(
                        id = c.getLong(0),
                        name = c.getString(1),
                        username = c.getString(2),
                        categoryName = if (c.isNull(idxCatName)) null else c.getString(idxCatName),
                        updatedAt = c.getLong(4)
                    )
                )
            }
        }
        return out
    }
}
