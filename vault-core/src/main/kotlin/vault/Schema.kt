package vault

// ============================================================================
// Schema.kt：DDL 单一事实源（契约 schema.sql）。纯 Kotlin，零 Android 依赖。
//  - Android 侧 VaultDbHelper.onCreate / 桌面侧 DesktopDb.open(0) 执行 VAULT_SCHEMA_STATEMENTS；
//    JVM 契约测试也执行同一列表，保证两端 DDL 完全一致，无漂移。
//
// v2（全加密）：name/username/category-name 等元数据不再以明文存于库中——
//  - password_entries.name / username 退化为占位空串，真值折进 secret_blob 的加密 JSON（见 EntryBlob.kt）。
//  - categories 新增 name_blob（AES-GCM(JSON{name})），name 退化为占位空串；
//    原 uq_categories_name 唯一索引移除（多行空串会冲突），唯一性改由代码层保证。
//  - 依赖明文列的索引（idx_entries_name / _username / _cat_name）在 v2 不再创建。
//  - category_id / created_at / updated_at / is_deleted / sort_order 仍明文（结构性、非敏感）。
// 全新库直接建 v2；已发布的 v1 库经 VAULT_MIGRATE_V1_TO_V2 就地升级（DDL 段，无需 DEK）。
// ============================================================================

// v2 全新建表语句（fresh install）。
val VAULT_SCHEMA_STATEMENTS: List<String> = listOf(
    """
    CREATE TABLE categories (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        name        TEXT    NOT NULL DEFAULT '',
        name_blob   BLOB,
        sort_order  INTEGER NOT NULL DEFAULT 0,
        created_at  INTEGER NOT NULL
    )
    """,
    """
    CREATE TABLE password_entries (
        id          INTEGER PRIMARY KEY AUTOINCREMENT,
        name        TEXT    NOT NULL DEFAULT '',
        username    TEXT    NOT NULL DEFAULT '',
        secret_blob BLOB    NOT NULL,
        category_id INTEGER,
        created_at  INTEGER NOT NULL,
        updated_at  INTEGER NOT NULL,
        is_deleted  INTEGER NOT NULL DEFAULT 0,
        FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL
    )
    """,
    "CREATE INDEX idx_entries_deleted ON password_entries(is_deleted)",
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

// v1 → v2 就地升级的 DDL 段（无 DEK 依赖；纯结构变更）。
// 数据迁移（把明文 name/username 折进密文）在解锁后由 DAO/helper 执行，需 DEK，不在此列。
// 幂等：调用方仅在 user_version/DB_VERSION<2 时执行一次。DROP INDEX IF EXISTS 对全新库亦安全。
val VAULT_MIGRATE_V1_TO_V2: List<String> = listOf(
    "ALTER TABLE categories ADD COLUMN name_blob BLOB",
    "DROP INDEX IF EXISTS uq_categories_name",
    "DROP INDEX IF EXISTS idx_entries_name",
    "DROP INDEX IF EXISTS idx_entries_username",
    "DROP INDEX IF EXISTS idx_entries_cat_name"
)
