package vault.desktop

import java.io.File

// ============================================================================
// DesktopVault：桌面数据层门面（M3 交付物，M5 的 Compose UI 直接消费）。
//  - 同步 API（与 Android 的回调式 RealVaultData 不同：桌面 JVM 无主线程约束，UI 侧自行后台化）。
//  - 单连接 + synchronized 串行化所有 DB 操作：镜像 Android 单线程 Executor 模型，
//    规避 SQLite 并发写竞争（未开 WAL）。
//  - 生命周期：进程内常驻；lock() 只清 DEK 不关库。
// ============================================================================

class DesktopVault(dbFile: File = DesktopDb.defaultVaultFile()) : AutoCloseable {

    // 暴露底层句柄：自检直读 secret_blob 验证"密文≠明文"；M5 高级用途（如备份读写）亦经此。
    val db: DesktopDb = DesktopDb.open(dbFile)
    val unlock: DesktopUnlockManager = DesktopUnlockManager(db)
    private val entryDao = DesktopEntryDao(db.connection) { unlock.getActiveDek() }
    private val categoryDao = DesktopCategoryDao(db.connection)
    val settings: DesktopSettingsStore = DesktopSettingsStore(db.connection)

    // ---- 解锁 / 初始化（F1/F2） ----
    fun isInitialized(): Boolean = synchronized(db) { unlock.isInitialized() }
    fun initializeMasterPassword(password: String): Boolean = synchronized(db) { unlock.initializeMasterPassword(password) }
    fun unlockWithPassword(password: String): Boolean = synchronized(db) { unlock.unlockWithPassword(password) }
    fun lockoutRemainingMs(): Long = unlock.lockoutRemainingMs()
    fun changeMasterPassword(oldPassword: String, newPassword: String): Boolean =
        synchronized(db) { unlock.changeMasterPassword(oldPassword, newPassword) }
    fun lock() = unlock.lock()
    fun isLocked(): Boolean = unlock.isLocked()

    // ---- 条目（F3/F4） ----
    fun listEntries(
        search: String? = null,
        sortBy: vault.SortKey = vault.SortKey.NAME_ASC,
        categoryId: Long? = null
    ): List<vault.PasswordEntryRow> =
        synchronized(db) { entryDao.listEntries(search, sortBy, categoryId) }

    fun getEntry(id: Long): vault.PasswordEntryRow? = synchronized(db) { entryDao.getEntry(id) }
    fun createEntry(input: vault.EntryInput): Long = synchronized(db) { entryDao.createEntry(input) }
    fun updateEntry(id: Long, input: vault.EntryInput): Boolean = synchronized(db) { entryDao.updateEntry(id, input) }
    fun deleteEntry(id: Long): Boolean = synchronized(db) { entryDao.deleteEntry(id) }

    // ---- 分类（F12 存储侧） ----
    fun listCategories(): List<vault.CategoryRow> = synchronized(db) { categoryDao.listCategories() }
    fun createCategory(name: String, sortOrder: Int): Long = synchronized(db) { categoryDao.createCategory(name, sortOrder) }
    fun updateCategory(id: Long, name: String? = null, sortOrder: Int? = null): Boolean =
        synchronized(db) { categoryDao.updateCategory(id, name, sortOrder) }
    fun deleteCategory(id: Long): Boolean = synchronized(db) { categoryDao.deleteCategory(id) }
    fun reorderCategories(orderedIds: List<Long>): Boolean = synchronized(db) { categoryDao.reorderCategories(orderedIds) }

    // ---- 设置（F8/F9 存储侧） ----
    fun getSettings(): vault.AppSettings = synchronized(db) { settings.getSettings() }
    fun updateSettings(patch: vault.SettingsPatch): Boolean = synchronized(db) { settings.updateSettings(patch) }

    override fun close() = db.close()
}
