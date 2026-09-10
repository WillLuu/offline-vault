package vault

// ============================================================================
// Query.kt：v2 内存检索/排序（纯函数，零 Android 依赖，JVM 可测）。
// v2 下 name/username/category-name 均在密文里，SQL 无法再 LIKE/ORDER BY 明文列，
// 故 DAO 先按结构（is_deleted / category_id）取行并解密，再在此做搜索 + 排序 + 分页。
// 语义严格对齐旧 SQL（SqlBuilders.buildListEntriesQuery 的 v1 版）：
//   搜索：name 或 username 包含匹配（大小写不敏感，trim）。
//   排序：NAME_ASC/DESC 按 name；UPDATED_DESC 按 updated_at DESC 再 name ASC；
//         CATEGORY_ASC/DESC 按 (分类 sort_order, 分类名, 条目名)；未分类 sort_order 视作 -1（排最前，近似 SQLite NULL ASC 首位）。
// ============================================================================

// categoryOrder: categoryId -> (sort_order, categoryName)。null/缺失视作未分类 (-1, "")。
fun filterAndSortEntries(
    rows: List<PasswordEntryRow>,
    search: String?,
    sortBy: SortKey,
    categoryId: Long?,
    categoryOrder: Map<Long, Pair<Int, String>>,
    limit: Int = LIST_NO_LIMIT,
    offset: Int = 0
): List<PasswordEntryRow> {
    var out = rows.asSequence()
    if (categoryId != null) out = out.filter { it.categoryId == categoryId }
    if (!search.isNullOrBlank()) {
        val q = search.trim().lowercase()
        out = out.filter { it.name.lowercase().contains(q) || it.username.lowercase().contains(q) }
    }
    val list = out.toMutableList()

    fun catKey(r: PasswordEntryRow): Pair<Int, String> =
        r.categoryId?.let { categoryOrder[it] } ?: (-1 to "")

    when (sortBy) {
        SortKey.NAME_ASC -> list.sortWith(compareBy({ it.name }, { it.id }))
        SortKey.NAME_DESC -> list.sortWith(compareByDescending<PasswordEntryRow> { it.name }.thenBy { it.id })
        SortKey.UPDATED_DESC -> list.sortWith(compareByDescending<PasswordEntryRow> { it.updatedAt }.thenBy { it.name }.thenBy { it.id })
        SortKey.CATEGORY_ASC -> list.sortWith(
            compareBy<PasswordEntryRow> { catKey(it).first }.thenBy { catKey(it).second }.thenBy { it.name }.thenBy { it.id }
        )
        SortKey.CATEGORY_DESC -> list.sortWith(
            compareByDescending<PasswordEntryRow> { catKey(it).first }.thenBy { catKey(it).second }.thenBy { it.name }.thenBy { it.id }
        )
    }

    if (offset >= list.size) return emptyList()
    val from = offset.coerceAtLeast(0)
    val to = if (limit == LIST_NO_LIMIT || limit <= 0) list.size else (from + limit).coerceAtMost(list.size)
    return list.subList(from, to).toList()
}

// 自检：搜索、各排序、分页。
fun querySelfTest() {
    fun row(id: Long, name: String, user: String, cat: Long?, upd: Long) =
        PasswordEntryRow(id, name, user, null, null, null, cat, null, 0, upd, false)
    val cats = mapOf(1L to (0 to "支付"), 2L to (1 to "社交"))
    val rows = listOf(
        row(1, "GitHub", "me@x.com", 2L, 100),
        row(2, "alipay", "u1", 1L, 300),
        row(3, "Bank", "u2", null, 200),
        row(4, "alpha", "z@x.com", 2L, 400)
    )

    // 搜索（大小写不敏感，命中 name）
    checkThat(filterAndSortEntries(rows, "git", SortKey.NAME_ASC, null, cats).map { it.id } == listOf(1L)) { "搜索 name 失败" }
    // 搜索命中 username
    checkThat(filterAndSortEntries(rows, "z@x", SortKey.NAME_ASC, null, cats).map { it.id } == listOf(4L)) { "搜索 username 失败" }
    // NAME_ASC：ASCII 大写在前 -> Bank(B), GitHub(G), 再小写 alipay < alpha（第3字符 i<p）
    val nameAsc = filterAndSortEntries(rows, null, SortKey.NAME_ASC, null, cats).map { it.name }
    checkThat(nameAsc == listOf("Bank", "GitHub", "alipay", "alpha")) { "NAME_ASC 失败: $nameAsc" }
    // NAME_DESC 逆序，首项 alpha
    checkThat(filterAndSortEntries(rows, null, SortKey.NAME_DESC, null, cats).first().name == "alpha") { "NAME_DESC 失败" }
    // UPDATED_DESC：400,300,200,100 -> id 4,2,3,1
    checkThat(filterAndSortEntries(rows, null, SortKey.UPDATED_DESC, null, cats).map { it.id } == listOf(4L, 2L, 3L, 1L)) { "UPDATED_DESC 失败" }
    // 分类过滤
    checkThat(filterAndSortEntries(rows, null, SortKey.NAME_ASC, 2L, cats).map { it.id }.toSet() == setOf(1L, 4L)) { "分类过滤失败" }
    // CATEGORY_ASC：未分类(-1) 最前，然后 支付(0)、社交(1)
    val catAsc = filterAndSortEntries(rows, null, SortKey.CATEGORY_ASC, null, cats).map { it.id }
    checkThat(catAsc == listOf(3L, 2L, 1L, 4L)) { "CATEGORY_ASC 失败: $catAsc" } // Bank(未分类), alipay(支付0), GitHub(社交1,名G), alpha(社交1,名a)
    // 分页（NAME_ASC 序 [3,1,2,4]，offset2 limit2 -> [2,4]）
    checkThat(filterAndSortEntries(rows, null, SortKey.NAME_ASC, null, cats, limit = 2, offset = 2).map { it.id } == listOf(2L, 4L)) { "分页失败" }
}
