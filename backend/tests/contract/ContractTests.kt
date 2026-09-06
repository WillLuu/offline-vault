package vault

// ============================================================================
// 契约测试套件（tests/contract，契约 api-contract.md）。无测试框架（纯 main/assert），
// 可在普通 JVM 直接执行（不依赖 Android/UI/Keystore）。
//
// 架构纪律（副作用隔离）：
//  - 纯模块（VaultCrypto / VaultFormat / VaultMerge / SqlBuilders / Json / Schema / VaultSession）
//    零 Android 依赖，已在 JVM 上由 *SelfTest() 覆盖；本套件先调用它们（自检即文档）。
//  - Android 胶水（DAO/UnlockManager/BiometricKeystore）在 Android 编译，无法在裸 JVM 跑；
//    其"契约可观察行为"由本套件用【同一份 SQL（SqlBuilders 生成的读取 SQL / DAO 写入 SQL 的逐字副本）
//    + 同一份加密原语（VaultCrypto）】在 sqlite-jdbc 上重放验证。DAO 仅是这些 SQL+加密的机械封装，
//    故验证 SQL+加密即验证契约方法语义。
//  - 生物识别通道（BiometricKeystore）依赖 Android Keystore Provider，裸 JVM 无，故不在此执行；
//    其行为由 Android Instrumented 测试覆盖（见 README 待确认项）。
//
// 运行：见 backend/build_and_test.sh（或 README "契约测试运行方式"）。
// ============================================================================

import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

// JDBC ResultSet 无 isNull(int)（那是 wasNull() 的误用）；用 getObject()==null 判定 SQL NULL，
// 封装为可读辅助，避免重复的 null 判定。
fun ResultSet.strOrNull(col: Int): String? = if (getObject(col) == null) null else getString(col)
fun ResultSet.longOrNull(col: Int): Long? = if (getObject(col) == null) null else getLong(col)

// ---------------------- JDBC 轻量封装（_CLOSEABLE 手动管理，避免引入 Android/额外依赖） ----------------------

fun execDdl(conn: java.sql.Connection, stmts: List<String>) {
    val st = conn.createStatement()
    try {
        for (s in stmts) st.execute(s)
    } finally {
        st.close()
    }
}

// INSERT 并返回自增 id。
fun insertReturnId(conn: java.sql.Connection, sql: String, bind: (java.sql.PreparedStatement) -> Unit): Long {
    val ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)
    try {
        bind(ps)
        ps.executeUpdate()
        val gk = ps.generatedKeys
        try {
            gk.next()
            return gk.getLong(1)
        } finally {
            gk.close()
        }
    } finally {
        ps.close()
    }
}

// UPDATE/DELETE，返回受影响行数。
fun jdbcUpdate(conn: java.sql.Connection, sql: String, bind: (java.sql.PreparedStatement) -> Unit): Int {
    val ps = conn.prepareStatement(sql)
    try {
        bind(ps)
        return ps.executeUpdate()
    } finally {
        ps.close()
    }
}

// 查询，逐行回调。
fun jdbcQuery(conn: java.sql.Connection, sql: String, args: Array<String> = emptyArray(), each: (java.sql.ResultSet) -> Unit) {
    val ps = conn.prepareStatement(sql)
    try {
        args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
        val rs = ps.executeQuery()
        try {
            while (rs.next()) each(rs)
        } finally {
            rs.close()
        }
    } finally {
        ps.close()
    }
}

// ---------------------- 与 DAO/Store 逐字一致的 DB 操作（harness 镜像） ----------------------

data class MaskedRow(
    val id: Long, val name: String, val username: String,
    val categoryName: String?, val createdAt: Long, val updatedAt: Long, val isDeleted: Boolean
)

// 镜像 PasswordEntryDao.listEntries（掩码：不解密 secret_blob；复用 SqlBuilders 生成的同一份 SQL）。
fun listEntriesMasked(
    conn: java.sql.Connection,
    search: String? = null,
    sortBy: SortKey = SortKey.NAME_ASC,
    categoryId: Long? = null,
    limit: Int = 100,
    offset: Int = 0
): List<MaskedRow> {
    val (sql, args) = buildListEntriesQuery(search, sortBy, categoryId, limit, offset)
    val out = mutableListOf<MaskedRow>()
    jdbcQuery(conn, sql, args) { rs ->
        val idxCat = rs.findColumn("category_name")
        val catName = rs.strOrNull(idxCat)
        val idxCatId = rs.findColumn("category_id")
        val catId = rs.longOrNull(idxCatId)
        out.add(
            MaskedRow(
                id = rs.getLong(rs.findColumn("id")),
                name = rs.getString(rs.findColumn("name")),
                username = rs.getString(rs.findColumn("username")),
                categoryName = catName,
                createdAt = rs.getLong(rs.findColumn("created_at")),
                updatedAt = rs.getLong(rs.findColumn("updated_at")),
                isDeleted = rs.getInt(rs.findColumn("is_deleted")) == 1
            )
        )
    }
    return out
}

