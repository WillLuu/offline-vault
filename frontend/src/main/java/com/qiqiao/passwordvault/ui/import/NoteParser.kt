package com.qiqiao.passwordvault.ui.import

/**
 * 自由文本笔记解析器：把用户粘贴的账号/密码笔记解析为多条 ParsedEntry。
 * 支持中英文标签（账号/用户/密码/网站/网址/分类/备注 等）、分隔符 : ： = 及空格。
 * 条目之间以空行分隔；无空行时按「无标签行 / 相同标签重复」切分新条目
 * （同字段不同别名如 账号/邮箱 视为同一条目，重复值保留进备注）。
 */
data class ParsedEntry(
    var name: String = "",
    var username: String = "",
    var password: String = "",
    var website: String = "",
    var category: String = "",
    var notes: String = "",
    var include: Boolean = true
)

object NoteParser {
    private val FIELD_LABELS: Map<String, List<String>> = mapOf(
        "name" to listOf("名称", "标题", "名字", "条目名", "平台", "平台名", "应用", "应用名", "网站名", "账号名", "项目", "站点名"),
        "username" to listOf("用户", "用户名", "账号", "帐号", "登录名", "登录账号", "邮箱", "email", "e-mail", "mail"),
        "password" to listOf("密码", "口令", "pwd", "pass", "密碼"),
        "website" to listOf("网站", "网址", "url", "链接", "域名", "link", "主页", "站点"),
        "category" to listOf("分类", "类别", "分组", "目录"),
        "notes" to listOf("备注", "说明", "注释", "note", "comment", "备注信息")
    )

    private val aliasToKey: Map<String, String> = buildMap {
        FIELD_LABELS.forEach { (k, aliases) -> aliases.forEach { a -> put(a, k) } }
    }
    private val bigLabel: String = FIELD_LABELS.values.flatten().joinToString("|") { Regex.escape(it) }
    private val fieldRegex = Regex(
        "($bigLabel)(?:\\s*[:：=]\\s*|\\s+)(.*?)(?=\\s*(?:$bigLabel)(?:\\s*[:：=]|\\s+)|$)",
        RegexOption.MULTILINE
    )

    fun parse(text: String): List<ParsedEntry> {
        val blocks = splitBlocks(text)
        val result = mutableListOf<ParsedEntry>()
        for (block in blocks) {
            val entry = ParsedEntry()
            val nameCandidates = mutableListOf<String>()
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines) {
                val matches = fieldRegex.findAll(line).toList()
                if (matches.isEmpty()) {
                    // 整行无标签 -> 标题候选 或 备注
                    if (entry.name.isEmpty() && nameCandidates.isEmpty()) nameCandidates.add(line)
                    else entry.notes = (entry.notes + " " + line).trim()
                    continue
                }
                for (m in matches) {
                    val label = m.groupValues[1]
                    val key = aliasToKey[label] ?: continue
                    val value = m.groupValues[2].trim()
                    if (value.isEmpty()) continue
                    // 字段已填充时，同字段的后续标签值保留进备注（原实现静默丢弃）。
                    // 典型场景：同一条目里既写「账号：a」又写「邮箱：b」（两个别名同映射 username）。
                    val filled = when (key) {
                        "name" -> entry.name.isNotEmpty()
                        "username" -> entry.username.isNotEmpty()
                        "password" -> entry.password.isNotEmpty()
                        "website" -> entry.website.isNotEmpty()
                        "category" -> entry.category.isNotEmpty()
                        else -> false
                    }
                    if (key != "notes" && filled) {
                        entry.notes = (entry.notes + " " + label + "：" + value).trim()
                        continue
                    }
                    when (key) {
                        "name" -> if (entry.name.isEmpty()) entry.name = value
                        "username" -> if (entry.username.isEmpty()) entry.username = value
                        "password" -> if (entry.password.isEmpty()) entry.password = value
                        "website" -> if (entry.website.isEmpty()) entry.website = value
                        "category" -> if (entry.category.isEmpty()) entry.category = value
                        "notes" -> entry.notes = (entry.notes + " " + value).trim()
                    }
                }
                // 本行被标签消费后残余文本（如行首标题词）
                val consumed = matches.fold(line) { acc, m -> acc.replace(m.value, "") }.trim()
                if (consumed.isNotEmpty()) {
                    if (entry.name.isEmpty() && nameCandidates.isEmpty()) nameCandidates.add(consumed)
                    else entry.notes = (entry.notes + " " + consumed).trim()
                }
            }
            if (entry.name.isEmpty()) {
                entry.name = nameCandidates.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: entry.username.takeIf { it.isNotBlank() }
                    ?: entry.website.takeIf { it.isNotBlank() }
                    ?: "未命名条目"
            }
            entry.notes = entry.notes.trim()
            result.add(entry)
        }
        return result.filter {
            it.name.isNotBlank() || it.username.isNotBlank() || it.password.isNotBlank() || it.website.isNotBlank()
        }
    }

    /** 只统计本行有「非空值」的字段标签（原文），避免「我的账号…」这类误判。 */
    private fun fieldLabelsInLine(line: String): Set<String> =
        fieldRegex.findAll(line).mapNotNull { m ->
            val label = m.groupValues[1]
            aliasToKey[label] ?: return@mapNotNull null
            if (m.groupValues[2].trim().isEmpty()) null else label
        }.toSet()

    private fun splitBlocks(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val byBlank = trimmed.split(Regex("\\n\\s*\\n"))
        if (byBlank.size > 1) return byBlank.map { it.trim() }.filter { it.isNotEmpty() }

        // 无空行：按行分组。切分规则（2026-09-06 修复精化）：
        //  a) 无标签行：仅当当前块已含字段标签时才开新条——标题后紧跟的裸行（如裸邮箱）归入同条，
        //     避免「Github / me@x.com / 密码：p」被拆成 [Github] + [me@x.com+密码] 两条；
        //  b) 相同标签重复（账号…账号）=> 新条目。同字段不同别名（账号/邮箱）不拆，
        //     重复值由 parse() 保留进备注（原按"字段键重复"切分会把 账号+邮箱 误拆成两条）。
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = mutableListOf<StringBuilder>()
        var cur = StringBuilder()
        var used = mutableSetOf<String>()
        for (line in lines) {
            val labels = fieldLabelsInLine(line)
            val startsNew = when {
                cur.isEmpty() -> false                    // 首行不开新块
                labels.isEmpty() -> used.isNotEmpty()     // 无标签行：当前块已有字段才切分
                labels.any { it in used } -> true         // 相同标签重复 => 新条目
                else -> false
            }
            if (startsNew) {
                if (cur.isNotBlank()) blocks.add(cur)
                cur = StringBuilder()
                used = mutableSetOf()
            }
            cur.appendLine(line)
            used.addAll(labels)
        }
        if (cur.isNotBlank()) blocks.add(cur)
        return blocks.map { it.toString().trim() }.filter { it.isNotBlank() }
    }
}
