package vault

import android.database.Cursor

// 轻量 Cursor 扩展（避免引入 androidx.core KTX 依赖，零抽象）。
inline fun <T> Cursor.use(block: (Cursor) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
