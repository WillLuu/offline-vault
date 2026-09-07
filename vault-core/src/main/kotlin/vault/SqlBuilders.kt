package vault

// ============================================================================
// SQL 查询构造（纯函数，零 Android 依赖）。
//  - 单一事实源：Android DAO 与 JVM 契约测试都使用这里的 SQL，避免两边漂移。
//  - 副作用隔离：本文件只"构造 SQL 字符串 + 参数"，不执行、不持有 DB 连接。
//  - limit/offset 为应用受控的非负整数，直接内联进 SQL（避免 LIMIT ? 跨引擎绑定类型差异）。
//    ponytail: limit/offset 内联而非绑定，因 SQLite 在 LIMIT/OFFSET 上对绑定类型较敏感。
//    | 升级阈值：若 limit/offset 改为用户可控字符串，必须改回 ? 绑定并校验。
// ============================================================================

// 列表查询：搜索 + 排序 + 分类过滤 + 分页（掩码：不解密 secret_blob）。
fun buildListEntriesQuery(
    search: String? = null,
    sortBy: SortKey = SortKey.NAME_ASC,
    categoryId: Long? = null,
    limit: Int = 100,
    offset: Int = 0
): Pair<String, Array<String>> {
    val where = mutableListOf<String>()
    val args = mutableListOf<String>()
    if (search != null && search.isNotBlank()) {
        // name/username 明文 LIKE（契约 §6）。前后 % 做包含匹配。
        // ponytail: 未对用户输入中的 %/_ 做 ESCAPE，按契约仅做简单 LIKE。 | 升级阈值：若需求要求把 %/_ 当字面量，需加 ESCAPE 子句。
        val like = "%${search.trim()}%"
        where += "(e.name LIKE ? OR e.username LIKE ?)"
        args += like
        args += like
    }
    if (categoryId != null) {
        where += "e.category_id = ?"
        args += categoryId.toString()
    }
    where += "e.is_deleted = 0" // 默认不含已软删
    val orderBy = when (sortBy) {
        SortKey.NAME_ASC -> "e.name ASC"
        SortKey.NAME_DESC -> "e.name DESC"
        // 按分类"用户可见顺序"排序：分类管理里的 sort_order（可拖拽重排）+ 分类名兜底，
        // 组内条目再按名称。不按 category_id（自增 id ≠ 用户看到的分类顺序）。
        SortKey.CATEGORY_ASC -> "c.sort_order ASC, c.name ASC, e.name ASC"
        SortKey.CATEGORY_DESC -> "c.sort_order DESC, c.name ASC, e.name ASC"
        // 更新时间倒序（用户定稿默认排序）：同秒更新的用名称兜底保证顺序稳定
        SortKey.UPDATED_DESC -> "e.updated_at DESC, e.name ASC"
    }
    val sql = """
        SELECT e.id, e.name, e.username, e.category_id, c.name AS category_name,
               e.created_at, e.updated_at, e.is_deleted
        FROM password_entries e
        LEFT JOIN categories c ON e.category_id = c.id
        WHERE ${where.joinToString(" AND ")}
        ORDER BY $orderBy
        LIMIT $limit OFFSET $offset
    """.trimIndent()
    return sql to args.toTypedArray()
}

// 分类列表：单条 LEFT JOIN + GROUP BY 取每条分类的条目计数（防 N+1，契约 §7）。
// entryCount 统计未软删条目（与列表展示一致）。
// ponytail: entryCount 统计 is_deleted=0；若需含已删计数则去掉 AND e.is_deleted = 0。 | 升级阈值：若"非空分类不可删"改为含已删计数，则调整。
fun buildListCategoriesQuery(): String = """
    SELECT c.id, c.name, c.sort_order, c.created_at,
           COALESCE(COUNT(e.id), 0) AS entry_count
    FROM categories c
    LEFT JOIN password_entries e ON e.category_id = c.id AND e.is_deleted = 0
    GROUP BY c.id, c.name, c.sort_order, c.created_at
    ORDER BY c.sort_order ASC, c.name ASC
""".trimIndent()
