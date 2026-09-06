package com.qiqiao.passwordvault.model

// 唯一事实源：api-contract.md §3。纯 Kotlin 数据类，无 Android 依赖（供自检在 JVM 直跑）。

// 列表行：password/website/notes 仅 getEntry 回填，列表展示时为空
data class PasswordEntryRow(
    val id: Long,
    val name: String,
    val username: String,
    val password: String = "",          // 仅 getEntry 回填
    val website: String = "",           // 仅 getEntry 回填
    val notes: String = "",             // 仅 getEntry 回填
    val categoryId: Long?,
    val categoryName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean = false,
    val extras: List<ExtraField> = emptyList()   // 自定义词条，仅 getEntry 回填
)

// 新增/编辑输入
data class EntryInput(
    val name: String,
    val username: String,
    val password: String,
    val website: String,
    val notes: String,
    val categoryId: Long? = null,
    val extras: List<ExtraField> = emptyList()   // 自定义词条（2026-09-06）
)

// 自定义词条（label 用户可编辑；与平台/帐号/密码并列的附加字段）。
data class ExtraField(val label: String, val value: String)

// 分类行：含条目计数（防 N+1，单 JOIN+GROUP BY）
data class CategoryRow(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val entryCount: Int,
    val createdAt: Long
)

// 用户设置（不含任何密钥材料）
data class AppSettings(
    val initialized: Boolean,
    val autoLockTimeoutSec: Int,
    val clipboardClearDelaySec: Int,
    val theme: String,                  // light | dark | system
    val biometricEnabled: Boolean
)

// 设置局部更新补丁（可空子集）
data class SettingsPatch(
    val autoLockTimeoutSec: Int? = null,
    val clipboardClearDelaySec: Int? = null,
    val theme: String? = null,
    val biometricEnabled: Boolean? = null
)

// 导入合并报告
data class MergeReport(
    val categoriesAdded: Int,
    val categoriesMerged: Int,
    val entriesAdded: Int,
    val entriesUpdated: Int,
    val entriesSkipped: Int
)

// UPDATED_DESC：按更新时间倒序（2026-09-06 用户定稿：列表默认排序，最新更新的卡片在最前）。
enum class SortKey { NAME_ASC, NAME_DESC, CATEGORY_ASC, CATEGORY_DESC, UPDATED_DESC }

// 种子分类中不可删除的兜底分类名
const val DEFAULT_CATEGORY_NAME = "其他"
