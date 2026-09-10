package com.qiqiao.passwordvault.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.qiqiao.passwordvault.model.AppSettings
import com.qiqiao.passwordvault.model.CategoryRow
import com.qiqiao.passwordvault.model.EntryInput
import com.qiqiao.passwordvault.model.ExtraField
import com.qiqiao.passwordvault.model.MergeReport
import com.qiqiao.passwordvault.model.PasswordEntryRow
import com.qiqiao.passwordvault.model.SettingsPatch
import com.qiqiao.passwordvault.model.SortKey
import vault.AppSettingsStore
import vault.CategoryDao
import vault.PasswordEntryDao
import vault.UnlockManager
import vault.VaultBackup
import vault.VaultDbHelper
import vault.VaultException
import vault.WrongPasswordException
import vault.PasswordEntryRow as BackendEntryRow
import vault.EntryInput as BackendEntryInput
import vault.CategoryRow as BackendCategoryRow
import vault.AppSettings as BackendAppSettings
import vault.SettingsPatch as BackendSettingsPatch
import vault.MergeReport as BackendMergeReport
import vault.SortKey as BackendSortKey
import vault.ExtraField as BackendExtraField
import java.io.File
import java.util.concurrent.Executors
import javax.crypto.Cipher  // 生物识别两段式：Cipher 实例在 prepare*/complete* 间传递（#QA-029）

// ============================================================================
// RealVaultData：VaultData 接口的真实实现，桥接前端 UI 与 backend/ 的 vault.* DAO。
//  - 编译单元：本文件与 backend/src/vault/*.kt 一同作为 frontend app module 的源码集引入，
//    使 com.qiqiao.passwordvault.data 与 vault 同编译单元（集成方式见 backend/README.md）。
//  - 异步模型：接口全部回调（Done<T>）。每个方法在后台线程执行同步 DAO 调用，
//    结果/异常经主线程 Handler 回调（Android 要求 DB/文件/Keystore 离主线程）。
//  - 不依赖任何第三方协程库：单线程 Executor + Handler 即最小可行方案（决策阶梯 5 级）。
//  - 类型映射：后端 PasswordEntryRow 的 password/website/notes/categoryName 为 String?，
//    前端为 String（默认 ""）。映射时把 null 填成 ""（列表掩码行本就为 null，前端展示即空）。
// ============================================================================

class RealVaultData(context: Context) : VaultData {

    // 单线程 Executor：序列化所有 DAO 调用，避免 SQLite 并发写竞争（默认未开 WAL）。
    // ponytail: 用单线程池而非连接池/协程；DB 调用量小，串行足够且无并发写风险。 | 升级阈值：若需并行读以降延迟，改 CachedThreadPool 并开启 SQLiteDatabase.enableWriteAheadLogging()。
    private val executor = Executors.newSingleThreadExecutor()

    // 主线程回调：所有 Done<T> 回调必须在主线程（UI 层假设回调在主线程）。
    // ponytail: 直用 Handler(Looper.getMainLooper())，不引 HandlerThread/Lifecycle 封装（YAGNI）。 | 升级阈值：若需生命周期感知自动摘除，改 Lifecycle-aware 分发。
    private val mainHandler = Handler(Looper.getMainLooper())

    // 持有构造 Context 的 application filesDir（MainApplication.onCreate 传入 applicationContext，进程级有效，无泄漏风险）。
    // ponytail: 直接持 Application Context 的 filesDir 引用；不持 Activity/Fragment Context 以防泄漏。 | 升级阈值：若需在 onTrimMemory 释放，改弱引用。
    private val appFilesDir: File = context.filesDir

    // 打开库（onCreate 应用 schema）。库随 App 进程存活，不主动关闭。
    // 审计 2026-09-06 #QA-007 加固：不再在 Application.onCreate 主线程同步建表——
    // db 懒初始化（首次真正访问才打开），且构造时即向同一单线程 executor 入队后台预热，
    // FIFO 保证任何 runAsync 数据操作前库已打开、建表 DDL 全程在后台线程执行。
    private val dbHelper by lazy { VaultDbHelper(context) }
    private val db: android.database.sqlite.SQLiteDatabase by lazy { dbHelper.writableDatabase }

    // 解锁会话（持有内存 DEK）。#QA-029：注入 BiometricKeystore，生物识别实装。
    // 懒初始化：解锁相关 DB 访问同样首落在后台 runAsync 线程；lock/isLocked 纯内存不触发建库。
    private val unlock by lazy { UnlockManager(db, vault.BiometricKeystore()) }

