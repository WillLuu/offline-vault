package com.qiqiao.passwordvault.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// epoch millis -> 可读时间；<=0 返回占位
fun formatTime(epochMillis: Long): String {
    if (epochMillis <= 0) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))
}
