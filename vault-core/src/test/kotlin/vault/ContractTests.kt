package vault

// ============================================================================
// 契约测试套件（tests/contract，契约 api-contract.md）。无测试框架（纯 main/assert），
// 可在普通 JVM 直接执行（不依赖 Android/UI/Keystore）。
//
// v2（全加密）语义：name/username/category-name 均以密文存于库中，明文列恒为空串；
//   搜索/排序/分页在内存（Query.filterAndSortEntries）。本 harness 用【同一份 SQL + 同一份加密原语
//   + 同一份 EntryBlob 编解码】在 sqlite-jdbc 上重放 DAO 行为，并额外断言"库内无明文元数据"与
//   "v1→v2 静默迁移"。DAO 仅是这些 SQL+加密的机械封装，故验证此处即验证契约。
// ============================================================================

import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

fun ResultSet.strOrNull(col: Int): String? = if (getObject(col) == null) null else getString(col)
fun ResultSet.longOrNull(col: Int): Long? = if (getObject(col) == null) null else getLong(col)

fun execDdl(conn: java.sql.Connection, stmts: List<String>) {
    val st = conn.createStatement()
    try { for (s in stmts) st.execute(s) } finally { st.close() }
}

fun insertReturnId(conn: java.sql.Connection, sql: String, bind: (java.sql.PreparedStatement) -> Unit): Long {
    val ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)
    try {
        bind(ps); ps.executeUpdate()
        val gk = ps.generatedKeys
        try { gk.next(); return gk.getLong(1) } finally { gk.close() }
    } finally { ps.close() }
}

fun jdbcUpdate(conn: java.sql.Connection, sql: String, bind: (java.sql.PreparedStatement) -> Unit): Int {
    val ps = conn.prepareStatement(sql)
    try { bind(ps); return ps.executeUpdate() } finally { ps.close() }
}

fun jdbcQuery(conn: java.sql.Connection, sql: String, args: Array<String> = emptyArray(), each: (java.sql.ResultSet) -> Unit) {
    val ps = conn.prepareStatement(sql)
    try {
        args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
        val rs = ps.executeQuery()
        try { while (rs.next()) each(rs) } finally { rs.close() }
    } finally { ps.close() }
}

// ---------------------- v2 DAO 镜像（harness） ----------------------

// 分类名解密（name_blob 优先；迁移期回退明文列）。
private fun readCategoryName(conn: java.sql.Connection, dek: ByteArray, id: Long): String? {
    var r: String? = null
    jdbcQuery(conn, "SELECT name, name_blob FROM categories WHERE id = ?", arrayOf(id.toString())) { rs ->
        r = decodeCatName(dek, rs.getBytes(rs.findColumn("name_blob")), rs.getString(rs.findColumn("name")))
    }
    return r
}

private fun decodeCatName(dek: ByteArray, blob: ByteArray?, plainFallback: String?): String {
    if (blob != null) return decodeCategoryNameBlob(decryptAesGcm(dek, AeadBlob.fromBytes(blob)))
    return plainFallback ?: ""
}

// 镜像 CategoryDao.createCategory（v2：name 明文列写空，真名进 name_blob）。
fun insertCategory(conn: java.sql.Connection, dek: ByteArray, name: String, sortOrder: Int): Long =
    insertReturnId(conn, "INSERT INTO categories (name, name_blob, sort_order, created_at) VALUES ('', ?, ?, ?)") { ps ->
        ps.setBytes(1, encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes())
        ps.setInt(2, sortOrder)
        ps.setLong(3, nowMillis())
    }

// 镜像 CategoryDao.listCategories（解密 name_blob）。
fun listCategoriesWithCount(conn: java.sql.Connection, dek: ByteArray): List<CategoryRow> {
    val out = mutableListOf<CategoryRow>()
    jdbcQuery(conn, buildListCategoriesQuery()) { rs ->
        out.add(
            CategoryRow(
                id = rs.getLong(rs.findColumn("id")),
                name = decodeCatName(dek, rs.getBytes(rs.findColumn("name_blob")), rs.getString(rs.findColumn("name"))),
                sortOrder = rs.getInt(rs.findColumn("sort_order")),
                createdAt = rs.getLong(rs.findColumn("created_at")),
                entryCount = rs.getInt(rs.findColumn("entry_count"))
            )
        )
    }
    return out
}

fun categoryOrderMap(conn: java.sql.Connection, dek: ByteArray): Map<Long, Pair<Int, String>> =
    listCategoriesWithCount(conn, dek).associate { it.id to (it.sortOrder to it.name) }