// 镜像 PasswordEntryDao.getEntry（解密 secret_blob 回填 password/website/notes）。
fun getEntryDecrypted(conn: java.sql.Connection, dek: ByteArray, id: Long): Pair<MaskedRow, SecretPlain>? {
    var result: Pair<MaskedRow, SecretPlain>? = null
    jdbcQuery(
        conn,
        "SELECT id,name,username,secret_blob,category_id,created_at,updated_at,is_deleted " +
            "FROM password_entries WHERE id = ? AND is_deleted = 0",
        arrayOf(id.toString())
    ) { rs ->
        val idxCat = rs.findColumn("category_id")
        val catId = rs.longOrNull(idxCat)
        val row = MaskedRow(
            id = rs.getLong(rs.findColumn("id")),
            name = rs.getString(rs.findColumn("name")),
            username = rs.getString(rs.findColumn("username")),
            categoryName = null,
            createdAt = rs.getLong(rs.findColumn("created_at")),
            updatedAt = rs.getLong(rs.findColumn("updated_at")),
            isDeleted = rs.getInt(rs.findColumn("is_deleted")) == 1
        )
        val secret = decryptSecretBlob(dek, rs.getBytes(rs.findColumn("secret_blob")))
        result = row to secret
    }
    return result
}

// 镜像 PasswordEntryDao.createEntry：加密 {password,website,notes} -> secret_blob。
fun createEntry(
    conn: java.sql.Connection, dek: ByteArray,
    name: String, username: String, password: String, website: String, notes: String, categoryId: Long?
): Long {
    val sql = "INSERT INTO password_entries (name, username, secret_blob, category_id, created_at, updated_at, is_deleted) " +
        "VALUES (?, ?, ?, ?, ?, ?, 0)"
    return insertReturnId(conn, sql) { ps ->
        ps.setString(1, name)
        ps.setString(2, username)
        ps.setBytes(3, encryptAesGcm(dek, secretJson(password, website, notes)).toBytes())
        val now = nowMillis()
        if (categoryId == null) ps.setNull(4, Types.INTEGER) else ps.setLong(4, categoryId)
        ps.setLong(5, now)
        ps.setLong(6, now)
    }
}

// 镜像 PasswordEntryDao.updateEntry：重加密 secret_blob，刷新 updated_at。
fun updateEntryById(
    conn: java.sql.Connection, dek: ByteArray, id: Long,
    name: String, username: String, password: String, website: String, notes: String, categoryId: Long?
): Boolean {
    val sql = "UPDATE password_entries SET name = ?, username = ?, secret_blob = ?, category_id = ?, updated_at = ? " +
        "WHERE id = ? AND is_deleted = 0"
    val n = jdbcUpdate(conn, sql) { ps ->
        ps.setString(1, name)
        ps.setString(2, username)
        ps.setBytes(3, encryptAesGcm(dek, secretJson(password, website, notes)).toBytes())
        if (categoryId == null) ps.setNull(4, Types.INTEGER) else ps.setLong(4, categoryId)
        ps.setLong(5, nowMillis())
        ps.setLong(6, id)
    }
    return n > 0
}

// 镜像 PasswordEntryDao.deleteEntry：软删（is_deleted=1）。
fun softDeleteEntry(conn: java.sql.Connection, id: Long): Boolean {
    val n = jdbcUpdate(conn, "UPDATE password_entries SET is_deleted = 1, updated_at = ? WHERE id = ? AND is_deleted = 0") { ps ->
        ps.setLong(1, nowMillis())
        ps.setLong(2, id)
    }
    return n > 0
}

// 镜像 CategoryDao 写入 + 读取。
fun insertCategory(conn: java.sql.Connection, name: String, sortOrder: Int): Long {
    return insertReturnId(conn, "INSERT INTO categories (name, sort_order, created_at) VALUES (?, ?, ?)") { ps ->
        ps.setString(1, name)
        ps.setInt(2, sortOrder)
        ps.setLong(3, nowMillis())
    }
}

fun listCategoriesWithCount(conn: java.sql.Connection): List<CategoryRow> {
    val out = mutableListOf<CategoryRow>()
    jdbcQuery(conn, buildListCategoriesQuery()) { rs ->
        out.add(
            CategoryRow(
                id = rs.getLong(rs.findColumn("id")),
                name = rs.getString(rs.findColumn("name")),
                sortOrder = rs.getInt(rs.findColumn("sort_order")),
                createdAt = rs.getLong(rs.findColumn("created_at")),
                entryCount = rs.getInt(rs.findColumn("entry_count"))
            )
        )
    }
    return out
}

fun updateCategorySortOrder(conn: java.sql.Connection, id: Long, sortOrder: Int): Boolean {
    val n = jdbcUpdate(conn, "UPDATE categories SET sort_order = ? WHERE id = ?") { ps ->
        ps.setInt(1, sortOrder)
        ps.setLong(2, id)
    }
    return n > 0
}

// 镜像 CategoryDao.deleteCategory：其他(种子)不可删；非空不可删。
fun deleteCategory(conn: java.sql.Connection, id: Long): Boolean {
    var name: String? = null
    jdbcQuery(conn, "SELECT name FROM categories WHERE id = ?", arrayOf(id.toString())) { rs -> name = rs.getString(1) }
    if (name == "其他") return false
    var count = 0
    jdbcQuery(conn, "SELECT COUNT(*) FROM password_entries WHERE category_id = ? AND is_deleted = 0", arrayOf(id.toString())) { rs ->
        count = rs.getInt(1)
    }
    if (count > 0) return false
    val n = jdbcUpdate(conn, "DELETE FROM categories WHERE id = ?") { ps -> ps.setLong(1, id) }
    return n > 0
}

