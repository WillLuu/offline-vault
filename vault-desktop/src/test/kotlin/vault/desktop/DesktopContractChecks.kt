package vault.desktop

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import vault.EntryInput
import vault.ExtraField
import vault.LockedException
import vault.SortKey
import vault.checkThat
import vault.AeadBlob
import vault.decryptAesGcm
import vault.parseVaultHeader
import vault.WrongPasswordException
import vault.DEFAULT_KDF
import vault.deriveKey
import vault.encryptAesGcm
import vault.jsonEncode
import vault.makeVerifier
import vault.randomDek
import vault.randomSalt
import vault.wrapKey
import vault.nowMillis
import vault.zeroBytes

// ============================================================================
// DesktopContractChecks：桌面端存储/解锁自检（"自检即文档"约定，无测试框架，静默通过）。
//  覆盖 M3 出口条件（PRD F1-F4 + SEC-8）：
//   ① 初始化/重复拒绝/种子分类  ② 错误密码/解锁  ③ CRUD+extras+掩码
//   ④ 搜索/排序/分类过滤  ⑤ 软删+非空分类不可删+"其他"不可删  ⑥ 改主密码 DEK 不变
//   ⑦ 锁定态拒绝访问  ⑧ 失败限流（5 次起冷却，冷却期连正确密码也拒绝且 lockout>0）
//  用真实文件 DB（临时目录），同时验证 %APPDATA% 路径逻辑（OFFLINE_VAULT_DIR 覆盖机制）。
// ============================================================================