// 镜像 PasswordEntryDao.createEntry（v2：name/username 明文列写空，全字段进 secret_blob）。
fun createEntry(
    conn: java.sql.Connection, dek: ByteArray,
    name: String, username: String, password: String, website: String, notes: String, categoryId: Long?,
    extras: List<ExtraField> = emptyList()
): Long {
    val sql = "INSERT INTO password_entries (name, username, secret_blob, category_id, created_at, updated_at, is_deleted) " +
        "VALUES ('', '', ?, ?, ?, ?, 0)"
    return insertReturnId(conn, sql) { ps ->
        ps.setBytes(1, encryptAesGcm(dek, encodeEntryBlob(name, username, SecretPlain(password, website, notes, extras))).toBytes())
        val now = nowMillis()
        if (categoryId == null) ps.setNull(2, Types.INTEGER) else ps.setLong(2, categoryId)
        ps.setLong(3, now); ps.setLong(4, now)
    }
}

fun updateEntryById(
    conn: java.sql.Connection, dek: ByteArray, id: Long,
    name: String, username: String, password: String, website: String, notes: String, categoryId: Long?
): Boolean = jdbcUpdate(conn, "UPDATE password_entries SET name = '', username = '', secret_blob = ?, category_id = ?, updated_at = ? WHERE id = ? AND is_deleted = 0") { ps ->
    ps.setBytes(1, encryptAesGcm(dek, encodeEntryBlob(name, username, SecretPlain(password, website, notes))).toBytes())
    if (categoryId == null) ps.setNull(2, Types.INTEGER) else ps.setLong(2, categoryId)
    ps.setLong(3, nowMillis()); ps.setLong(4, id)
} > 0

fun softDeleteEntry(conn: java.sql.Connection, id: Long): Boolean =
    jdbcUpdate(conn, "UPDATE password_entries SET is_deleted = 1, updated_at = ? WHERE id = ? AND is_deleted = 0") { ps ->
        ps.setLong(1, nowMillis()); ps.setLong(2, id)
    } > 0

// 从一行结果集构造解密后的 PasswordEntryRow（name/username 从 blob，迁移期回退明文列）。
private fun entryRowFrom(rs: ResultSet, dek: ByteArray, catName: String?): PasswordEntryRow {
    val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(rs.getBytes(rs.findColumn("secret_blob")))))
    val colName = rs.getString(rs.findColumn("name"))
    val colUser = rs.getString(rs.findColumn("username"))
    return PasswordEntryRow(
        id = rs.getLong(rs.findColumn("id")),
        name = view.name.ifEmpty { colName },
        username = view.username.ifEmpty { colUser },
        password = view.secret.password, website = view.secret.website, notes = view.secret.notes,
        categoryId = rs.longOrNull(rs.findColumn("category_id")),
        categoryName = catName,
        createdAt = rs.getLong(rs.findColumn("created_at")),
        updatedAt = rs.getLong(rs.findColumn("updated_at")),
        isDeleted = false,
        extras = view.secret.extras
    )
}

// 镜像 PasswordEntryDao.listEntries（v2：结构过滤取行→解密→内存搜索/排序/分页；返回掩码行）。
fun listEntriesMasked(
    conn: java.sql.Connection, dek: ByteArray,
    search: String? = null, sortBy: SortKey = SortKey.NAME_ASC,
    categoryId: Long? = null, limit: Int = LIST_NO_LIMIT, offset: Int = 0
): List<PasswordEntryRow> {
    val (sql, args) = buildListEntriesQuery(categoryId)
    val order = categoryOrderMap(conn, dek)
    val all = mutableListOf<PasswordEntryRow>()
    jdbcQuery(conn, sql, args) { rs ->
        val catId = rs.longOrNull(rs.findColumn("category_id"))
        val catName = catId?.let { order[it]?.second }
        all.add(entryRowFrom(rs, dek, catName))
    }
    return filterAndSortEntries(all, search, sortBy, categoryId, order, limit, offset)
        .map { it.copy(password = null, website = null, notes = null, extras = emptyList()) }
}

fun getEntryDecrypted(conn: java.sql.Connection, dek: ByteArray, id: Long): PasswordEntryRow? {
    var r: PasswordEntryRow? = null
    jdbcQuery(conn, buildGetEntryQuery(), arrayOf(id.toString())) { rs ->
        val catId = rs.longOrNull(rs.findColumn("category_id"))
        val catName = catId?.let { readCategoryName(conn, dek, it) }
        r = entryRowFrom(rs, dek, catName)
    }
    return r
}

fun deleteCategory(conn: java.sql.Connection, dek: ByteArray, id: Long): Boolean {
    if (readCategoryName(conn, dek, id) == "其他") return false
    var count = 0
    jdbcQuery(conn, "SELECT COUNT(*) FROM password_entries WHERE category_id = ? AND is_deleted = 0", arrayOf(id.toString())) { rs -> count = rs.getInt(1) }
    if (count > 0) return false
    return jdbcUpdate(conn, "DELETE FROM categories WHERE id = ?") { ps -> ps.setLong(1, id) } > 0
}