// 镜像 CategoryDao.seedDefaultsIfEmpty：categories 为空时写入 6 个种子分类（支付/社交/工作/娱乐/邮箱/其他）。
// 用于契约检查首次初始化必须产出种子分类（此前 glue 漏调用，已修）。
fun seedDefaultsIfEmpty(conn: java.sql.Connection) {
    var count = 0
    jdbcQuery(conn, "SELECT COUNT(*) FROM categories") { rs -> count = rs.getInt(1) }
    if (count > 0) return
    for ((name, order) in listOf(
        "支付" to 0, "社交" to 1, "工作" to 2, "娱乐" to 3, "邮箱" to 4, "其他" to 5
    )) {
        insertCategory(conn, name, order)
    }
}

// ---------------------- secret_blob 编解码（与 PasswordEntryDao 一致） ----------------------

fun secretJson(password: String, website: String, notes: String): ByteArray =
    jsonEncode(mapOf("password" to password, "website" to website, "notes" to notes)).toByteArray(Charsets.UTF_8)

fun decryptSecretBlob(dek: ByteArray, blob: ByteArray): SecretPlain {
    val json = String(decryptAesGcm(dek, AeadBlob.fromBytes(blob)), Charsets.UTF_8)
    val m = jsonDecode(json) as Map<*, *>
    return SecretPlain(m["password"] as String, m["website"] as String, m["notes"] as String)
}

// ---------------------- app_settings 镜像（AppSettingsStore） ----------------------

fun writeInitialization(
    conn: java.sql.Connection, salt: ByteArray, wrappedDek: AeadBlob, verifier: AeadBlob, bioWrapped: ByteArray?
) {
    val now = nowMillis()
    insertReturnId(
        conn,
        "INSERT INTO app_settings (id, initialized, kdf_algo, kdf_memory_kb, kdf_iterations, kdf_parallelism, " +
            "kdf_salt, wrapped_dek, verifier, wrapped_dek_biometric, biometric_enabled, " +
            "auto_lock_timeout_sec, clipboard_clear_delay_sec, theme, created_at, updated_at) " +
            "VALUES (1,1,'argon2id'," + DEFAULT_KDF.memoryKb + "," + DEFAULT_KDF.iterations + "," +
            DEFAULT_KDF.parallelism + ",?,?,?,?,?,60,30,'system',?,?)"
    ) { ps ->
        ps.setBytes(1, salt)
        ps.setBytes(2, wrappedDek.toBytes())
        ps.setBytes(3, verifier.toBytes())
        if (bioWrapped == null) ps.setNull(4, Types.BLOB) else ps.setBytes(4, bioWrapped)
        ps.setInt(5, if (bioWrapped != null) 1 else 0)
        ps.setLong(6, now)
        ps.setLong(7, now)
    }
}

fun readKdfAndVerifier(conn: java.sql.Connection): Triple<KdfParams, ByteArray, ByteArray>? {
    var r: Triple<KdfParams, ByteArray, ByteArray>? = null
    jdbcQuery(conn, "SELECT kdf_memory_kb, kdf_iterations, kdf_parallelism, kdf_salt, verifier FROM app_settings WHERE id = 1") { rs ->
        val params = KdfParams("argon2id", rs.getInt(1), rs.getInt(2), rs.getInt(3))
        r = Triple(params, rs.getBytes(4), rs.getBytes(5))
    }
    return r
}

fun readWrappedDek(conn: java.sql.Connection): ByteArray? {
    var r: ByteArray? = null
    jdbcQuery(conn, "SELECT wrapped_dek FROM app_settings WHERE id = 1") { rs -> r = rs.getBytes(1) }
    return r
}

// 镜像 AppSettingsStore.deriveKek：错误密码返回 null。
fun deriveKek(conn: java.sql.Connection, password: String): ByteArray? {
    val kv = readKdfAndVerifier(conn) ?: return null
    val kek = deriveKey(password, kv.second, kv.first)
    return if (checkVerifier(kek, AeadBlob.fromBytes(kv.third))) kek else null
}

// 镜像 AppSettingsStore.changeMasterPassword：校验旧 KEK -> 解包 DEK -> 新盐新 KEK -> 重包 DEK+verifier。DEK 不变。
fun changeMasterPassword(conn: java.sql.Connection, oldPassword: String, newPassword: String): Boolean {
    val kekOld = deriveKek(conn, oldPassword) ?: return false
    val dek = unwrapKey(kekOld, AeadBlob.fromBytes(readWrappedDek(conn)!!))
    val newSalt = randomSalt()
    val kekNew = deriveKey(newPassword, newSalt, DEFAULT_KDF)
    val newWrapped = wrapKey(kekNew, dek)
    val newVerifier = makeVerifier(kekNew)
    val n = jdbcUpdate(conn, "UPDATE app_settings SET kdf_salt = ?, wrapped_dek = ?, verifier = ?, updated_at = ? WHERE id = 1") { ps ->
        ps.setBytes(1, newSalt)
        ps.setBytes(2, newWrapped.toBytes())
        ps.setBytes(3, newVerifier.toBytes())
        ps.setLong(4, nowMillis())
    }
    zeroBytes(kekOld)
    zeroBytes(kekNew)
    return n > 0
}