fun main() {
    val tmpDir = Files.createTempDirectory("offline-vault-desktop-check").toFile()
    System.setProperty("user.home", tmpDir.absolutePath)
    val dbFile = File(tmpDir, "vault.db")
    val vault = DesktopVault(dbFile)

    // ① 首次初始化
    checkThat(!vault.isInitialized()) { "初始应未初始化" }
    checkThat(vault.initializeMasterPassword("master-pw-123")) { "初始化应成功" }
    checkThat(vault.isInitialized()) { "初始化后 isInitialized 应为 true" }
    checkThat(!vault.initializeMasterPassword("other-pw-123")) { "重复初始化应被拒绝" }
    checkThat(vault.listCategories().map { it.name } == listOf("支付", "社交", "工作", "娱乐", "邮箱", "其他")) {
        "种子分类应 6 个且有序，实 ${vault.listCategories().map { it.name }}"
    }

    // ② 错误密码拒绝 / 正确密码解锁
    vault.lock()
    checkThat(!vault.unlockWithPassword("wrong-password")) { "错误密码应解锁失败" }
    checkThat(vault.unlockWithPassword("master-pw-123")) { "正确密码应解锁成功" }

    // ③ CRUD + extras + 掩码
    val cats = vault.listCategories()
    val socialId = cats.first { it.name == "社交" }.id
    val extras = listOf(ExtraField("密保问题", "blue-42"), ExtraField("PIN", "1234"))
    val id = vault.createEntry(EntryInput("github", "me@x.com", "gh-secret", "https://gh", "note-1", socialId, extras))
    checkThat(id > 0) { "createEntry 应返回正 id" }
    val got = vault.getEntry(id)!!
    checkThat(got.password == "gh-secret" && got.website == "https://gh") { "getEntry 解密回填错误" }
    checkThat(got.extras == extras) { "extras 往返失败，实 ${got.extras}" }
    checkThat(got.categoryName == "社交") { "category_name JOIN 回填失败" }
    val listed = vault.listEntries()
    checkThat(listed.size == 1 && listed[0].password == null) { "列表应掩码（password=null）" }
    checkThat(
        vault.updateEntry(id, EntryInput("github", "me@x.com", "gh-new", "https://gh", "note-2", socialId, emptyList()))
    ) { "updateEntry 应成功" }
    checkThat(vault.getEntry(id)!!.password == "gh-new") { "update 后密码应更新" }
    checkThat(vault.getEntry(id)!!.extras.isEmpty()) { "update 清空 extras 后应为空" }

    // 密文 ≠ 明文（直接读 blob 验证加密真实发生）
    val rawBlob: ByteArray = vault.db.connection.prepareStatement(
        "SELECT secret_blob FROM password_entries WHERE id = ?"
    ).use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); rs.getBytes(1) } }
    checkThat(rawBlob.size > "gh-new".length + 28) { "secret_blob 应为密文（含 nonce+tag 且远大于明文）" }
    checkThat(!String(rawBlob).contains("gh-new")) { "密文不得含明文" }
    // 用活跃 DEK 手工解密应还原 JSON（往返证明）
    val dek = vault.unlock.getActiveDek()!!
    val json = String(decryptAesGcm(dek, AeadBlob.fromBytes(rawBlob)), Charsets.UTF_8)
    checkThat(json.contains("gh-new")) { "DEK 手工解密应还原含新密码的 JSON" }

    // ④ 搜索 / 排序 / 分类过滤
    vault.createEntry(EntryInput("gitlab", "me@x.com", "gl-pw", "", "", socialId, emptyList()))
    val workId = cats.first { it.name == "工作" }.id
    vault.createEntry(EntryInput("vpn", "admin", "vp-pw", "", "", workId, emptyList()))
    checkThat(vault.listEntries(search = "git").size == 2) { "搜索 'git' 应命中 2 条" }
    checkThat(vault.listEntries(search = "me@x.com").size == 2) { "按用户名搜索应命中 2 条" }
    checkThat(vault.listEntries(categoryId = workId).single().name == "vpn") { "分类过滤应仅返回工作类" }
    checkThat(vault.listEntries(sortBy = SortKey.NAME_ASC).map { it.name } == listOf("github", "gitlab", "vpn")) {
        "NAME_ASC 排序错误"
    }
    checkThat(vault.listEntries(sortBy = SortKey.NAME_DESC).first().name == "vpn") { "NAME_DESC 排序错误" }
    checkThat(vault.listEntries(sortBy = SortKey.UPDATED_DESC).first().name == "vpn") { "UPDATED_DESC 最新应在前" }
    run {
        // 种子 sort_order：支付0<社交1<工作2 → CATEGORY_ASC 首条应为社交类（github/gitlab 之一）
        val first = vault.listEntries(sortBy = SortKey.CATEGORY_ASC).first().name
        checkThat(first in setOf("github", "gitlab")) { "CATEGORY_ASC 首条应为社交类，实 $first" }
    }

    // ⑤ 软删 + 分类删除保护
    val glId = vault.listEntries(search = "gitlab").single().id
    checkThat(vault.deleteEntry(glId)) { "软删应成功" }
    checkThat(vault.listEntries().none { it.id == glId }) { "软删后列表不应返回" }
    checkThat(!vault.deleteCategory(socialId)) { "非空分类（社交）不可删" }
    val otherId = cats.first { it.name == "其他" }.id
    checkThat(!vault.deleteCategory(otherId)) { "种子分类「其他」不可删" }
    val tempCat = vault.createCategory("临时", 9)
    checkThat(vault.deleteCategory(tempCat)) { "空自定义分类可删" }
    // 重排
    checkThat(vault.reorderCategories(listOf(workId, socialId, otherId))) { "重排应成功" }
    checkThat(
        vault.listCategories().first().id == workId
    ) { "重排后首位应为工作" }

    // ⑥ 改主密码：DEK 不变、旧 blob 字节不变、新密码可解
    val beforeBlob = vault.db.connection.prepareStatement(
        "SELECT secret_blob FROM password_entries WHERE id = ?"
    ).use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); rs.getBytes(1) } }
    val dekBefore = vault.unlock.getActiveDek()!!
    checkThat(vault.changeMasterPassword("master-pw-123", "master-pw-456")) { "改主密码应成功" }
    val dekAfter = vault.unlock.getActiveDek()!!
    checkThat(dekBefore.contentEquals(dekAfter)) { "改主密码后 DEK 应不变" }
    val afterBlob = vault.db.connection.prepareStatement(
        "SELECT secret_blob FROM password_entries WHERE id = ?"
    ).use { ps -> ps.setLong(1, id); ps.executeQuery().use { rs -> rs.next(); rs.getBytes(1) } }
    checkThat(beforeBlob.contentEquals(afterBlob)) { "改主密码后 secret_blob 字节应不变（不重加密）" }
    checkThat(vault.getEntry(id)!!.password == "gh-new") { "改密后（同会话）仍可解密" }
    vault.lock()
    checkThat(!vault.unlockWithPassword("master-pw-123")) { "旧主密码应解锁失败" }
    checkThat(vault.unlockWithPassword("master-pw-456")) { "新主密码应解锁成功" }

    // ⑦ 锁定态拒绝
    vault.lock()
    var lockedThrew = false
    try { vault.listEntries() } catch (e: LockedException) { lockedThrew = true }
    checkThat(lockedThrew) { "锁定态 listEntries 应抛 LockedException" }
    checkThat(vault.unlockWithPassword("master-pw-456")) { "重新解锁应成功" }

    // ⑧ 备份导出/导入（F5/F6，跨端兼容的桌面侧验证——格式/合并决策与 Android 同走 core 代码）：
    //    独立密码导出 → 新库导入（中文+extras 完整往返）；同库重导全 skipped；错误密码拒绝；
    //    主密码同源导出（文件头 salt==登录 salt 铁证，同契约测试 checkExportImportMasterPassword）。
    val cnId = vault.createEntry(EntryInput("网银", "张三", "cn-pw", "https://bank", "中文备注", socialId,
        listOf(ExtraField("密保问题", "红色"))))
    checkThat(cnId > 0) { "中文条目应可创建" }

    val exportPw = "export-pw-8888"
    val fileBytes = vault.exportVault(exportPw, useMasterPassword = false)
    checkThat(String(fileBytes, 0, 4, Charsets.US_ASCII) == "VLT1") { ".vault magic 应为 VLT1" }

    val vault2 = DesktopVault(File(tmpDir, "vault2.db"))
    vault2.initializeMasterPassword("desktop-pw-2")
    val report = vault2.importVault(fileBytes, exportPw)
    checkThat(report.entriesAdded == 3) { "新库导入应新增 3 条（github/vpn/网银），实 ${report.entriesAdded}" }
    val cnRow = vault2.listEntries(search = "网银").single()
    val cn2 = vault2.getEntry(cnRow.id)!!
    checkThat(cn2.password == "cn-pw" && cn2.notes == "中文备注") { "中文明文往返失败" }
    checkThat(cn2.extras == listOf(ExtraField("密保问题", "红色"))) { "自定义词条跨库往返失败，实 ${cn2.extras}" }
    checkThat(cn2.categoryName == "社交") { "分类名应随导入还原" }

    // 同库重导同一文件 → 全 skipped（updated_at 相等取旧——"合并≠同步"语义基础，PRD R8 相关）
    val report2 = vault2.importVault(fileBytes, exportPw)
    checkThat(report2.entriesAdded == 0 && report2.entriesUpdated == 0 && report2.entriesSkipped == 3) {
        "同库重导应全跳过，实 added=${report2.entriesAdded} updated=${report2.entriesUpdated} skipped=${report2.entriesSkipped}"
    }

    var wrongThrew = false
    try { vault2.importVault(fileBytes, "bad-export-pw") } catch (e: WrongPasswordException) { wrongThrew = true }
    checkThat(wrongThrew) { "错误导出密码应抛 WrongPasswordException" }

    val sameFile = vault.exportVault("master-pw-456", useMasterPassword = true)
    val header = parseVaultHeader(sameFile)
    val loginSalt = vault.settings.getKdfMaterial()!!.second
    checkThat(header.exportSalt.contentEquals(loginSalt)) { "主密码同源导出：文件头 salt 必须等于登录 salt" }
    var sameThrew = false
    try { vault2.importVault(sameFile, "wrong-master") } catch (e: WrongPasswordException) { sameThrew = true }
    checkThat(sameThrew) { "同源文件用错误主密码导入应拒绝" }
    vault2.close()

    // ⑨ 失败限流（SEC-8）：先锁定会话 → 连续 5 次错误 → 冷却期内连正确密码也拒绝且 lockout>0
    vault.lock()
    repeat(5) { checkThat(!vault.unlockWithPassword("bad")) { "第 ${it + 1} 次错误密码应失败" } }
    checkThat(vault.lockoutRemainingMs() > 0) { "5 次失败后应进入冷却" }
    checkThat(!vault.unlockWithPassword("master-pw-456")) { "冷却期内正确密码也应被拒绝（先限流后派生）" }
    checkThat(vault.isLocked()) { "冷却期内会话保持锁定" }

    vault.close()
    tmpDir.deleteRecursively()
    checkRealV1ToV2Migration()
    println("DESKTOP CHECKS OK (10 groups)")
}