    // DAO 们。getDek 由 UnlockManager 提供活跃 DEK。
    private val getDek: () -> ByteArray? = { unlock.getActiveDek() }
    private val entryDao by lazy { PasswordEntryDao(db, getDek) }
    private val categoryDao by lazy { CategoryDao(db, getDek) }
    private val settingsStore by lazy { AppSettingsStore(db) }
    private val backup by lazy { VaultBackup(db, getDek, settingsStore) }

    init {
        // 后台预热：首启建表/开库不占主线程（#QA-007）。单线程池 FIFO → 先于一切 runAsync。
        executor.execute { db }
    }

    // ---- 通用异步包裹：后台执行 work，主线程回调 Result ----
    private fun <T> runAsync(work: () -> T, cb: Done<T>) {
        executor.execute {
            try {
                val result = work()
                mainHandler.post { cb(Result.success(result)) }
            } catch (e: Throwable) {
                mainHandler.post { cb(Result.failure(e)) }
            }
        }
    }

    // ---- 解锁 / 初始化（契约 §5）----

    override fun isInitialized(cb: Done<Boolean>) =
        runAsync({ settingsStore.isInitialized() }, cb)

    override fun initializeMasterPassword(password: String, cb: Done<Boolean>) =
        runAsync({ unlock.initializeMasterPassword(password) }, cb)

    override fun unlockWithPassword(password: String, cb: Done<Boolean>) =
        runAsync({ unlock.unlockWithPassword(password) }, cb)

    override fun lockoutRemainingMs(): Long = unlock.lockoutRemainingMs()

    // ---- 生物识别（#QA-029 两段式）：prepare* 段 1 在后台 init Cipher（Keystore 操作离主线程）；
    // 认证成功后 UI 携同一 Cipher 实例回调 complete*/unlockWithBiometric。prepare 抛
    // BiometricUnavailableException（未开启/无配置）或 Keystore 异常（密钥失效）时由 UI 层处理。 ----
    override fun prepareBiometricWrap(cb: Done<Cipher>) =
        runAsync({ unlock.prepareBiometricWrapCipher() }, cb)

    override fun completeBiometricEnable(cipher: Cipher, cb: Done<Boolean>) =
        runAsync({ unlock.completeBiometricEnable(cipher) }, cb)

    override fun prepareBiometricUnlock(cb: Done<Cipher>) =
        runAsync({ unlock.prepareBiometricUnlockCipher() }, cb)

    override fun unlockWithBiometric(cipher: Cipher, cb: Done<Boolean>) =
        runAsync({ unlock.unlockWithBiometric(cipher) }, cb)

    override fun disableBiometric(cb: Done<Boolean>) =
        runAsync({ unlock.disableBiometric(); true }, cb)

    // 解锁会话：纯内存操作（清/读 DEK 标志），接口定义为同步无回调，直接委托 UnlockManager。
    // 与其余异步方法不同，不套 runAsync（同步委托即可）。
    // ponytail: lock/isLocked 同步委托 UnlockManager，与 Mock 对称；不暴露 getActiveDek 到 UI 以防 DEK 泄露。 | 升级阈值：若 UI 需主动锁并等完成回调，改异步。
    override fun lock() = unlock.lock()
    override fun isLocked(): Boolean = unlock.isLocked()

    // ---- 密码条目（契约 §3.1）----

    override fun listEntries(
        search: String?,
        sortBy: SortKey,
        categoryId: Long?,
        limit: Int,
        offset: Int,
        cb: Done<List<PasswordEntryRow>>
    ) = runAsync({
        entryDao.listEntries(search, toBackendSortKey(sortBy), categoryId, limit, offset)
            .map { mapEntryRow(it) }
    }, cb)

    override fun getEntry(id: Long, cb: Done<PasswordEntryRow?>) =
        runAsync({ entryDao.getEntry(id)?.let { mapEntryRow(it) } }, cb)

    override fun createEntry(input: EntryInput, cb: Done<Long>) =
        runAsync({ entryDao.createEntry(toBackendEntryInput(input)) }, cb)

    override fun updateEntry(id: Long, input: EntryInput, cb: Done<Boolean>) =
        runAsync({ entryDao.updateEntry(id, toBackendEntryInput(input)) }, cb)