// ---------------------- 导出/导入（VaultBackup 镜像，复用纯编解码+合并决策） ----------------------

fun readAllCategories(conn: java.sql.Connection): List<ExportCategory> {
    val out = mutableListOf<ExportCategory>()
    jdbcQuery(conn, "SELECT name, sort_order FROM categories") { rs ->
        out.add(ExportCategory(rs.getString(1), rs.getInt(2)))
    }
    return out
}

fun categoryNameById(conn: java.sql.Connection, id: Long): String? {
    var r: String? = null
    jdbcQuery(conn, "SELECT name FROM categories WHERE id = ?", arrayOf(id.toString())) { rs -> r = rs.getString(1) }
    return r
}

fun readAllEntriesWithSecret(conn: java.sql.Connection, dek: ByteArray): List<ExportEntry> {
    val out = mutableListOf<ExportEntry>()
    jdbcQuery(conn, "SELECT name, username, category_id, created_at, updated_at, secret_blob FROM password_entries WHERE is_deleted = 0"    ) { rs ->
        val idxCat = rs.findColumn("category_id")
        val catName = if (rs.getObject(idxCat) == null) "" else (categoryNameById(conn, rs.getLong(idxCat)) ?: "")
        val secret = decryptSecretBlob(dek, rs.getBytes(rs.findColumn("secret_blob")))
        out.add(
            ExportEntry(
                name = rs.getString(1),
                username = rs.getString(2),
                categoryName = catName,
                createdAt = rs.getLong(4),
                updatedAt = rs.getLong(5),
                secret = secret
            )
        )
    }
    return out
}

// 镜像 VaultBackup.exportVault：useMasterPassword=true 时用主密码经 Argon2id 派生 KEK（同源，复用 deriveKek/登录 salt），
//   文件头写登录 kdf_salt+params；false 时用独立 export_salt。导入端据 header 同源派生，用户只记一个主密码。
fun exportVault(conn: java.sql.Connection, dek: ByteArray, password: String, useMasterPassword: Boolean = false): ByteArray {
    val categories = readAllCategories(conn)
    val entries = readAllEntriesWithSecret(conn, dek)
    val json = encodeVaultPayload(buildExportPayload(categories, entries))
    if (useMasterPassword) {
        // 同源 KEK：复用 deriveKek（登录 salt/params + verifier 校验），禁止自研 Argon2。
        val kek = deriveKek(conn, password) ?: throw WrongPasswordException()
        val kv = readKdfAndVerifier(conn) ?: throw VaultException("未初始化：无法用主密码导出")
        val blob = encryptAesGcm(kek, json.toByteArray(Charsets.UTF_8))
        return serializeVaultFile(kv.first, kv.second, blob)
    }
    val exportSalt = randomSalt()
    val key = deriveKey(password, exportSalt, DEFAULT_KDF)
    val blob = encryptAesGcm(key, json.toByteArray(Charsets.UTF_8))
    return serializeVaultFile(DEFAULT_KDF, exportSalt, blob)
}

fun readCurrentEntryMetas(conn: java.sql.Connection): List<EntryMeta> {
    val out = mutableListOf<EntryMeta>()
    jdbcQuery(
        conn,
        "SELECT e.id, e.name, e.username, c.name AS category_name, e.updated_at " +
            "FROM password_entries e LEFT JOIN categories c ON e.category_id = c.id " +
            "WHERE e.is_deleted = 0"
    ) { rs ->
        val idxCat = rs.findColumn("category_name")
        out.add(
            EntryMeta(
                id = rs.getLong(1),
                name = rs.getString(2),
                username = rs.getString(3),
                categoryName = rs.strOrNull(idxCat),
                updatedAt = rs.getLong(5)
            )
        )
    }
    return out
}

// 镜像 VaultBackup.importVault 的合并应用（写 DB）。
fun applyMerge(conn: java.sql.Connection, dek: ByteArray, payload: VaultPayload): MergeReport {
    val currentCats = listCategoriesWithCount(conn)
    val currentEntries = readCurrentEntryMetas(conn)
    val catDec = decideCategoryMerge(currentCats, payload.categories)
    val entDec = decideEntryMerge(currentEntries, payload.entries)
    var catAdded = 0
    var catMerged = 0
    val nameToId = currentCats.associate { it.name to it.id }.toMutableMap()
    for ((_, imp) in catDec.merged) {
        updateCategorySortOrder(conn, nameToId[imp.name]!!, imp.sortOrder)
        catMerged++
    }
    for (imp in catDec.added) {
        val id = insertCategory(conn, imp.name, imp.sortOrder)
        nameToId[imp.name] = id
        catAdded++
    }
    var added = 0
    var updated = 0
    val keyToId = currentEntries.associate { Triple(it.name, it.username, it.categoryName ?: "") to it.id }.toMutableMap()
    for (imp in entDec.added) {
        createEntry(conn, dek, imp.name, imp.username, imp.secret.password, imp.secret.website, imp.secret.notes, nameToId[imp.categoryName])
        added++
    }
    for ((_, imp) in entDec.updated) {
        val id = keyToId[Triple(imp.name, imp.username, imp.categoryName)] ?: return@applyMerge MergeReport(catAdded, catMerged, added, updated, entDec.skipped)
        updateEntryById(conn, dek, id, imp.name, imp.username, imp.secret.password, imp.secret.website, imp.secret.notes, nameToId[imp.categoryName])
        updated++
    }
    return MergeReport(catAdded, catMerged, added, updated, entDec.skipped)
}

