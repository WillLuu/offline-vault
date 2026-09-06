package vault

// 排序键（契约 §3.1）。CATEGORY_* 仅按 category_id 排序（见 SqlBuilders ponytail）。
// UPDATED_DESC：按更新时间倒序（2026-09-06 用户定稿：列表默认排序，最新更新的卡片在最前）。
enum class SortKey { NAME_ASC, NAME_DESC, CATEGORY_ASC, CATEGORY_DESC, UPDATED_DESC }

// 列表查询默认取全量（审查 2.1 方案 A）。
// 背景：原默认 limit=100 且 UI 无任何翻页/触底加载控件，导致第 101 条起永久不可见、连搜索都搜不到——
// 这是"有分页的性能假设、没分页的可达性"的最差组合。本地单用户 SQLite 库条数量级仅百~千，
// 全量查询无压力，交由 RecyclerView 虚拟化渲染即可彻底消除该天花板。
// limit/offset 参数保留，供未来若条目量真的大到需要分页时启用（届时须配套触底加载 UI）。
const val LIST_NO_LIMIT = Int.MAX_VALUE

// 新增/修改条目的输入（契约 §3.1）。
// extras：自定义词条（2026-09-06 用户需求：平台/帐号/密码固定，其余词条可增删）。
data class EntryInput(
    val name: String,
    val username: String,
    val password: String,
    val website: String,
    val notes: String,
    val categoryId: Long?,
    val extras: List<ExtraField> = emptyList()
)

// 自定义词条（label 用户可编辑；与平台/帐号/密码并列的附加字段）。
data class ExtraField(val label: String, val value: String)

// 列表行：password/website/notes 仅 getEntry 回填，列表态为 null（掩码）。
data class PasswordEntryRow(
    val id: Long,
    val name: String,
    val username: String,
    val password: String?,
    val website: String?,
    val notes: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val isDeleted: Boolean,
    val extras: List<ExtraField> = emptyList()   // 仅 getEntry 回填，列表态为空
)

// 分类行（含条目计数，防 N+1）。
data class CategoryRow(
    val id: Long,
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
    val entryCount: Int
)

// 用户设置（不含任何密钥材料）。
data class AppSettings(
    val initialized: Boolean,
    val autoLockTimeoutSec: Int,
    val clipboardClearDelaySec: Int,
    val theme: String,
    val biometricEnabled: Boolean
)

// 设置局部更新补丁（可空子集）。
data class SettingsPatch(
    val autoLockTimeoutSec: Int? = null,
    val clipboardClearDelaySec: Int? = null,
    val theme: String? = null,
    val biometricEnabled: Boolean? = null
)

// 导入合并报告（契约 §3.4）。
data class MergeReport(
    val categoriesAdded: Int,
    val categoriesMerged: Int,
    val entriesAdded: Int,
    val entriesUpdated: Int,
    val entriesSkipped: Int
)

// secret_blob 明文结构（契约 §4.3）：{ password, website, notes }。
// 解密后的 secret 明文。extras 仅在非空时写入 JSON "extras" 键（旧记录/旧导出文件无该键，向后兼容）。
data class SecretPlain(
    val password: String,
    val website: String,
    val notes: String,
    val extras: List<ExtraField> = emptyList()
)

// 导出荷载中的条目（secret 为解密后的明文，由导出密钥整体重加密）。
data class ExportEntry(
    val name: String,
    val username: String,
    val categoryName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val secret: SecretPlain
)

data class ExportCategory(val name: String, val sortOrder: Int)

// 导出荷载（契约 §4.4 注释）。
data class VaultPayload(
    val version: Int,
    val categories: List<ExportCategory>,
    val entries: List<ExportEntry>
)

// 导入时的轻量条目元数据（用于合并决策，不解密当前库；带 id 以便决策直接给出现有记录 id）。
data class EntryMeta(
    val id: Long,
    val name: String,
    val username: String,
    val categoryName: String?,
    val updatedAt: Long
)

// 当前 epoch 毫秒（纯标准库，Android/JVM 通用）。
fun nowMillis(): Long = System.currentTimeMillis()

// 业务异常（防静默失败底线：加密/解密错误必须显式抛出，而非吞掉）。
open class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
class LockedException : VaultException("VAULT_LOCKED")          // DAO 在锁定态被调用
class WrongPasswordException : VaultException("WRONG_PASSWORD")  // .vault 解密 GCM 认证失败
class BiometricUnavailableException : VaultException("BIOMETRIC_UNAVAILABLE")