fun updateCategorySortOrder(conn: java.sql.Connection, id: Long, sortOrder: Int): Boolean =
    jdbcUpdate(conn, "UPDATE categories SET sort_order = ? WHERE id = ?") { ps -> ps.setInt(1, sortOrder); ps.setLong(2, id) } > 0

fun seedDefaultsIfEmpty(conn: java.sql.Connection, dek: ByteArray) {
    var count = 0
    jdbcQuery(conn, "SELECT COUNT(*) FROM categories") { rs -> count = rs.getInt(1) }
    if (count > 0) return
    for ((name, order) in listOf("支付" to 0, "社交" to 1, "工作" to 2, "娱乐" to 3, "邮箱" to 4, "其他" to 5))
        insertCategory(conn, dek, name, order)
}

// ---------------------- app_settings 镜像（与 v2 无关，沿用） ----------------------

fun writeInitialization(conn: java.sql.Connection, salt: ByteArray, wrappedDek: AeadBlob, verifier: AeadBlob, bioWrapped: ByteArray?) {
    val now = nowMillis()
    insertReturnId(
        conn,
        "INSERT INTO app_settings (id, initialized, kdf_algo, kdf_memory_kb, kdf_iterations, kdf_parallelism, " +
            "kdf_salt, wrapped_dek, verifier, wrapped_dek_biometric, biometric_enabled, " +
            "auto_lock_timeout_sec, clipboard_clear_delay_sec, theme, created_at, updated_at) " +
            "VALUES (1,1,'argon2id',${DEFAULT_KDF.memoryKb},${DEFAULT_KDF.iterations},${DEFAULT_KDF.parallelism},?,?,?,?,?,60,30,'system',?,?)"
    ) { ps ->
        ps.setBytes(1, salt); ps.setBytes(2, wrappedDek.toBytes()); ps.setBytes(3, verifier.toBytes())
        if (bioWrapped == null) ps.setNull(4, Types.BLOB) else ps.setBytes(4, bioWrapped)
        ps.setInt(5, if (bioWrapped != null) 1 else 0); ps.setLong(6, now); ps.setLong(7, now)
    }
}

fun readKdfAndVerifier(conn: java.sql.Connection): Triple<KdfParams, ByteArray, ByteArray>? {
    var r: Triple<KdfParams, ByteArray, ByteArray>? = null
    jdbcQuery(conn, "SELECT kdf_memory_kb, kdf_iterations, kdf_parallelism, kdf_salt, verifier FROM app_settings WHERE id = 1") { rs ->
        r = Triple(KdfParams("argon2id", rs.getInt(1), rs.getInt(2), rs.getInt(3)), rs.getBytes(4), rs.getBytes(5))
    }
    return r
}

fun readWrappedDek(conn: java.sql.Connection): ByteArray? {
    var r: ByteArray? = null
    jdbcQuery(conn, "SELECT wrapped_dek FROM app_settings WHERE id = 1") { rs -> r = rs.getBytes(1) }
    return r
}

fun deriveKek(conn: java.sql.Connection, password: String): ByteArray? {
    val kv = readKdfAndVerifier(conn) ?: return null
    val kek = deriveKey(password, kv.second, kv.first)
    return if (checkVerifier(kek, AeadBlob.fromBytes(kv.third))) kek else null
}

fun changeMasterPassword(conn: java.sql.Connection, oldPassword: String, newPassword: String): Boolean {
    val kekOld = deriveKek(conn, oldPassword) ?: return false
    val dek = unwrapKey(kekOld, AeadBlob.fromBytes(readWrappedDek(conn)!!))
    val newSalt = randomSalt(); val kekNew = deriveKey(newPassword, newSalt, DEFAULT_KDF)
    val n = jdbcUpdate(conn, "UPDATE app_settings SET kdf_salt = ?, wrapped_dek = ?, verifier = ?, updated_at = ? WHERE id = 1") { ps ->
        ps.setBytes(1, newSalt); ps.setBytes(2, wrapKey(kekNew, dek).toBytes()); ps.setBytes(3, makeVerifier(kekNew).toBytes()); ps.setLong(4, nowMillis())
    }
    zeroBytes(kekOld); zeroBytes(kekNew); return n > 0
}

// ---------------------- 导出/导入（复用纯编解码+合并决策） ----------------------

fun readAllCategories(conn: java.sql.Connection, dek: ByteArray): List<ExportCategory> =
    listCategoriesWithCount(conn, dek).map { ExportCategory(it.name, it.sortOrder) }

fun readAllEntriesWithSecret(conn: java.sql.Connection, dek: ByteArray): List<ExportEntry> {
    val out = mutableListOf<ExportEntry>()
    jdbcQuery(conn, "SELECT id, name, username, secret_blob, category_id, created_at, updated_at FROM password_entries WHERE is_deleted = 0") { rs ->
        val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(rs.getBytes(rs.findColumn("secret_blob")))))
        val name = view.name.ifEmpty { rs.getString(2) }
        val user = view.username.ifEmpty { rs.getString(3) }
        val catId = rs.longOrNull(5)
        out.add(ExportEntry(name, user, catId?.let { readCategoryName(conn, dek, it) } ?: "", rs.getLong(6), rs.getLong(7), view.secret))
    }
    return out
}

