package vault

// ============================================================================
// VaultBackup 的纯逻辑层（副作用隔离铁律）：
//  - 荷载 JSON 编解码（导出/导入）。
//  - 导入合并决策（纯函数，不触碰 DB/文件），供 Android 层做实际的 DB 写入。
// 合并主键（契约 §3.4）：
//  分类：按 name；命中保留现有 id 并取导入的 sort_order；未命中新建。
//  条目：按 (name, username, category_name)；命中按 updated_at 取较新者；未命中插入。
//  is_deleted=1 的条目不导入（避免复活已删数据）。
// ============================================================================

// 由 DB 行构建导出荷载（Android 层提供解密后的 secret）。
fun buildExportPayload(
    categories: List<ExportCategory>,
    entries: List<ExportEntry>
): VaultPayload = VaultPayload(version = 1, categories = categories, entries = entries)

fun encodeVaultPayload(payload: VaultPayload): String {
    val cats = payload.categories.map { mapOf("name" to it.name, "sort_order" to it.sortOrder) }
    val ents = payload.entries.map { e ->
        mapOf(
            "name" to e.name,
            "username" to e.username,
            "category_name" to e.categoryName,
            "created_at" to e.createdAt,
            "updated_at" to e.updatedAt,
            "secret" to secretToMap(e.secret)
        )
    }
    return jsonEncode(mapOf("version" to payload.version, "categories" to cats, "entries" to ents))
}

// secret -> JSON map。extras 仅在非空时写入键（2026-09-06 自定义词条；旧版导出文件无该键，字节兼容）。
private fun secretToMap(s: SecretPlain): Map<String, Any?> {
    val m = linkedMapOf<String, Any?>(
        "password" to s.password,
        "website" to s.website,
        "notes" to s.notes
    )
    if (s.extras.isNotEmpty()) {
        m["extras"] = s.extras.map { mapOf("l" to it.label, "v" to it.value) }
    }
    return m
}

// 解析可选 extras 键：缺失/畸形一律回退空列表（旧文件向后兼容；不因附加字段损坏而拒载整文件）。
private fun parseExtras(raw: Any?): List<ExtraField> =
    (raw as? List<*>)?.mapNotNull { e ->
        val m = e as? Map<*, *> ?: return@mapNotNull null
        val l = m["l"] as? String ?: return@mapNotNull null
        ExtraField(l, (m["v"] as? String) ?: "")
    } ?: emptyList()

fun decodeVaultPayload(json: String): VaultPayload {
    val root = jsonDecode(json) as Map<*, *>
    val version = (root["version"] as Number).toInt()
    val cats = (root["categories"] as List<*>).map { c ->
        val m = c as Map<*, *>
        ExportCategory(m["name"] as String, (m["sort_order"] as Number).toInt())
    }
    val ents = (root["entries"] as List<*>).map { e ->
        val m = e as Map<*, *>
        val sec = m["secret"] as Map<*, *>
        ExportEntry(
            name = m["name"] as String,
            username = m["username"] as String,
            categoryName = m["category_name"] as String,
            createdAt = (m["created_at"] as Number).toLong(),
            updatedAt = (m["updated_at"] as Number).toLong(),
            secret = SecretPlain(
                password = sec["password"] as String,
                website = sec["website"] as String,
                notes = sec["notes"] as String,
                extras = parseExtras(sec["extras"])
            )
        )
    }
    return VaultPayload(version, cats, ents)
}

// ---- 导入合并决策（纯函数）----

// 分类合并决策。
data class CategoryMergeDecision(
    val merged: List<Pair<Long, ExportCategory>>, // 命中现有 id + 导入分类（取导入 sort_order）
    val added: List<ExportCategory>                // 未命中，需新建
)

fun decideCategoryMerge(current: List<CategoryRow>, imported: List<ExportCategory>): CategoryMergeDecision {
    val merged = mutableListOf<Pair<Long, ExportCategory>>()
    val added = mutableListOf<ExportCategory>()
    val currentByName = current.associateBy { it.name }
    for (imp in imported) {
        val existing = currentByName[imp.name]
        if (existing != null) {
            // 命中：保留现有 id，取导入的 sort_order（categories 无 updated_at，见 ponytail）
            // ponytail: schema.categories 无 updated_at 列，"较新"退化为"采用导入 sort_order"。
            // | 升级阈值：若需按时间戳合并分类，须在 categories 增加 updated_at 列。
            merged.add(existing.id to imp)
        } else {
            added.add(imp)
        }
    }
    return CategoryMergeDecision(merged, added)
}