// 镜像 VaultBackup.importVault：解密文件 -> 合并 -> 错误密码抛 WrongPasswordException。
fun importVault(conn: java.sql.Connection, dek: ByteArray, file: ByteArray, password: String): MergeReport {
    val header = parseVaultHeader(file)
    val key = deriveKey(password, header.exportSalt, header.kdfParams)
    val json = try {
        String(decryptAesGcm(key, header.blob), Charsets.UTF_8)
    } catch (e: VaultException) {
        throw WrongPasswordException()
    }
    val payload = decodeVaultPayload(json)
    return applyMerge(conn, dek, payload)
}

// ============================================================================
// 契约检查（逐条对应 api-contract.md 方法）
// ============================================================================

fun checkListEntries(conn: java.sql.Connection) {
    val dek = randomDek()
    val catA = insertCategory(conn, "社交", 1)
    val catB = insertCategory(conn, "工作", 2)
    val catOther = insertCategory(conn, "其他", 5)
    createEntry(conn, dek, "github", "me@x.com", "p1", "https://gh", "n1", catA)
    createEntry(conn, dek, "gitlab", "me@x.com", "p2", "https://gl", "n2", catA)
    createEntry(conn, dek, "work-vpn", "admin", "p3", "https://vpn", "n3", catB)
    createEntry(conn, dek, "other-bank", "u", "p4", "https://bk", "n4", catOther)

    // 默认列表（NAME_ASC）掩码：含全部 4 条，password 不出现在结果结构里
    val all = listEntriesMasked(conn)
    checkThat(all.size == 4) { "默认列表应返回 4 条" }
    checkThat(all.all { it.categoryName != null }) { "掩码行应带分类名" }

    // 搜索 name LIKE
    val byName = listEntriesMasked(conn, search = "git")
    checkThat(byName.size == 2 && byName.all { it.name.contains("git") }) { "搜索 'git' 应命中 github/gitlab，实 ${byName.map { it.name }}" }
    // 搜索 username LIKE
    val byUser = listEntriesMasked(conn, search = "me@x.com")
    checkThat(byUser.size == 2) { "搜索用户名 'me@x.com' 应命中 2 条" }

    // 排序 NAME_ASC / NAME_DESC（二进制序：github < gitlab）
    val asc = listEntriesMasked(conn, sortBy = SortKey.NAME_ASC)
    checkThat(asc.map { it.name } == listOf("github", "gitlab", "other-bank", "work-vpn")) { "NAME_ASC 顺序错误: ${asc.map { it.name }}" }
    val desc = listEntriesMasked(conn, sortBy = SortKey.NAME_DESC)
    checkThat(desc.map { it.name } == listOf("work-vpn", "other-bank", "gitlab", "github")) { "NAME_DESC 顺序错误: ${desc.map { it.name }}" }

    // 分类过滤
    val onlyA = listEntriesMasked(conn, categoryId = catA)
    checkThat(onlyA.size == 2 && onlyA.all { it.categoryName == "社交" }) { "分类过滤应仅返回社交类 2 条" }

    // 分页 limit=2 offset=1（默认序 github,gitlab,other-bank,work-vpn -> [gitlab, other-bank]）
    val page = listEntriesMasked(conn, limit = 2, offset = 1)
    checkThat(page.size == 2 && page[0].name == "gitlab") { "分页 limit=2 offset=1 首项应为 gitlab，实 ${page.map { it.name }}" }

    // CATEGORY_ASC 按分类 sort_order 排序（catA=1 社交, catB=2 工作, catOther=3 其他）
    val catAsc = listEntriesMasked(conn, sortBy = SortKey.CATEGORY_ASC)
    checkThat(catAsc.map { it.categoryName } == listOf("社交", "社交", "工作", "其他")) { "CATEGORY_ASC 顺序错误: ${catAsc.map { it.categoryName }}" }
}