    override fun deleteEntry(id: Long, cb: Done<Boolean>) =
        runAsync({ entryDao.deleteEntry(id) }, cb)

    // ---- 分类（契约 §3.2）----

    override fun listCategories(cb: Done<List<CategoryRow>>) =
        runAsync({ categoryDao.listCategories().map { mapCategoryRow(it) } }, cb)

    override fun createCategory(name: String, sortOrder: Int, cb: Done<Long>) =
        runAsync({ categoryDao.createCategory(name, sortOrder) }, cb)

    override fun updateCategory(id: Long, name: String?, sortOrder: Int?, cb: Done<Boolean>) =
        runAsync({ categoryDao.updateCategory(id, name, sortOrder) }, cb)

    override fun deleteCategory(id: Long, cb: Done<Boolean>) =
        runAsync({ categoryDao.deleteCategory(id) }, cb)

    override fun reorderCategories(orderedIds: List<Long>, cb: Done<Boolean>) =
        runAsync({ categoryDao.reorderCategories(orderedIds) }, cb)

    // ---- 设置（契约 §3.3）----

    override fun getSettings(cb: Done<AppSettings>) =
        runAsync({ mapSettings(settingsStore.getSettings()) }, cb)

    override fun updateSettings(patch: SettingsPatch, cb: Done<Boolean>) =
        runAsync({ settingsStore.updateSettings(toBackendSettingsPatch(patch)) }, cb)

    // 改主密码（契约 §3.3）：委托 AppSettingsStore.changeMasterPassword（DEK 不变、KEK 用毕 zeroBytes，均在 Store 内）。
    // 旧密码错误（Store 返回 false）-> WrongPasswordException；成功 -> 会话保持、历史条目可解密。
    // newPassword 空/过短：AppSettingsStore 未做，信任边界最小前置校验，显式抛 VaultException 而非静默失败。
    override fun changeMasterPassword(oldPassword: String, newPassword: String, cb: Done<Unit>) =
        runAsync({
            if (newPassword.isBlank() || newPassword.length < MIN_NEW_PASSWORD_LEN) {
                throw VaultException("新主密码为空或过短")
            }
            if (!settingsStore.changeMasterPassword(oldPassword, newPassword)) {
                throw WrongPasswordException() // 旧密码错误
            }
        }, cb)

    // ---- 备份（契约 §3.4）----

    // 导出：调 vault 层拿 .vault 字节 -> 写入应用私有存储 -> 回调文件名。
    override fun exportVault(password: String, useMasterPassword: Boolean, cb: Done<String>) =
        runAsync({
            val bytes = backup.exportVault(password, useMasterPassword)
            val name = "vault_export_${System.currentTimeMillis()}.vault"
            // ponytail: 文件名规则 vault_export_<epochMillis>.vault，写 context.filesDir（应用私有，无需权限）。 | 升级阈值：若需用户可选下载目录（外部存储），改 MediaStore/SAF 并申请权限。
            File(appFilesDir, name).writeBytes(bytes)
            // 仅返回文件名（契约要求 String 文件名），不返绝对路径（UI 仅需标识）。
            // ponytail: 返回文件名而非全路径，保持接口最小且规避路径泄露。 | 升级阈值：若 UI 需读回文件，改返绝对路径或 Content URI。
            name
        }, cb)

    override fun importVault(fileBytes: ByteArray, password: String, cb: Done<MergeReport>) =
        runAsync({ mapReport(backup.importVault(fileBytes, password)) }, cb)
}

// ============================================================================
// 纯映射函数（文件私有，零 Android 依赖）：后端 vault 模型 <-> 前端 model。
// 集中此处便于自检与复用，RealVaultData 直接调用。
// ============================================================================

// 后端 password/website/notes/categoryName 为 String?；前端为 String（默认 ""）。
// 唯一边界：null -> ""（列表掩码行在列表态本就为 null，前端展示即空串）。
private fun mapEntryRow(v: BackendEntryRow): PasswordEntryRow = PasswordEntryRow(
    id = v.id,
    name = v.name,
    username = v.username,
    password = v.password ?: "",
    website = v.website ?: "",
    notes = v.notes ?: "",
    categoryId = v.categoryId,
    categoryName = v.categoryName ?: "",
    createdAt = v.createdAt,
    updatedAt = v.updatedAt,
    isDeleted = v.isDeleted,
    extras = v.extras.map { ExtraField(it.label, it.value) }
)

