package vault

// ============================================================================
// SQL 查询构造（纯函数，零 Android 依赖）。
//  - 单一事实源：Android/桌面 DAO 与 JVM 契约测试都使用这里的 SQL，避免漂移。
//  - 副作用隔离：只"构造 SQL 字符串 + 参数"，不执行、不持有 DB 连接。
//
// v2（全加密）：name/username/category-name 均在密文里，SQL 不再做 LIKE / ORDER BY 明文列，
//  只负责结构过滤（is_deleted / category_id）。搜索/排序/分页见 Query.filterAndSortEntries（内存）。
//  仍 SELECT e.name / e.username / c.name：迁移进行中的行明文列尚未清空，读侧据此回退（见 EntryBlob）。
//  - limit/offset 不再进 SQL（内存分页），故本文件不再内联它们。
// ============================================================================

// 列表取行（结构过滤）。category_id 可选；始终排除软删。
fun buildListEntriesQuery(categoryId: Long? = null): Pair<String, Array<String>> {
    val where = mutableListOf("is_deleted = 0")
    val args = mutableListOf<String>()
    if (categoryId != null) {
        where += "category_id = ?"
        args += categoryId.toString()
    }
    val sql = """
        SELECT id, name, username, secret_blob, category_id, created_at, updated_at
        FROM password_entries
        WHERE ${where.joinToString(" AND ")}
    """.trimIndent()
    return sql to args.toTypedArray()
}

// 详情取单行（含 secret_blob 供解密回填）。
fun buildGetEntryQuery(): String = """
    SELECT id, name, username, secret_blob, category_id, created_at, updated_at
    FROM password_entries
    WHERE id = ? AND is_deleted = 0
""".trimIndent()

// 分类列表：LEFT JOIN 取条目计数（防 N+1）。name_blob 解密在 DAO；c.name 供迁移期回退。
fun buildListCategoriesQuery(): String = """
    SELECT c.id, c.name, c.name_blob, c.sort_order, c.created_at,
           COALESCE(COUNT(e.id), 0) AS entry_count
    FROM categories c
    LEFT JOIN password_entries e ON e.category_id = c.id AND e.is_deleted = 0
    GROUP BY c.id, c.sort_order, c.created_at
    ORDER BY c.sort_order ASC
""".trimIndent()

// 迁移扫描：统计仍带明文 name/username 的条目、以及尚未生成 name_blob 的分类。
// 两者皆 0 表示数据迁移已完成（幂等守卫）。
const val SQL_COUNT_UNMIGRATED_ENTRIES =
    "SELECT COUNT(*) FROM password_entries WHERE name <> '' OR username <> ''"
const val SQL_COUNT_UNMIGRATED_CATEGORIES =
    "SELECT COUNT(*) FROM categories WHERE name <> '' AND name_blob IS NULL"
