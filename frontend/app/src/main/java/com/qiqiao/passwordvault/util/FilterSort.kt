package com.qiqiao.passwordvault.util

import com.qiqiao.passwordvault.model.PasswordEntryRow
import com.qiqiao.passwordvault.model.SortKey

// 搜索：对 name/username 做不区分大小写 LIKE；空/空白 query 返回原列表
fun filterEntries(rows: List<PasswordEntryRow>, query: String?): List<PasswordEntryRow> {
    val q = query?.trim().orEmpty()
    if (q.isEmpty()) return rows
    val lower = q.lowercase()
    return rows.filter {
        it.name.lowercase().contains(lower) || it.username.lowercase().contains(lower)
    }
}

// 排序：按 SortKey 应用（稳定排序）。CATEGORY 先按分类名再按名称
fun sortEntries(rows: List<PasswordEntryRow>, sortBy: SortKey): List<PasswordEntryRow> {
    return when (sortBy) {
        SortKey.NAME_ASC -> rows.sortedBy { it.name.lowercase() }
        SortKey.NAME_DESC -> rows.sortedByDescending { it.name.lowercase() }
        SortKey.CATEGORY_ASC -> rows.sortedWith(
            compareBy({ it.categoryName.lowercase() }, { it.name.lowercase() })
        )
        SortKey.CATEGORY_DESC -> rows.sortedWith(
            compareByDescending<PasswordEntryRow> { it.categoryName.lowercase() }
                .thenByDescending { it.name.lowercase() }
        )
        // 更新时间倒序（2026-09-06 用户定稿默认排序）：同刻用名称兜底保证顺序稳定
        SortKey.UPDATED_DESC -> rows.sortedWith(
            compareByDescending<PasswordEntryRow> { it.updatedAt }.thenBy { it.name.lowercase() }
        )
    }
}

// 自检：搜索（空/大小写/无命中）与排序（升降序 / 分类）
fun testFilterSort() {
    // 用命名参数构造，避免与 PasswordEntryRow 字段顺序（…notes, categoryId, categoryName, createdAt…）耦合错位
    val a = PasswordEntryRow(id = 1, name = "Alice", username = "alice@example.com", password = "", website = "", notes = "", categoryId = 1, categoryName = "社交", createdAt = 0, updatedAt = 0)
    val b = PasswordEntryRow(id = 2, name = "Bob", username = "bob", password = "", website = "", notes = "", categoryId = 2, categoryName = "工作", createdAt = 0, updatedAt = 0)
    val rows = listOf(a, b)
    assert(filterEntries(rows, null).size == 2) { "null query 返回全部" }
    assert(filterEntries(rows, "  ").size == 2) { "空白 query 返回全部" }
    assert(filterEntries(rows, "alic").size == 1) { "子串命中" }
    assert(filterEntries(rows, "ALICE").size == 1) { "大小写不敏感" }
    assert(filterEntries(rows, "zzz").isEmpty()) { "无命中返回空" }

    assert(sortEntries(rows, SortKey.NAME_ASC)[0].name == "Alice") { "名称升序" }
    assert(sortEntries(rows, SortKey.NAME_DESC)[0].name == "Bob") { "名称降序" }
    assert(sortEntries(rows, SortKey.CATEGORY_ASC)[0].categoryName == "工作") { "分类升序：工作 < 社交" }
    assert(sortEntries(rows, SortKey.CATEGORY_DESC)[0].categoryName == "社交") { "分类降序：社交 > 工作" }
}