fun exportVault(conn: java.sql.Connection, dek: ByteArray, password: String, useMasterPassword: Boolean = false): ByteArray {
    val json = encodeVaultPayload(buildExportPayload(readAllCategories(conn, dek), readAllEntriesWithSecret(conn, dek)))
    if (useMasterPassword) {
        val kek = deriveKek(conn, password) ?: throw WrongPasswordException()
        val kv = readKdfAndVerifier(conn) ?: throw VaultException("未初始化")
        return serializeVaultFile(kv.first, kv.second, encryptAesGcm(kek, json.toByteArray(Charsets.UTF_8)))
    }
    val exportSalt = randomSalt(); val key = deriveKey(password, exportSalt, DEFAULT_KDF)
    return serializeVaultFile(DEFAULT_KDF, exportSalt, encryptAesGcm(key, json.toByteArray(Charsets.UTF_8)))
}

fun readCurrentEntryMetas(conn: java.sql.Connection, dek: ByteArray): List<EntryMeta> {
    val out = mutableListOf<EntryMeta>()
    val order = categoryOrderMap(conn, dek)
    jdbcQuery(conn, "SELECT id, name, username, secret_blob, category_id, updated_at FROM password_entries WHERE is_deleted = 0") { rs ->
        val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(rs.getBytes(rs.findColumn("secret_blob")))))
        val catId = rs.longOrNull(5)
        out.add(EntryMeta(rs.getLong(1), view.name.ifEmpty { rs.getString(2) }, view.username.ifEmpty { rs.getString(3) }, catId?.let { order[it]?.second }, rs.getLong(6)))
    }
    return out
}

fun applyMerge(conn: java.sql.Connection, dek: ByteArray, payload: VaultPayload): MergeReport {
    val currentCats = listCategoriesWithCount(conn, dek)
    val currentEntries = readCurrentEntryMetas(conn, dek)
    val catDec = decideCategoryMerge(currentCats, payload.categories)
    val entDec = decideEntryMerge(currentEntries, payload.entries)
    var catAdded = 0; var catMerged = 0
    val nameToId = currentCats.associate { it.name to it.id }.toMutableMap()
    for ((_, imp) in catDec.merged) { updateCategorySortOrder(conn, nameToId[imp.name]!!, imp.sortOrder); catMerged++ }
    for (imp in catDec.added) { nameToId[imp.name] = insertCategory(conn, dek, imp.name, imp.sortOrder); catAdded++ }
    var added = 0; var updated = 0
    val keyToId = currentEntries.associate { Triple(it.name, it.username, it.categoryName ?: "") to it.id }.toMutableMap()
    for (imp in entDec.added) { createEntry(conn, dek, imp.name, imp.username, imp.secret.password, imp.secret.website, imp.secret.notes, nameToId[imp.categoryName], imp.secret.extras); added++ }
    for ((_, imp) in entDec.updated) {
        val id = keyToId[Triple(imp.name, imp.username, imp.categoryName)] ?: continue
        updateEntryById(conn, dek, id, imp.name, imp.username, imp.secret.password, imp.secret.website, imp.secret.notes, nameToId[imp.categoryName]); updated++
    }
    return MergeReport(catAdded, catMerged, added, updated, entDec.skipped)
}

fun importVault(conn: java.sql.Connection, dek: ByteArray, file: ByteArray, password: String): MergeReport {
    val header = parseVaultHeader(file)
    val key = deriveKey(password, header.exportSalt, header.kdfParams)
    val json = try { String(decryptAesGcm(key, header.blob), Charsets.UTF_8) } catch (e: VaultException) { throw WrongPasswordException() }
    return applyMerge(conn, dek, decodeVaultPayload(json))
}

// ---------------------- v1→v2 数据迁移镜像（需 DEK；DAO 在解锁后执行同一逻辑） ----------------------

