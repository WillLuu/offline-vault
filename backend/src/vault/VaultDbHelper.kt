package vault

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// ============================================================================
// SQLiteOpenHelper：执行 schema.sql 的 DDL（契约 §schema.sql）。
// DDL 取自 Schema.kt 的 VAULT_SCHEMA_STATEMENTS（与 schema.sql 严格一致的单一事实源），
// 避免运行时读取 assets/.sql 文件，且保证 Android 与 JVM 契约测试共用同一份 DDL。
// ponytail: DDL 内联于 Schema.kt 而非读取外部文件，避免 assets 路径耦合与 IO；若 DDL 需版本化迁移，改回读取迁移脚本。
// | 升级阈值：DB_VERSION 需递增以应用 ALTER 迁移时。
// ============================================================================

private const val DB_NAME = "vault.db"
private const val DB_VERSION = 1

class VaultDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            for (stmt in VAULT_SCHEMA_STATEMENTS) db.execSQL(stmt)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- 版本化迁移（审计 2026-09-06 加固）----
    // 规则：schema 变更时递增 DB_VERSION，并在此按 oldVersion→newVersion 逐段执行迁移
    // （事务内、每段幂等）；永不修改已发布版本的 onCreate 语义。当前 v1 为首发，无历史段。
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        android.util.Log.i("VaultDbHelper", "DB upgrade $oldVersion -> $newVersion")
        db.beginTransaction()
        try {
            // 版本迁移按从小到大顺序执行；v1 为基线，无前置迁移段。
            // 示例（未来 v2 增加列）：
            // if (oldVersion < 2) db.execSQL("ALTER TABLE ... ADD COLUMN ...")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
