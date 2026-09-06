package vault

// ============================================================================
// Schema.kt：DDL 单一事实源（契约 schema.sql）。纯 Kotlin，零 Android 依赖。
//  - Android 侧 VaultDbHelper.onCreate 执行本列表；JVM 契约测试（tests/contract）也执行本列表
//    于 sqlite-jdbc，保证两端 DDL 完全一致，无漂移。
//  - 与 schema.sql 逐条一致（不含 PRAGMA/注释）。
//  ponytail: DDL 以内联语句列表形式提供，而非运行时读取 assets/schema.sql，避免 Android 资源路径
//  耦合与 IO；测试侧则直接复用本列表，保证"同一份 DDL"。 | 升级阈值：若 DDL 需版本化迁移，
//  在此按 DB_VERSION 增加迁移语句，并让 VaultDbHelper.onUpgrade 调用。
// ============================================================================

val VAULT_SCHEMA_STATEMENTS: List<String> = listOf(
    """
    CREATE TABLE categories (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        name        TEXT    NOT NULL,
        sort_order  INTEGER NOT NULL DEFAULT 0,
        created_at  INTEGER NOT NULL
    )
    """,
    "CREATE UNIQUE INDEX uq_categories_name ON categories(name)",
    """
    CREATE TABLE password_entries (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        name        TEXT    NOT NULL,
        username    TEXT    NOT NULL DEFAULT '',
        secret_blob BLOB    NOT NULL,
        category_id INTEGER,
        created_at  INTEGER NOT NULL,
        updated_at  INTEGER NOT NULL,
        is_deleted  INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL
    )
    """,
    "CREATE INDEX idx_entries_name     ON password_entries(name)",
    "CREATE INDEX idx_entries_username ON password_entries(username)",
    "CREATE INDEX idx_entries_cat_name ON password_entries(category_id, name)",
    "CREATE INDEX idx_entries_deleted  ON password_entries(is_deleted)",
    """
    CREATE TABLE app_settings (
        id                        INTEGER PRIMARY KEY CHECK (id = 1),
        initialized              INTEGER NOT NULL DEFAULT 0,
        kdf_algo                 TEXT    NOT NULL DEFAULT 'argon2id',
        kdf_memory_kb            INTEGER NOT NULL DEFAULT 32768,
        kdf_iterations           INTEGER NOT NULL DEFAULT 2,
        kdf_parallelism          INTEGER NOT NULL DEFAULT 1,
        kdf_salt                 BLOB    NOT NULL,
        wrapped_dek              BLOB    NOT NULL,
        verifier                 BLOB    NOT NULL,
        wrapped_dek_biometric    BLOB,
        auto_lock_timeout_sec    INTEGER NOT NULL DEFAULT 60,
        clipboard_clear_delay_sec INTEGER NOT NULL DEFAULT 30,
        theme                    TEXT    NOT NULL DEFAULT 'system',
        biometric_enabled        INTEGER NOT NULL DEFAULT 0,
        created_at              INTEGER NOT NULL,
        updated_at              INTEGER NOT NULL
    )
    """
)