fun checkCreateGetUpdateDeleteEntry(conn: java.sql.Connection) {
    val dek = randomDek()
    val catId = insertCategory(conn, "支付", 0)
    val id = createEntry(conn, dek, "bank", "u1", "super-secret", "https://bank", "note", catId)
    checkThat(id > 0) { "createEntry 应返回正 id" }

    // 密文 ≠ 明文：长度含 GCM tag，且不等于明文
    var rawBlob: ByteArray? = null
    jdbcQuery(conn, "SELECT secret_blob FROM password_entries WHERE id = ?", arrayOf(id.toString())) { rs -> rawBlob = rs.getBytes(1) }
    checkThat(rawBlob != null && rawBlob!!.size > "super-secret".length) { "密文长度应 > 明文（含 GCM tag）" }
    checkThat(!rawBlob!!.contentEquals("super-secret".toByteArray())) { "密文不应等于明文" }

    // getEntry 解密回填
    val got = getEntryDecrypted(conn, dek, id)
    checkThat(got != null) { "getEntry 应返回条目" }
    checkThat(got!!.second.password == "super-secret") { "getEntry 应解密出正确密码" }
    checkThat(got.second.website == "https://bank") { "getEntry website 错误" }

    // 锁定/无正确 DEK 时解密必须抛（DAO 拒绝：getDek()==null 即无法解密）
    val wrongDek = randomDek()
    var lockedThrew = false
    try { getEntryDecrypted(conn, wrongDek, id) } catch (e: VaultException) { lockedThrew = true }
    checkThat(lockedThrew) { "非活跃 DEK 解密应抛 VaultException（模拟锁定态拒绝）" }

    // update
    checkThat(updateEntryById(conn, dek, id, "bank", "u1", "new-secret", "https://bank2", "note2", catId)) { "updateEntry 应成功" }
    val got2 = getEntryDecrypted(conn, dek, id)
    checkThat(got2!!.second.password == "new-secret") { "updateEntry 后密码应更新" }

    // delete（软删）
    checkThat(softDeleteEntry(conn, id)) { "deleteEntry 应成功" }
    checkThat(listEntriesMasked(conn).none { it.id == id }) { "软删后列表不应返回该条目" }
    var isDel = -1
    jdbcQuery(conn, "SELECT is_deleted FROM password_entries WHERE id = ?", arrayOf(id.toString())) { rs -> isDel = rs.getInt(1) }
    checkThat(isDel == 1) { "软删后 is_deleted 应为 1" }
}

fun checkCategoryDao(conn: java.sql.Connection) {
    val otherId = insertCategory(conn, "其他", 5)
    checkThat(!deleteCategory(conn, otherId)) { "'其他' 种子分类不可删" }

    val workId = insertCategory(conn, "工作", 2)
    val dek = randomDek()
    createEntry(conn, dek, "e1", "u", "p", "w", "n", workId)

    val cats = listCategoriesWithCount(conn)
    val work = cats.first { it.id == workId }
    checkThat(work.entryCount == 1) { "工作类 entryCount 应为 1（单条 JOIN 防 N+1），实 ${work.entryCount}" }
    checkThat(!deleteCategory(conn, workId)) { "非空分类不可删" }

    // 软删条目后，分类可删
    var eid = -1L
    jdbcQuery(conn, "SELECT id FROM password_entries WHERE name = 'e1'") { rs -> eid = rs.getLong(1) }
    softDeleteEntry(conn, eid)
    checkThat(deleteCategory(conn, workId)) { "条目清空后可删分类" }
}

// 种子分类：首次初始化必须写入 6 个种子分类（"其他"不可删），且重复 seed 不重复插入。
fun checkSeedCategories(conn: java.sql.Connection) {
    seedDefaultsIfEmpty(conn)
    val cats = listCategoriesWithCount(conn)
    checkThat(cats.size == 6) { "应有 6 个种子分类，实 ${cats.size}" }
    checkThat(cats.map { it.name } == listOf("支付", "社交", "工作", "娱乐", "邮箱", "其他")) {
        "种子分类名称/顺序错误: ${cats.map { it.name }}"
    }
    // "其他" 不可删
    val other = cats.first { it.name == "其他" }
    checkThat(!deleteCategory(conn, other.id)) { "种子分类 其他 必须不可删" }
    // 重复 seed 不应新增
    seedDefaultsIfEmpty(conn)
    checkThat(listCategoriesWithCount(conn).size == 6) { "重复 seed 不应新增分类" }
}

fun checkChangeMasterPassword(conn: java.sql.Connection) {
    // 初始化（密码 pw1）
    val salt = randomSalt()
    val kek1 = deriveKey("pw1", salt, DEFAULT_KDF)
    val dek = randomDek()
    val wrapped = wrapKey(kek1, dek)
    val verifier = makeVerifier(kek1)
    writeInitialization(conn, salt, wrapped, verifier, null)
    zeroBytes(kek1)

    // 用 pw1 解锁拿到 DEK，建条目，记录 secret_blob 字节
    val dek1 = unwrapKey(deriveKek(conn, "pw1")!!, AeadBlob.fromBytes(readWrappedDek(conn)!!))
    val catId = insertCategory(conn, "社交", 1)
    val eid = createEntry(conn, dek1, "tw", "u", "pw-old", "w", "n", catId)
    var beforeBlob: ByteArray? = null
    jdbcQuery(conn, "SELECT secret_blob FROM password_entries WHERE id = ?", arrayOf(eid.toString())) { rs -> beforeBlob = rs.getBytes(1) }

    // 改主密码 pw1 -> pw2
    checkThat(changeMasterPassword(conn, "pw1", "pw2")) { "changeMasterPassword 应成功" }
    // 旧密码解锁失败
    checkThat(deriveKek(conn, "pw1") == null) { "旧密码 pw1 应解锁失败" }
    // 新密码解锁成功，且 DEK 不变
    val dek2 = unwrapKey(deriveKek(conn, "pw2")!!, AeadBlob.fromBytes(readWrappedDek(conn)!!))
    assertBytesEq(dek1, dek2, "改主密码后 DEK 应不变")
    // secret_blob 字节未变（未重加密）
    var afterBlob: ByteArray? = null
    jdbcQuery(conn, "SELECT secret_blob FROM password_entries WHERE id = ?", arrayOf(eid.toString())) { rs -> afterBlob = rs.getBytes(1) }
    assertBytesEq(beforeBlob!!, afterBlob!!, "改主密码后 secret_blob 字节应不变（DEK 不变）")
    // 新密码可解密原条目
    val got = getEntryDecrypted(conn, dek2, eid)
    checkThat(got!!.second.password == "pw-old") { "新密码应仍能解密原条目" }
}

