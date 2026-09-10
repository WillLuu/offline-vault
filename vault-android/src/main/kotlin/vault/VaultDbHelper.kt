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
private const val DB_VERSION = 2

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
    // （事务内、每段幂等）；永不修改已发布版本的 onCreate 语义。
    // v1→v2（全加密）：仅结构变更（加 name_blob 列、删明文相关索引），无需 DEK；
    // 数据迁移（明文名折进密文）在解锁后由 migrateVaultDataIfNeeded 执行。
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        android.util.Log.i("VaultDbHelper", "DB upgrade $oldVersion -> $newVersion")
        db.beginTransaction()
        try {
            if (oldVersion < 2) for (stmt in VAULT_MIGRATE_V1_TO_V2) db.execSQL(stmt)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
