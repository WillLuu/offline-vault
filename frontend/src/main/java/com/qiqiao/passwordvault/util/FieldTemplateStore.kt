package com.qiqiao.passwordvault.util

import android.content.Context
import org.json.JSONArray
import com.qiqiao.passwordvault.R

// 词条模板（2026-09-06 用户需求：新增一个词条后所有密码卡片同步）。
// 全局共享、有序、持久化的词条名集合：平台/帐号/密码为固定词条（不进模板，永不可删）；
// 模板默认 = 邮箱 / 网站 / 备注（新增时即出现，可删可再加）。
// 存储：SharedPreferences JSON 数组（纯 UI 元数据，非密钥材料，不上 DB 以免污染加密层）。
// 各卡片已填的词条值仍按标签存放在条目自身的加密 extras 中——模板只决定"显示哪些词条行"，
// 从模板删除某词条只隐藏行，已存值保留（重新加回同名词条即重新可见，不丢数据）。
object FieldTemplateStore {
    private const val PREFS = "field_template"
    private const val KEY = "labels"

    /** 默认词条（按序）：邮箱 / 网站 / 备注。 */
    fun defaults(ctx: Context): List<String> = listOf(
        ctx.getString(R.string.edit_email),
        ctx.getString(R.string.edit_website),
        ctx.getString(R.string.edit_notes)
    )

    fun load(ctx: Context): MutableList<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return defaults(ctx).toMutableList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            if (list.isEmpty()) defaults(ctx).toMutableList() else list
        } catch (e: Exception) {
            defaults(ctx).toMutableList()
        }
    }

    fun save(ctx: Context, labels: List<String>) {
        val arr = JSONArray()
        labels.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** 追加并持久化；已存在返回 false。 */
    fun add(ctx: Context, label: String): Boolean {
        val list = load(ctx)
        if (list.contains(label)) return false
        list.add(label)
        save(ctx, list)
        return true
    }

    /** 移除并持久化。 */
    fun remove(ctx: Context, label: String) {
        val list = load(ctx)
        if (list.remove(label)) save(ctx, list)
    }

    /** 重命名模板词条并持久化；新名已存在返回 false（防止模板内重名）。 */
    fun rename(ctx: Context, oldLabel: String, newLabel: String): Boolean {
        val list = load(ctx)
        val idx = list.indexOf(oldLabel)
        if (idx < 0 || list.contains(newLabel)) return false
        list[idx] = newLabel
        save(ctx, list)
        return true
    }
}