fun checkExportImport(conn: java.sql.Connection) {
    val dek = randomDek()
    val catA = insertCategory(conn, "社交", 1)
    val catB = insertCategory(conn, "工作", 2)
    createEntry(conn, dek, "github", "me@x.com", "gh-pw", "https://gh", "n1", catA)
    createEntry(conn, dek, "jira", "me@x.com", "ji-pw", "https://ji", "n2", catB)

    // 导出（独立 export_salt；两次应不同 nonce）
    val f1 = exportVault(conn, dek, "export-pw")
    val f2 = exportVault(conn, dek, "export-pw")
    checkThat(!f1.contentEquals(f2)) { "两次导出应使用不同 nonce/salt，文件不应相同" }

    // 导入到全新库（同导出密码）
    val conn2 = DriverManager.getConnection("jdbc:sqlite::memory:")
    try {
        execDdl(conn2, VAULT_SCHEMA_STATEMENTS)
        val report = importVault(conn2, dek, f1, "export-pw")
        checkThat(report.entriesAdded == 2) { "导入应新增 2 条条目，实 ${report.entriesAdded}" }
        checkThat(report.categoriesAdded == 2) { "导入应新增 2 个分类，实 ${report.categoriesAdded}" }
        val rows = listEntriesMasked(conn2)
        checkThat(rows.size == 2) { "导入后应有 2 条，实 ${rows.size}" }
        val gh = getEntryDecrypted(conn2, dek, rows.first { it.name == "github" }.id)
        checkThat(gh!!.second.password == "gh-pw") { "导入后 github 密码应一致" }
        val ji = getEntryDecrypted(conn2, dek, rows.first { it.name == "jira" }.id)
        checkThat(ji!!.second.password == "ji-pw") { "导入后 jira 密码应一致" }

        // 错误导出密码 -> WRONG_PASSWORD
        var threw = false
        try { importVault(conn2, dek, f1, "wrong-pw") } catch (e: WrongPasswordException) { threw = true }
        checkThat(threw) { "错误导出密码应抛 WrongPasswordException" }
    } finally {
        conn2.close()
    }
}

// ② 用主密码导出（useMasterPassword=true）：KEK 须经 Argon2id 由主密码派生（同源），文件头写登录 kdf_salt，
//   用户只记一个主密码即可导入，而非另设独立导出密码。
fun checkExportImportMasterPassword(conn: java.sql.Connection) {
    // 初始化主密码 mp（写 app_settings：kdf_salt/params/verifier/wrapped_dek）
    val mp = "master-pw"
    val salt = randomSalt()
    val kek = deriveKey(mp, salt, DEFAULT_KDF)
    val dek = randomDek()
    val wrapped = wrapKey(kek, dek)
    val verifier = makeVerifier(kek)
    writeInitialization(conn, salt, wrapped, verifier, null)
    zeroBytes(kek)

    val catA = insertCategory(conn, "社交", 1)
    createEntry(conn, dek, "github", "me@x.com", "gh-pw", "https://gh", "n1", catA)

    // 用主密码导出（useMasterPassword=true）：KEK 必须同源（由主密码经 Argon2id 派生，复用 deriveKek）。
    val f = exportVault(conn, dek, mp, useMasterPassword = true)
    // 同源铁证：文件头 salt == 登录 kdf_salt（而非文件内随机 export_salt）。
    val header = parseVaultHeader(f)
    assertBytesEq(header.exportSalt, salt, "② useMasterPassword=true 文件头 salt 必须等于登录 kdf_salt（同源）")

    // 导入到全新库，用同一主密码 -> 成功（用户只记一个主密码）。
    val conn2 = DriverManager.getConnection("jdbc:sqlite::memory:")
    try {
        execDdl(conn2, VAULT_SCHEMA_STATEMENTS)
        val report = importVault(conn2, dek, f, mp)
        checkThat(report.entriesAdded == 1) { "主密码导出文件用同一主密码导入应新增 1 条，实 ${report.entriesAdded}" }
        val rows = listEntriesMasked(conn2)
        checkThat(rows.size == 1 && rows[0].name == "github") { "主密码导入应有 1 条 github" }
        val gh = getEntryDecrypted(conn2, dek, rows[0].id)
        checkThat(gh!!.second.password == "gh-pw") { "主密码导入后密码应一致" }
        // 错误主密码 -> WRONG_PASSWORD（同源派生失败）
        var threw = false
        try { importVault(conn2, dek, f, "wrong-mp") } catch (e: WrongPasswordException) { threw = true }
        checkThat(threw) { "错误主密码导入应抛 WrongPasswordException" }
        // 导出时主密码错误 -> 立即抛 WrongPasswordException（verifier 校验，防 typo 产出打不开的文件）
        var exportThrew = false
        try { exportVault(conn, dek, "wrong-mp", useMasterPassword = true) } catch (e: WrongPasswordException) { exportThrew = true }
        checkThat(exportThrew) { "导出时主密码错误应抛 WrongPasswordException" }
    } finally {
        conn2.close()
    }
}