fun migrateDataV1toV2(conn: java.sql.Connection, dek: ByteArray) {
    conn.autoCommit = false
    try {
        // 条目：把明文 name/username 折进 secret_blob（若 blob 已是 v2 则幂等跳过），再清空明文列。
        val rows = mutableListOf<Triple<Long, String, String>>() // id, name, username（明文列）
        jdbcQuery(conn, "SELECT id, name, username, secret_blob FROM password_entries WHERE name <> '' OR username <> ''") { rs ->
            rows.add(Triple(rs.getLong(1), rs.getString(2), rs.getString(3)))
        }
        for ((id, name, user) in rows) {
            var blobBytes: ByteArray? = null
            jdbcQuery(conn, "SELECT secret_blob FROM password_entries WHERE id = ?", arrayOf(id.toString())) { rs -> blobBytes = rs.getBytes(1) }
            val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(blobBytes!!)))
            val n = if (view.name.isNotEmpty()) view.name else name
            val u = if (view.username.isNotEmpty()) view.username else user
            val newBlob = encryptAesGcm(dek, encodeEntryBlob(n, u, view.secret)).toBytes()
            jdbcUpdate(conn, "UPDATE password_entries SET name = '', username = '', secret_blob = ? WHERE id = ?") { ps -> ps.setBytes(1, newBlob); ps.setLong(2, id) }
        }
        // 分类：明文 name → name_blob，清空明文列。
        val cats = mutableListOf<Pair<Long, String>>()
        jdbcQuery(conn, "SELECT id, name FROM categories WHERE name <> '' AND name_blob IS NULL") { rs -> cats.add(rs.getLong(1) to rs.getString(2)) }
        for ((id, name) in cats) {
            val blob = encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes()
            jdbcUpdate(conn, "UPDATE categories SET name = '', name_blob = ? WHERE id = ?") { ps -> ps.setBytes(1, blob); ps.setLong(2, id) }
        }
        conn.commit()
    } catch (e: Exception) { conn.rollback(); throw e }
    conn.autoCommit = true
}

// ============================================================================
// 契约检查
// ============================================================================

fun checkListEntries(conn: java.sql.Connection) {
    val dek = randomDek()
    val catA = insertCategory(conn, dek, "社交", 1)
    val catB = insertCategory(conn, dek, "工作", 2)
    val catOther = insertCategory(conn, dek, "其他", 5)
    createEntry(conn, dek, "github", "me@x.com", "p1", "https://gh", "n1", catA)
    createEntry(conn, dek, "gitlab", "me@x.com", "p2", "https://gl", "n2", catA)
    createEntry(conn, dek, "work-vpn", "admin", "p3", "https://vpn", "n3", catB)
    createEntry(conn, dek, "other-bank", "u", "p4", "https://bk", "n4", catOther)

    checkThat(listEntriesMasked(conn, dek).size == 4) { "默认列表应返回 4 条" }
    checkThat(listEntriesMasked(conn, dek, search = "git").size == 2) { "搜索 'git' 应命中 2 条" }
    checkThat(listEntriesMasked(conn, dek, search = "me@x.com").size == 2) { "搜索用户名应命中 2 条" }
    val asc = listEntriesMasked(conn, dek, sortBy = SortKey.NAME_ASC)
    checkThat(asc.map { it.name } == listOf("github", "gitlab", "other-bank", "work-vpn")) { "NAME_ASC 错误: ${asc.map { it.name }}" }
    checkThat(listEntriesMasked(conn, dek, sortBy = SortKey.NAME_DESC).first().name == "work-vpn") { "NAME_DESC 错误" }
    checkThat(listEntriesMasked(conn, dek, categoryId = catA).all { it.categoryName == "社交" }) { "分类过滤错误" }
    checkThat(listEntriesMasked(conn, dek, limit = 2, offset = 1)[0].name == "gitlab") { "分页错误" }
    val catAsc = listEntriesMasked(conn, dek, sortBy = SortKey.CATEGORY_ASC)
    checkThat(catAsc.map { it.categoryName } == listOf("社交", "社交", "工作", "其他")) { "CATEGORY_ASC 错误: ${catAsc.map { it.categoryName }}" }
}

fun checkNoPlaintextMetadata(conn: java.sql.Connection) {
    // v2 安全属性：库文件里搜不到条目名/用户名/分类名的明文。
    val dek = randomDek()
    val cat = insertCategory(conn, dek, "公司VPN", 0)
    createEntry(conn, dek, "vpngate", "s3cr3t-user", "pw", "https://x", "note-body", cat)
    // 明文列必须为空
    jdbcQuery(conn, "SELECT name, username FROM password_entries") { rs ->
        checkThat(rs.getString(1) == "" && rs.getString(2) == "") { "v2 条目明文列必须为空" }
    }
    jdbcQuery(conn, "SELECT name FROM categories") { rs -> checkThat(rs.getString(1) == "") { "v2 分类明文列必须为空" } }
    // 整库转储（sqlite 支持 hex/全文，这里逐列取 blob 拼字节）里不得含敏感明文子串
    val dump = StringBuilder()
    jdbcQuery(conn, "SELECT secret_blob FROM password_entries") { rs -> dump.append(rs.getBytes(1).joinToString("") { "%02x".format(it) }) }
    jdbcQuery(conn, "SELECT name_blob FROM categories") { rs -> dump.append(rs.getBytes(1)?.joinToString("") { "%02x".format(it) } ?: "") }
    val dumpBytes = dump.toString()
    for (needle in listOf("vpngate", "s3cr3t-user", "公司VPN", "pw", "note-body")) {
        val hex = needle.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        checkThat(!dumpBytes.contains(hex)) { "密文转储中不应含明文 '$needle'（GCM 应已加密）" }
    }
}

