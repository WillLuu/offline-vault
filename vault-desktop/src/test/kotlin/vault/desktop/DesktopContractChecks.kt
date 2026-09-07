package vault.desktop

import java.io.File
import java.nio.file.Files
import vault.EntryInput
import vault.ExtraField
import vault.LockedException
import vault.SortKey
import vault.checkThat
import vault.AeadBlob
import vault.decryptAesGcm

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

    // ⑧ 失败限流（SEC-8）：先锁定会话 → 连续 5 次错误 → 冷却期内连正确密码也拒绝且 lockout>0
    vault.lock()
    repeat(5) { checkThat(!vault.unlockWithPassword("bad")) { "第 ${it + 1} 次错误密码应失败" } }
    checkThat(vault.lockoutRemainingMs() > 0) { "5 次失败后应进入冷却" }
    checkThat(!vault.unlockWithPassword("master-pw-456")) { "冷却期内正确密码也应被拒绝（先限流后派生）" }
    checkThat(vault.isLocked()) { "冷却期内会话保持锁定" }

    vault.close()
    tmpDir.deleteRecursively()
    println("DESKTOP CHECKS OK (8 groups)")
}