// 字段一致（仅声明顺序不同），逐字段映射。
private fun mapCategoryRow(v: BackendCategoryRow): CategoryRow = CategoryRow(
    id = v.id,
    name = v.name,
    sortOrder = v.sortOrder,
    entryCount = v.entryCount,
    createdAt = v.createdAt
)

// 字段完全一致。
private fun mapSettings(v: BackendAppSettings): AppSettings = AppSettings(
    initialized = v.initialized,
    autoLockTimeoutSec = v.autoLockTimeoutSec,
    clipboardClearDelaySec = v.clipboardClearDelaySec,
    theme = v.theme,
    biometricEnabled = v.biometricEnabled
)

// 字段完全一致。
private fun mapReport(v: BackendMergeReport): MergeReport = MergeReport(
    categoriesAdded = v.categoriesAdded,
    categoriesMerged = v.categoriesMerged,
    entriesAdded = v.entriesAdded,
    entriesUpdated = v.entriesUpdated,
    entriesSkipped = v.entriesSkipped
)

// 字段完全一致。
private fun toBackendEntryInput(v: EntryInput): BackendEntryInput = BackendEntryInput(
    name = v.name,
    username = v.username,
    password = v.password,
    website = v.website,
    notes = v.notes,
    categoryId = v.categoryId,
    extras = v.extras.map { BackendExtraField(it.label, it.value) }
)

// 字段完全一致。
private fun toBackendSettingsPatch(v: SettingsPatch): BackendSettingsPatch = BackendSettingsPatch(
    autoLockTimeoutSec = v.autoLockTimeoutSec,
    clipboardClearDelaySec = v.clipboardClearDelaySec,
    theme = v.theme,
    biometricEnabled = v.biometricEnabled
)

// SortKey 两端枚举成员完全一致，按名映射即可。
private fun toBackendSortKey(k: SortKey): BackendSortKey = BackendSortKey.valueOf(k.name)

// ============================================================================
// 自检（静默、纯函数、无 Android/DB/文件）：校验 null->"" 与 SortKey 映射不变量。
// 注：RealVaultData 是 Android 胶水类（依赖 Context/Looper），不在 JVM 契约测试编译集内；
// 本自检在 Android module 下运行（如 instrumented test 或 MainApplication.onCreate debug 分支）。
// ponytail: 自检不入 JVM 契约集因类依赖 Android 主线程；映射边界仅在 Android 集成验证。 | 升级阈值：若拆分出独立 JVM 映射模块，将其纳入契约自检。
// 运行：在 Android 测试/调试入口调用 realVaultDataSelfTest()，无异常即通过。
// ============================================================================
fun realVaultDataSelfTest() {
    // 掩码行：后端 null 字段 -> 前端 ""
    val masked = BackendEntryRow(
        id = 1, name = "微信", username = "u",
        password = null, website = null, notes = null,
        categoryId = null, categoryName = null,
        createdAt = 0, updatedAt = 0, isDeleted = false
    )
    val fm = mapEntryRow(masked)
    check(fm.password == "" && fm.website == "" && fm.notes == "" && fm.categoryName == "") {
        "null 字段必须映射为前端空串"
    }

    // 回填行：后端明文 -> 前端原值
    val filled = BackendEntryRow(
        id = 2, name = "支付宝", username = "u",
        password = "p", website = "w", notes = "n",
        categoryId = 3, categoryName = "支付",
        createdAt = 10, updatedAt = 20, isDeleted = false
    )
    val ff = mapEntryRow(filled)
    check(ff.password == "p" && ff.website == "w" && ff.notes == "n" && ff.categoryName == "支付") {
        "非空字段必须原值映射"
    }

    // SortKey 双向名映射一致
    for (k in SortKey.values()) {
        check(toBackendSortKey(k).name == k.name) { "SortKey 名映射必须一致: $k" }
    }
}

// 信任边界最小新密码长度（契约未固化，取保守下限；AppSettingsStore 未校验，桥接层补上，防空/过短密码派生弱 KEK）。
// ponytail: 未做强度策略（无 zxcvbn/策略 UI，YAGNI）。 | 升级阈值：产品定义密码强度策略后引入。
// 2026-09-06 审查 F3：由 4 提到 8，与独立导出密码 MIN_EXPORT_PW_LEN(8) 对齐——
// 主密码保护整库，下限不应低于单次导出密码。仅影响「设置/修改」主密码，已存在的短密码仍可正常解锁。
private const val MIN_NEW_PASSWORD_LEN = 8