fun checkCreateGetUpdateDeleteEntry(conn: java.sql.Connection) {
    val dek = randomDek()
    val catId = insertCategory(conn, dek, "支付", 0)
    val id = createEntry(conn, dek, "bank", "u1", "super-secret", "https://bank", "note", catId)
    checkThat(id > 0) { "createEntry 应返回正 id" }
    var rawBlob: ByteArray? = null
    jdbcQuery(conn, "SELECT secret_blob FROM password_entries WHERE id = ?", arrayOf(id.toString())) { rs -> rawBlob = rs.getBytes(1) }
    checkThat(rawBlob != null && !rawBlob!!.contentEquals("super-secret".toByteArray())) { "密文不应等于明文" }
    val got = getEntryDecrypted(conn, dek, id)
    checkThat(got != null && got.password == "super-secret" && got.name == "bank" && got.username == "u1") { "getEntry 应解密回填全字段" }
    var threw = false
    try { getEntryDecrypted(conn, randomDek(), id) } catch (e: VaultException) { threw = true }
    checkThat(threw) { "非活跃 DEK 解密应抛 VaultException" }
    checkThat(updateEntryById(conn, dek, id, "bank", "u1", "new-secret", "https://bank2", "note2", catId)) { "updateEntry 应成功" }
    checkThat(getEntryDecrypted(conn, dek, id)!!.password == "new-secret") { "update 后密码应更新" }
    checkThat(softDeleteEntry(conn, id)) { "deleteEntry 应成功" }
    checkThat(listEntriesMasked(conn, dek).none { it.id == id }) { "软删后列表不应含该条" }
}

fun checkExtrasRoundTrip(conn: java.sql.Connection) {
    val dek = randomDek()
    val cat = insertCategory(conn, dek, "工具", 0)
    val id = createEntry(conn, dek, "note", "u", "p", "w", "n", cat, listOf(ExtraField("邮箱", "a@b.c"), ExtraField("手机", "138")))
    val got = getEntryDecrypted(conn, dek, id)!!
    checkThat(got.extras == listOf(ExtraField("邮箱", "a@b.c"), ExtraField("手机", "138"))) { "extras 往返失败: ${got.extras}" }
}

fun checkCategoryDao(conn: java.sql.Connection) {
    val dek = randomDek()
    val otherId = insertCategory(conn, dek, "其他", 5)
    checkThat(!deleteCategory(conn, dek, otherId)) { "'其他' 不可删" }
    val workId = insertCategory(conn, dek, "工作", 2)
    val eid = createEntry(conn, dek, "e1", "u", "p", "w", "n", workId)
    checkThat(listCategoriesWithCount(conn, dek).first { it.id == workId }.entryCount == 1) { "工作类计数应为 1" }
    checkThat(!deleteCategory(conn, dek, workId)) { "非空分类不可删" }
    softDeleteEntry(conn, eid)
    checkThat(deleteCategory(conn, dek, workId)) { "清空后可删" }
}

fun checkSeedCategories(conn: java.sql.Connection) {
    val dek = randomDek()
    seedDefaultsIfEmpty(conn, dek)
    val cats = listCategoriesWithCount(conn, dek)
    checkThat(cats.map { it.name } == listOf("支付", "社交", "工作", "娱乐", "邮箱", "其他")) { "种子分类错误: ${cats.map { it.name }}" }
    seedDefaultsIfEmpty(conn, dek)
    checkThat(listCategoriesWithCount(conn, dek).size == 6) { "重复 seed 不应新增" }
}

fun checkChangeMasterPassword(conn: java.sql.Connection) {
    val salt = randomSalt(); val kek1 = deriveKey("pw1", salt, DEFAULT_KDF); val dek = randomDek()
    writeInitialization(conn, salt, wrapKey(kek1, dek), makeVerifier(kek1), null); zeroBytes(kek1)
    val dek1 = unwrapKey(deriveKek(conn, "pw1")!!, AeadBlob.fromBytes(readWrappedDek(conn)!!))
    val catId = insertCategory(conn, dek1, "社交", 1)
    val eid = createEntry(conn, dek1, "tw", "u", "pw-old", "w", "n", catId)
    checkThat(changeMasterPassword(conn, "pw1", "pw2")) { "改主密码应成功" }
    checkThat(deriveKek(conn, "pw1") == null) { "旧密码应解锁失败" }
    val dek2 = unwrapKey(deriveKek(conn, "pw2")!!, AeadBlob.fromBytes(readWrappedDek(conn)!!))
    assertBytesEq(dek1, dek2, "改主密码后 DEK 不变")
    checkThat(getEntryDecrypted(conn, dek2, eid)!!.password == "pw-old") { "新密码应仍能解密原条目" }
}

