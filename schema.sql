-- schema.sql : 离线密码本 本地 SQLite DDL（Android 原生，min API 26）
-- 作者：arch-luchen  |  Phase 3 交付物之一
--
-- 设计铁律（来自架构师）：
--   1. snake_case；无"为未来扩展"的预留字段（无 sync_status / cloud_id / device_id / etag）。
--   2. 【v2 起】name / username / 分类名 全部加密入库，不再明文：条目真名折进 secret_blob 的
--      AES-GCM JSON（{n,u,p,w,t,x}），分类真名存于 categories.name_blob；明文 name/username 列
--      退化为占位空串。搜索/排序/分页在解锁后于内存进行（vault-core Query.filterAndSortEntries）。
--      故 sqlite 客户端打开 vault.db 只见密文与结构性整数（id/时间戳/category_id/is_deleted/sort_order）。
--      （v1 曾明文存 name/username 以支持 SQL LIKE/ORDER BY；v1→v2 于解锁后静默迁移，见 VaultMigration。）
--   3. password / website / notes 合并为 secret_blob，由加密层整体做 AES-256-GCM 加密
--      （遵循需求中"notes 建议整体加密"的指引，统一加密所有敏感字段）。
--   4. 软删除 is_deleted；DEK/KEK/盐/生物识别密钥等敏感材料不在此文件以明文出现，
--      存于 app_settings 或由 Android Keystore 保护（见 api-contract.md §4/§5）。
--   5. 单行设置表：app_settings 仅在"首次设置主密码"时插入一行（id=1），
--      行存在即代表已完成初始化。

PRAGMA foreign_keys = ON;

-- ============================================================
-- 1) 分类表 categories
-- ============================================================
CREATE TABLE categories (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,                 -- 明文，唯一（支付/社交/工作/娱乐/邮箱/其他…）
    sort_order  INTEGER NOT NULL DEFAULT 0,       -- 分类在列表中的展示顺序
    created_at  INTEGER NOT NULL                  -- epoch millis
);
CREATE UNIQUE INDEX uq_categories_name ON categories(name);

-- ============================================================
-- 2) 密码条目表 password_entries
-- ============================================================
CREATE TABLE password_entries (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT    NOT NULL,                 -- 明文：用于搜索与按名称排序
    username    TEXT    NOT NULL DEFAULT '',      -- 明文：用于搜索（按用户名）
    secret_blob BLOB    NOT NULL,                 -- 密文：AES-256-GCM({
                                                  --         password, website, notes
                                                  --       }) 的序列化 JSON
    category_id INTEGER,                          -- 外键 -> categories.id；NULL = 未分类
    created_at  INTEGER NOT NULL,                 -- epoch millis（创建时间）
    updated_at  INTEGER NOT NULL,                 -- epoch millis（修改时间）
    is_deleted  INTEGER NOT NULL DEFAULT 0,       -- 软删：0=有效, 1=已删
    FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE SET NULL
);

-- 搜索索引：列表页按 name / username 做 LIKE（明文列）
CREATE INDEX idx_entries_name     ON password_entries(name);
CREATE INDEX idx_entries_username ON password_entries(username);
-- 过滤 + 排序联合索引：按分类过滤后按名称排序
CREATE INDEX idx_entries_cat_name ON password_entries(category_id, name);
-- 软删过滤（列表默认 WHERE is_deleted = 0）
CREATE INDEX idx_entries_deleted  ON password_entries(is_deleted);

-- ============================================================
-- 3) 应用设置表 app_settings（单行，id 固定 = 1）
-- ============================================================
CREATE TABLE app_settings (
    id                        INTEGER PRIMARY KEY CHECK (id = 1),

    -- 初始化标记：行存在且 =1 表示主密码已设置
    initialized              INTEGER NOT NULL DEFAULT 0,

    -- 主密码 KDF 参数（Argon2id 推荐；详见 api-contract.md §4.2）
    kdf_algo                 TEXT    NOT NULL DEFAULT 'argon2id',
    kdf_memory_kb            INTEGER NOT NULL DEFAULT 65536,   -- 64 MB
    kdf_iterations           INTEGER NOT NULL DEFAULT 3,
    kdf_parallelism          INTEGER NOT NULL DEFAULT 1,
    kdf_salt                 BLOB    NOT NULL,                 -- 主密码派生盐（16+ 字节随机）

    -- 密钥材料（均经主密码 KEK 或 Keystore 保护，不可明文反解）
    wrapped_dek              BLOB    NOT NULL,         -- DEK 经 KEK(AES-256-GCM) 加密
    verifier                 BLOB    NOT NULL,         -- KEK 解锁校验块（GCM 认证，用于验证主密码）
    wrapped_dek_biometric    BLOB,                     -- DEK 经 Keystore 生物识别密钥加密；未启用为 NULL

    -- 用户设置（设置页）
    auto_lock_timeout_sec    INTEGER NOT NULL DEFAULT 60,    -- 前台空闲自动锁超时（秒）；0=不超时
    clipboard_clear_delay_sec INTEGER NOT NULL DEFAULT 30,   -- 复制密码后剪贴板清除延时（秒）；0=永不自动清除
    theme                    TEXT    NOT NULL DEFAULT 'system', -- light / dark / system
    biometric_enabled        INTEGER NOT NULL DEFAULT 0,     -- 0/1 指纹/面容解锁开关

    created_at              INTEGER NOT NULL,
    updated_at              INTEGER NOT NULL
);

-- ============================================================
-- 初始数据：首次初始化时由代码写入（非 DDL 责任，列于此备忘）
--   categories 种子：支付/社交/工作/娱乐/邮箱/其他（sort_order 0..5，"其他"不可删）
--   app_settings 仅在初始化主密码后插入 id=1 一行
-- ============================================================