// 条目合并决策。
data class EntryMergeDecision(
    val added: List<ExportEntry>,                       // 未命中，需插入新记录
    val updated: List<Pair<Long, ExportEntry>>,         // (现有 id, 导入条目)：updated_at 较新者覆盖
    val skipped: Int                                    // 现有较新（无需更新）而跳过的数量
)

fun decideEntryMerge(current: List<EntryMeta>, imported: List<ExportEntry>): EntryMergeDecision {
    val added = mutableListOf<ExportEntry>()
    val updated = mutableListOf<Pair<Long, ExportEntry>>()
    var skipped = 0
    val currentByKey = current.associateBy { Triple(it.name, it.username, it.categoryName ?: "") }
    for (imp in imported) {
        val key = Triple(imp.name, imp.username, imp.categoryName)
        val existing = currentByKey[key]
        if (existing == null) {
            added.add(imp)
        } else if (imp.updatedAt > existing.updatedAt) {
            updated.add(existing.id to imp) // 导入较新 -> 用现有 id 覆盖
        } else {
            skipped++ // 现有较新或不更旧 -> 跳过（is_deleted=1 在 Android 层读取时已被排除）
        }
    }
    return EntryMergeDecision(added, updated, skipped)
}

// 自检：荷载编解码往返 + 合并决策。
fun vaultMergeSelfTest() {
    val payload = VaultPayload(
        version = 1,
        categories = listOf(ExportCategory("其他", 5), ExportCategory("社交", 1)),
        entries = listOf(
            // 含自定义词条：验证 extras 编解码往返（2026-09-06 自定义词条特性）
            ExportEntry("github", "me@x.com", "社交", 100, 200,
                SecretPlain("p1", "https://gh", "n1", listOf(ExtraField("邮箱", "a@b.c"), ExtraField("手机", "138")))),
            ExportEntry("bank", "u2", "其他", 300, 400, SecretPlain("p2", "https://bk", "n2"))
        )
    )
    val rt = decodeVaultPayload(encodeVaultPayload(payload))
    checkThat(rt.categories == payload.categories) { "分类荷载往返失败" }
    checkThat(rt.entries == payload.entries) { "条目荷载往返失败" }

    // 分类合并：命中取 sort_order，未命中新增
    val curCats = listOf(CategoryRow(1, "其他", 0, 0, 0), CategoryRow(2, "社交", 0, 0, 3))
    val impCats = listOf(ExportCategory("其他", 9), ExportCategory("工作", 2))
    val catDec = decideCategoryMerge(curCats, impCats)
    checkThat(catDec.merged.size == 1 && catDec.merged[0].first == 1L && catDec.merged[0].second.sortOrder == 9) { "分类命中应保留 id=1 并取 sort_order=9" }
    checkThat(catDec.added.size == 1 && catDec.added[0].name == "工作") { "分类未命中应新增 工作" }

    // 条目合并：key 命中且导入较新 => updated；未命中 => added
    val curEntries = listOf(
        EntryMeta(10, "github", "me@x.com", "社交", 150), // 现有较旧(150 < 200) -> 导入覆盖
        EntryMeta(11, "bank", "u2", "其他", 500)          // 现有较新(500 > 400) -> 跳过
    )
    val impEntries = listOf(
        ExportEntry("github", "me@x.com", "社交", 100, 200, SecretPlain("p1", "w", "n")),
        ExportEntry("bank", "u2", "其他", 300, 400, SecretPlain("p2", "w", "n")),
        ExportEntry("new", "u9", "工作", 1, 1, SecretPlain("p3", "w", "n"))
    )
    val dec = decideEntryMerge(curEntries, impEntries)
    checkThat(dec.updated.size == 1 && dec.updated[0].second.name == "github") { "github 应被较新导入覆盖" }
    checkThat(dec.added.size == 1 && dec.added[0].name == "new") { "new 应新增" }
    checkThat(dec.skipped == 1) { "bank 应跳过（现有较新）" }
}