fun checkGcmTamper() {
    val key = randomDek()
    val blob = encryptAesGcm(key, "top-secret".toByteArray())
    // 篡改密文一字节
    val tampered = AeadBlob(blob.nonce.copyOf(), blob.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })
    var threw = false
    try { decryptAesGcm(key, tampered) } catch (e: VaultException) { threw = true }
    checkThat(threw) { "GCM 篡改密文必须抛 VaultException（防静默失败）" }
    // 错误密钥解密亦抛
    val wrongKey = randomDek()
    var threw2 = false
    try { decryptAesGcm(wrongKey, blob) } catch (e: VaultException) { threw2 = true }
    checkThat(threw2) { "错误密钥解密必须抛 VaultException" }
}

// W4（#QA-004）：畸形 .vault 头必须抛 VaultException（契约 WRONG_PASSWORD 语义），而非 BufferUnderflowException。
// 复现 qa-jiyan 实证：合法 .vault 把 saltLen 改为 65535 后，parseVaultHeader 曾抛 BufferUnderflowException。
fun checkMalformedVaultHeader() {
    val salt = randomSalt()
    val key = deriveKey("export-pw", salt, DEFAULT_KDF)
    val blob = encryptAesGcm(key, "payload-bytes".toByteArray())
    val file = serializeVaultFile(DEFAULT_KDF, salt, blob)

    // 篡改 saltLen=65535：saltLen 位于偏移 15-16（magic4 + version1 + algo1 + mem4 + iter4 + par1）。
    val saltTampered = file.copyOf()
    saltTampered[15] = 0xFF.toByte()
    saltTampered[16] = 0xFF.toByte()
    var threwSalt = false
    try { parseVaultHeader(saltTampered) } catch (e: WrongPasswordException) { threwSalt = true }
    checkThat(threwSalt) { "saltLen=65535 应抛 WrongPasswordException，而非 BufferUnderflowException" }

    // 截断密文块使 ctLen < TAG_LEN（结构损坏 => WRONG_PASSWORD）。
    val truncated = file.copyOfRange(0, file.size - AeadBlob.TAG_LEN)
    var threwCt = false
    try { parseVaultHeader(truncated) } catch (e: WrongPasswordException) { threwCt = true }
    checkThat(threwCt) { "密文块过短（ctLen < TAG_LEN）应抛 WrongPasswordException" }

    // 截断至 nonce 越界（ivLen 越界：头部17 + 完整盐 + 仅 4 字节 nonce）。
    val noNonce = file.copyOfRange(0, 17 + salt.size + 4)
    var threwNonce = false
    try { parseVaultHeader(noNonce) } catch (e: WrongPasswordException) { threwNonce = true }
    checkThat(threwNonce) { "nonce 越界应抛 WrongPasswordException" }
}

fun checkVaultSession() {
    val s = VaultSession()
    checkThat(s.isLocked()) { "初始应为锁定态" }
    checkThat(s.getActiveDek() == null) { "锁定态 getActiveDek 应为 null" }
    val dek = randomDek()
    s.unlock(dek)
    checkThat(!s.isLocked() && s.getActiveDek()!!.contentEquals(dek)) { "unlock 后 DEK 可取回" }
    s.lock()
    checkThat(s.isLocked() && s.getActiveDek() == null) { "lock 后清零且为 null" }
}

// ============================================================================
// main：先跑纯模块自检，再跑逐条契约检查。
// ============================================================================

// 每个契约检查用独立的内存库（categories.name 有 UNIQUE 约束，避免跨检查名字冲突）。
fun withFreshDb(block: (java.sql.Connection) -> Unit) {
    val c = DriverManager.getConnection("jdbc:sqlite::memory:")
    try {
        execDdl(c, VAULT_SCHEMA_STATEMENTS)
        block(c)
    } finally {
        c.close()
    }
}

fun main(args: Array<String>) {
    // 自检即文档：纯模块不变量（静默）
    vaultCryptoSelfTest()
    vaultFormatSelfTest()
    vaultMergeSelfTest()
    jsonSelfTest()
    vaultSessionSelfTest()

    // 加载 sqlite-jdbc（裸 JVM 无 Android SQLite）
    Class.forName("org.sqlite.JDBC")

    withFreshDb { checkListEntries(it) }
    withFreshDb { checkCreateGetUpdateDeleteEntry(it) }
    withFreshDb { checkCategoryDao(it) }
    withFreshDb { checkSeedCategories(it) }
    withFreshDb { checkChangeMasterPassword(it) }
    withFreshDb { checkExportImport(it) }
    withFreshDb { checkExportImportMasterPassword(it) }
    checkGcmTamper()
    checkMalformedVaultHeader()
    checkVaultSession()
    println("ALL CONTRACT CHECKS OK")
}