fun checkExportImport(conn: java.sql.Connection) {
    val dek = randomDek()
    val catA = insertCategory(conn, dek, "社交", 1)
    val catB = insertCategory(conn, dek, "工作", 2)
    createEntry(conn, dek, "github", "me@x.com", "gh-pw", "https://gh", "n1", catA)
    createEntry(conn, dek, "jira", "me@x.com", "ji-pw", "https://ji", "n2", catB)
    val f1 = exportVault(conn, dek, "export-pw")
    val conn2 = DriverManager.getConnection("jdbc:sqlite::memory:")
    try {
        execDdl(conn2, VAULT_SCHEMA_STATEMENTS)
        val report = importVault(conn2, dek, f1, "export-pw")
        checkThat(report.entriesAdded == 2 && report.categoriesAdded == 2) { "导入应新增 2 条 2 分类，实 $report" }
        val rows = listEntriesMasked(conn2, dek)
        checkThat(rows.size == 2) { "导入后应 2 条" }
        checkThat(getEntryDecrypted(conn2, dek, rows.first { it.name == "github" }.id)!!.password == "gh-pw") { "导入 github 密码应一致" }
        var threw = false
        try { importVault(conn2, dek, f1, "wrong-pw") } catch (e: WrongPasswordException) { threw = true }
        checkThat(threw) { "错误导出密码应抛 WrongPasswordException" }
    } finally { conn2.close() }
}

fun checkExportImportMasterPassword(conn: java.sql.Connection) {
    val mp = "master-pw"; val salt = randomSalt(); val kek = deriveKey(mp, salt, DEFAULT_KDF); val dek = randomDek()
    writeInitialization(conn, salt, wrapKey(kek, dek), makeVerifier(kek), null); zeroBytes(kek)
    val catA = insertCategory(conn, dek, "社交", 1)
    createEntry(conn, dek, "github", "me@x.com", "gh-pw", "https://gh", "n1", catA)
    val f = exportVault(conn, dek, mp, useMasterPassword = true)
    assertBytesEq(parseVaultHeader(f).exportSalt, salt, "主密码导出文件头 salt 应等于登录 kdf_salt")
    val conn2 = DriverManager.getConnection("jdbc:sqlite::memory:")
    try {
        execDdl(conn2, VAULT_SCHEMA_STATEMENTS)
        checkThat(importVault(conn2, dek, f, mp).entriesAdded == 1) { "主密码导入应新增 1 条" }
        checkThat(listEntriesMasked(conn2, dek)[0].name == "github") { "主密码导入应含 github" }
    } finally { conn2.close() }
}

// v1→v2 迁移：建 v1 库（明文列 + v1 secret JSON + 明文分类名），跑 DDL 迁移 + 数据迁移，验证 v2 读取正确且明文清空。
private val VAULT_SCHEMA_V1 = listOf(
    "CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX uq_categories_name ON categories(name)",
    "CREATE TABLE password_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, username TEXT NOT NULL DEFAULT '', secret_blob BLOB NOT NULL, category_id INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0, FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL)",
    "CREATE INDEX idx_entries_name ON password_entries(name)",
    "CREATE INDEX idx_entries_username ON password_entries(username)",
    "CREATE INDEX idx_entries_cat_name ON password_entries(category_id, name)",
    "CREATE INDEX idx_entries_deleted ON password_entries(is_deleted)",
    "CREATE TABLE app_settings (id INTEGER PRIMARY KEY CHECK (id = 1), initialized INTEGER NOT NULL DEFAULT 0, kdf_algo TEXT NOT NULL DEFAULT 'argon2id', kdf_memory_kb INTEGER NOT NULL DEFAULT 32768, kdf_iterations INTEGER NOT NULL DEFAULT 2, kdf_parallelism INTEGER NOT NULL DEFAULT 1, kdf_salt BLOB NOT NULL, wrapped_dek BLOB NOT NULL, verifier BLOB NOT NULL, wrapped_dek_biometric BLOB, auto_lock_timeout_sec INTEGER NOT NULL DEFAULT 60, clipboard_clear_delay_sec INTEGER NOT NULL DEFAULT 30, theme TEXT NOT NULL DEFAULT 'system', biometric_enabled INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
)

