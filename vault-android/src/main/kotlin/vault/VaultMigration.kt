package vault

import android.database.sqlite.SQLiteDatabase

// ============================================================================
// VaultMigration：v1→v2 数据迁移（需 DEK，解锁后执行）。幂等：仅当存在未迁移行才动作。
//  - 条目：把明文 name/username 折进 secret_blob（v2 JSON），再清空明文列。
//  - 分类：把明文 name 加密进 name_blob，再清空明文列。
//  守卫：SQL_COUNT_UNMIGRATED_* 均为 0 时直接返回（迁移后每次解锁是廉价空扫描）。
//  事务：全程单事务，异常回滚（不留半迁移态；读侧对 v1/v2 blob 均兼容，故即使中断也不丢数据）。
// ============================================================================

fun migrateVaultDataIfNeeded(db: SQLiteDatabase, dek: ByteArray) {
    var pendingEntries = 0
    var pendingCats = 0
    db.rawQuery(SQL_COUNT_UNMIGRATED_ENTRIES, null).use { c -> if (c.moveToFirst()) pendingEntries = c.getInt(0) }
    db.rawQuery(SQL_COUNT_UNMIGRATED_CATEGORIES, null).use { c -> if (c.moveToFirst()) pendingCats = c.getInt(0) }
    if (pendingEntries == 0 && pendingCats == 0) return

    db.beginTransaction()
    try {
        // 条目
        val rows = ArrayList<Triple<Long, String, String>>() // id, 明文name, 明文username
        db.rawQuery("SELECT id, name, username FROM password_entries WHERE name <> '' OR username <> ''", null).use { c ->
            while (c.moveToNext()) rows.add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        for ((id, colName, colUser) in rows) {
            var blob: ByteArray? = null
            db.rawQuery("SELECT secret_blob FROM password_entries WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) blob = c.getBlob(0) }
            val view = decodeEntryBlob(decryptAesGcm(dek, AeadBlob.fromBytes(blob!!)))
            val n = view.name.ifEmpty { colName }
            val u = view.username.ifEmpty { colUser }
            val newBlob = encryptAesGcm(dek, encodeEntryBlob(n, u, view.secret)).toBytes()
            val cv = android.content.ContentValues().apply {
                put("name", ""); put("username", ""); put("secret_blob", newBlob)
            }
            db.update("password_entries", cv, "id = ?", arrayOf(id.toString()))
        }
        // 分类
        val cats = ArrayList<Pair<Long, String>>()
        db.rawQuery("SELECT id, name FROM categories WHERE name <> '' AND name_blob IS NULL", null).use { c ->
            while (c.moveToNext()) cats.add(c.getLong(0) to (c.getString(1) ?: ""))
        }
        for ((id, name) in cats) {
            val cv = android.content.ContentValues().apply {
                put("name", ""); put("name_blob", encryptAesGcm(dek, encodeCategoryNameBlob(name)).toBytes())
            }
            db.update("categories", cv, "id = ?", arrayOf(id.toString()))
        }
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}
