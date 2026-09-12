package vault.desktop.app

import java.util.prefs.Preferences

// ============================================================================
// DesktopFieldTemplate：全局自定义词条模板（与移动端 FieldTemplateStore 语义一致）。
//  - 固定词条 平台/帐号/密码 不进模板（永不可删）；模板默认 = 邮箱 / 网站 / 备注。
//  - 纯 UI 元数据，存 java.util.prefs（与 themeMode 同 node），不入库、不进加密层。
//  - 从模板删除某词条只隐藏行；条目已填值仍存于其加密 extras，重新加回同名即复现。
//  - 跨设备不同步（与移动端一致：模板是各端本地 UI 偏好）。
//  - 分隔符用不可见控制字符 \u0001（词条名不含控制字符，安全）。
// ============================================================================

object DesktopFieldTemplate {
    private val node: Preferences get() = Preferences.userRoot().node("offline-vault-desktop")
    private const val KEY = "fieldTemplate"
    private const val SEP = "\u0001"

    /** 模板仅存"自定义词条"（手机/邮箱/网站/备注 为内置固定字段，始终显示、不入模板）。默认无自定义。 */
    val DEFAULTS = emptyList<String>()

    fun load(): MutableList<String> {
        val raw = node.get(KEY, null) ?: return DEFAULTS.toMutableList()
        val list = raw.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        return if (list.isEmpty()) DEFAULTS.toMutableList() else list
    }

    fun save(labels: List<String>) { node.put(KEY, labels.joinToString(SEP)) }

    /** 追加并持久化；已存在返回 false。 */
    fun add(label: String): Boolean {
        val l = load()
        if (l.contains(label)) return false
        l.add(label); save(l); return true
    }

    /** 移除并持久化。 */
    fun remove(label: String) {
        val l = load()
        if (l.remove(label)) save(l)
    }

    /** 重命名模板词条并持久化；新名已存在或旧名不存在返回 false。 */
    fun rename(oldLabel: String, newLabel: String): Boolean {
        val l = load()
        val i = l.indexOf(oldLabel)
        if (i < 0 || l.contains(newLabel)) return false
        l[i] = newLabel; save(l); return true
    }
}