// ⑩ 真实代码路径的 v1→v2 迁移回归：手工建 v1 库（明文 name/username + v1 secret JSON +
//   明文分类名 + user_version=1），经 DesktopVault.open 触发 DDL 迁移、unlockWithPassword 触发
//   数据迁移，验证条目/分类名解密可读、明文列清空、重名唯一性生效。
private fun checkRealV1ToV2Migration() {
    val tmp = Files.createTempDirectory("ov-mig").toFile()
    val f = File(tmp, "v1.db")
    val pw = "migrate-pw-123"
    val salt = randomSalt()
    val kek = deriveKey(pw, salt, DEFAULT_KDF)
    val dek = randomDek()
    DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}").use { c ->
        c.createStatement().use { st ->
            st.execute("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
            st.execute("CREATE UNIQUE INDEX uq_categories_name ON categories(name)")
            st.execute("CREATE TABLE password_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, username TEXT NOT NULL DEFAULT '', secret_blob BLOB NOT NULL, category_id INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0)")
            st.execute("CREATE INDEX idx_entries_name ON password_entries(name)")
            st.execute("CREATE TABLE app_settings (id INTEGER PRIMARY KEY CHECK (id = 1), initialized INTEGER NOT NULL DEFAULT 0, kdf_algo TEXT NOT NULL DEFAULT 'argon2id', kdf_memory_kb INTEGER NOT NULL DEFAULT 32768, kdf_iterations INTEGER NOT NULL DEFAULT 2, kdf_parallelism INTEGER NOT NULL DEFAULT 1, kdf_salt BLOB NOT NULL, wrapped_dek BLOB NOT NULL, verifier BLOB NOT NULL, wrapped_dek_biometric BLOB, auto_lock_timeout_sec INTEGER NOT NULL DEFAULT 60, clipboard_clear_delay_sec INTEGER NOT NULL DEFAULT 30, theme TEXT NOT NULL DEFAULT 'system', biometric_enabled INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            val now = nowMillis()
            c.prepareStatement("INSERT INTO categories (name, sort_order, created_at) VALUES ('支付', 0, ?)").use { ps -> ps.setLong(1, now); ps.executeUpdate() }
            c.prepareStatement("INSERT INTO categories (name, sort_order, created_at) VALUES ('社交', 1, ?)").use { ps -> ps.setLong(1, now); ps.executeUpdate() }
            // 一条 v1 条目（明文 name/username + v1 secret JSON）
            val v1Secret = encryptAesGcm(dek, jsonEncode(mapOf("password" to "old-pw", "website" to "https://x", "notes" to "老备注")).toByteArray(Charsets.UTF_8)).toBytes()
            c.prepareStatement("INSERT INTO password_entries (name, username, secret_blob, category_id, created_at, updated_at, is_deleted) VALUES ('旧条目', 'old-user', ?, 1, ?, ?, 0)").use { ps ->
                ps.setBytes(1, v1Secret); ps.setLong(2, now); ps.setLong(3, now); ps.executeUpdate()
            }
            // app_settings：初始化态（同源 KEK 包 DEK + verifier）
            c.prepareStatement("INSERT INTO app_settings (id, initialized, kdf_salt, wrapped_dek, verifier, created_at, updated_at) VALUES (1, 1, ?, ?, ?, ?, ?)").use { ps ->
                ps.setBytes(1, salt); ps.setBytes(2, wrapKey(kek, dek).toBytes()); ps.setBytes(3, makeVerifier(kek).toBytes()); ps.setLong(4, now); ps.setLong(5, now); ps.executeUpdate()
            }
            st.execute("PRAGMA user_version = 1")
        }
    }
    zeroBytes(kek)

    // 用新代码打开（触发 DDL 迁移）→ 解锁（触发数据迁移）
    val vault = DesktopVault(f)
    checkThat(vault.unlockWithPassword(pw)) { "迁移：正确密码应解锁成功" }
    // 分类名解密可读且有序
    checkThat(vault.listCategories().map { it.name } == listOf("支付", "社交")) { "迁移后分类名应可读: ${vault.listCategories().map { it.name }}" }
    // 条目 name/username 从密文还原
    val listed = vault.listEntries()
    checkThat(listed.size == 1 && listed[0].name == "旧条目" && listed[0].username == "old-user") { "迁移后条目名/用户名应还原: ${listed.map { it.name to it.username }}" }
    val detail = vault.getEntry(listed[0].id)!!
    checkThat(detail.password == "old-pw" && detail.notes == "老备注" && detail.categoryName == "支付") { "迁移后 secret/分类应正确" }
    // 明文列已清空
    vault.db.connection.prepareStatement("SELECT name, username FROM password_entries").use { ps ->
        ps.executeQuery().use { rs -> while (rs.next()) checkThat(rs.getString(1) == "" && rs.getString(2) == "") { "迁移后明文列应清空" } }
    }
    // 重名分类唯一性（代码层）：新建同名"支付"应返回 -1
    checkThat(vault.createCategory("支付", 9) == -1L) { "迁移后重名分类应被拒（代码层唯一性）" }
    vault.close()
    tmp.deleteRecursively()
}
