package vault.desktop

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import vault.VAULT_MIGRATE_V1_TO_V2
import vault.VAULT_SCHEMA_STATEMENTS
import vault.VaultException

// ============================================================================
// DesktopDb：桌面端 SQLite 打开/建表/版本管理（PC 端 PRD §5.1，M3）。
//  - DDL 单一事实源：直接执行 vault-core 的 VAULT_SCHEMA_STATEMENTS（与 Android/契约测试同一份）。
//  - 版本跟踪：PRAGMA user_version（Android 端用 SQLiteOpenHelper DB_VERSION，机制不同、语义对齐）。
//  - 单连接 + 上层串行化（DesktopVault.synchronized）：镜像 Android 单线程 Executor 模型，
//    避免 SQLite 并发写竞争（未开 WAL）。
// ponytail: 数据目录 ACL 加固（仅当前用户可访问）留待 M6 安全收尾统一处理——%APPDATA% 默认
// 已在用户配置文件 ACL 之下，风险有限。 | 升级阈值：威胁模型要求防同用户其他进程时补 icacls。
// ============================================================================

const val DESKTOP_DB_VERSION = 2

class DesktopDb private constructor(val connection: Connection, val dbFile: File) {

    init {
        // sqlite-jdbc 驱动显式加载（Class.forName 幂等；避免个别打包环境下 SPI 自动发现失效）
        companionInstanceLoad()
    }

    private fun companionInstanceLoad() {
        try { Class.forName("org.sqlite.JDBC") } catch (e: ClassNotFoundException) {
            throw VaultException("sqlite-jdbc 驱动缺失", e)
        }
    }

    /** 当前库版本（PRAGMA user_version；0=全新）。 */
    fun userVersion(): Int = connection.createStatement().use { st ->
        st.executeQuery("PRAGMA user_version").let { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }

    private fun setUserVersion(v: Int) {
        connection.createStatement().use { it.execute("PRAGMA user_version = $v") } // 受控整数常量，非用户输入
    }

    companion object {
        /** 默认数据目录：%APPDATA%\OfflineVault（PRD D2 采纳建议值；可用环境变量 OFFLINE_VAULT_DIR 覆盖，便于测试/便携）。 */
        fun defaultDataDir(): File {
            val override = System.getenv("OFFLINE_VAULT_DIR")
            if (!override.isNullOrBlank()) return File(override)
            val appData = System.getenv("APPDATA") ?: System.getProperty("user.home")
            return File(File(appData), "OfflineVault")
        }

        /** 默认库文件：%APPDATA%\OfflineVault\vault.db。 */
        fun defaultVaultFile(): File = File(defaultDataDir(), "vault.db")

        /** 打开（或创建）数据库：v0 建表；版本低于 DESKTOP_DB_VERSION 走迁移段。 */
        fun open(dbFile: File = File(defaultDataDir(), "vault.db")): DesktopDb {
            val firstRun = dbFile.parentFile?.let { !it.exists() } ?: false
            dbFile.parentFile?.mkdirs()
            if (firstRun && dbFile.parentFile?.isDirectory == true) {
                // M6（PRD §5.1）：数据目录仅当前用户 + SYSTEM 可访问（尽力而为，失败退化为默认 ACL）
                DesktopSecurity.hardenDataDir(dbFile.parentFile)
            }
            val conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
            conn.autoCommit = true
            val db = DesktopDb(conn, dbFile)
            when (val v = db.userVersion()) {
                0 -> {
                    conn.autoCommit = false
                    try {
                        conn.createStatement().use { st ->
                            for (stmt in VAULT_SCHEMA_STATEMENTS) st.execute(stmt)
                        }
                        db.setUserVersion(DESKTOP_DB_VERSION)
                        conn.commit()
                    } catch (e: Exception) {
                        conn.rollback(); conn.autoCommit = true; throw e
                    }
                    conn.autoCommit = true
                }
                in 1 until DESKTOP_DB_VERSION -> {
                    // v1→v2 结构迁移（加 name_blob 列、删明文相关索引；无需 DEK）。
                    // 数据迁移（明文名折进密文）在解锁后由 migrateVaultDataIfNeededJdbc 执行。
                    conn.autoCommit = false
                    try {
                        conn.createStatement().use { st -> for (stmt in VAULT_MIGRATE_V1_TO_V2) st.execute(stmt) }
                        db.setUserVersion(DESKTOP_DB_VERSION)
                        conn.commit()
                    } catch (e: Exception) { conn.rollback(); throw e }
                    conn.autoCommit = true
                }
                else -> { /* v > 当前版本：旧程序打开新库，只读兼容即可，不动 schema */ }
            }
            return db
        }

        fun openInMemory(): DesktopDb {
            val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
            conn.autoCommit = true
            val db = DesktopDb(conn, File("vault-mem.db"))
            conn.createStatement().use { st -> for (stmt in VAULT_SCHEMA_STATEMENTS) st.execute(stmt) }
            db.setUserVersion(DESKTOP_DB_VERSION)
            return db
        }
    }

    fun close() { try { connection.close() } catch (_: Exception) {} }
}