fun checkV1ToV2Migration() {
    val c = DriverManager.getConnection("jdbc:sqlite::memory:")
    try {
        execDdl(c, VAULT_SCHEMA_V1)
        val dek = randomDek()
        // v1 写入：明文 name/username + v1 secret JSON + 明文分类名
        val catId = insertReturnId(c, "INSERT INTO categories (name, sort_order, created_at) VALUES ('邮箱', 4, ?)") { it.setLong(1, nowMillis()) }
        insertReturnId(
            c, "INSERT INTO password_entries (name, username, secret_blob, category_id, created_at, updated_at, is_deleted) VALUES ('gmail', 'me@gmail.com', ?, ?, ?, ?, 0)"
        ) { ps ->
            ps.setBytes(1, encryptAesGcm(dek, jsonEncode(mapOf("password" to "g-pw", "website" to "https://gmail", "notes" to "n")).toByteArray(Charsets.UTF_8)).toBytes())
            ps.setLong(2, catId); val now = nowMillis(); ps.setLong(3, now); ps.setLong(4, now)
        }
        // DDL 迁移（结构）
        execDdl(c, VAULT_MIGRATE_V1_TO_V2)
        // 数据迁移（需 DEK）
        migrateDataV1toV2(c, dek)
        // 迁移后：v2 读取正确
        val rows = listEntriesMasked(c, dek)
        checkThat(rows.size == 1 && rows[0].name == "gmail" && rows[0].username == "me@gmail.com") { "迁移后条目 name/username 应保留: ${rows.map { it.name to it.username }}" }
        val detail = getEntryDecrypted(c, dek, rows[0].id)!!
        checkThat(detail.password == "g-pw" && detail.categoryName == "邮箱") { "迁移后 secret/分类名应正确: pw=${detail.password} cat=${detail.categoryName}" }
        // 明文列已清空
        jdbcQuery(c, "SELECT name, username FROM password_entries") { rs -> checkThat(rs.getString(1) == "" && rs.getString(2) == "") { "迁移后条目明文列应清空" } }
        jdbcQuery(c, "SELECT name, name_blob FROM categories") { rs -> checkThat(rs.getString(1) == "" && rs.getBytes(2) != null) { "迁移后分类明文列应清空且 name_blob 生成" } }
        // 幂等：再跑一次数据迁移不应改变结果
        migrateDataV1toV2(c, dek)
        checkThat(listEntriesMasked(c, dek).size == 1 && listEntriesMasked(c, dek)[0].name == "gmail") { "迁移应幂等" }
        // 迁移扫描守卫：迁移完成后计数应为 0
        jdbcQuery(c, SQL_COUNT_UNMIGRATED_ENTRIES) { rs -> checkThat(rs.getInt(1) == 0) { "迁移后未迁移条目计数应为 0" } }
        jdbcQuery(c, SQL_COUNT_UNMIGRATED_CATEGORIES) { rs -> checkThat(rs.getInt(1) == 0) { "迁移后未迁移分类计数应为 0" } }
    } finally { c.close() }
}

fun checkGcmTamper() {
    val key = randomDek(); val blob = encryptAesGcm(key, "top-secret".toByteArray())
    var threw = false
    try { decryptAesGcm(key, AeadBlob(blob.nonce.copyOf(), blob.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })) } catch (e: VaultException) { threw = true }
    checkThat(threw) { "GCM 篡改密文必须抛" }
}

fun checkMalformedVaultHeader() {
    val salt = randomSalt(); val key = deriveKey("export-pw", salt, DEFAULT_KDF)
    val file = serializeVaultFile(DEFAULT_KDF, salt, encryptAesGcm(key, "payload-bytes".toByteArray()))
    val saltT = file.copyOf().also { it[15] = 0xFF.toByte(); it[16] = 0xFF.toByte() }
    var threwSalt = false
    try { parseVaultHeader(saltT) } catch (e: WrongPasswordException) { threwSalt = true }
    checkThat(threwSalt) { "saltLen 越界应抛 WrongPasswordException" }
}

fun checkVaultSession() {
    val s = VaultSession()
    checkThat(s.isLocked()) { "初始锁定" }
    val dek = randomDek(); s.unlock(dek)
    checkThat(!s.isLocked() && s.getActiveDek()!!.contentEquals(dek)) { "unlock 可取回" }
    s.lock(); checkThat(s.isLocked() && s.getActiveDek() == null) { "lock 清零" }
}

fun withFreshDb(block: (java.sql.Connection) -> Unit) {
    val c = DriverManager.getConnection("jdbc:sqlite::memory:")
    try { execDdl(c, VAULT_SCHEMA_STATEMENTS); block(c) } finally { c.close() }
}

fun main(args: Array<String>) {
    vaultCryptoSelfTest(); vaultFormatSelfTest(); vaultMergeSelfTest(); jsonSelfTest(); vaultSessionSelfTest()
    entryBlobSelfTest(); querySelfTest()
    Class.forName("org.sqlite.JDBC")
    withFreshDb { checkListEntries(it) }
    withFreshDb { checkNoPlaintextMetadata(it) }
    withFreshDb { checkCreateGetUpdateDeleteEntry(it) }
    withFreshDb { checkExtrasRoundTrip(it) }
    withFreshDb { checkCategoryDao(it) }
    withFreshDb { checkSeedCategories(it) }
    withFreshDb { checkChangeMasterPassword(it) }
    withFreshDb { checkExportImport(it) }
    withFreshDb { checkExportImportMasterPassword(it) }
    checkV1ToV2Migration()
    checkGcmTamper(); checkMalformedVaultHeader(); checkVaultSession()
    println("ALL CONTRACT CHECKS OK")
}
