package com.qiqiao.passwordvault.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

// 复制密码到剪贴板（契约 §7：防明文常驻）。
// clearDelaySec=0 表示永不自动清除（用户手动清，最安全）；>0 表示延时秒数后清除。
// #QA / 审计 2026-09-06 P2#3：进程被杀后延时清除回调不执行 → 提供 flushNow()，
// 由 MainApplication 在"真正切后台"时兜底冲刷（还在延时期内的本轮复制立即清空）。
object ClipboardHelper {

    private var lastText: String? = null
    private var lastDelaySec = 0
    private var lastCopyAt = 0L

    fun copy(context: Context, text: String, clearDelaySec: Int) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("password", text))
        lastText = text
        lastDelaySec = clearDelaySec
        lastCopyAt = SystemClock.uptimeMillis()
        if (clearDelaySec <= 0) return   // 永不自动清除：不启动清除定时器
        Handler(Looper.getMainLooper()).postDelayed({
            clearIfOurs(cm, text)
            if (lastText == text) { lastText = null; lastDelaySec = 0 }
        }, clearDelaySec * 1000L)
    }

    /** 真后台兜底：若我们的密码仍在剪贴板且未到延时，立即清空（进程存活场景）。 */
    fun flushNow(context: Context) {
        val text = lastText ?: return
        val delayMs = lastDelaySec * 1000L
        val elapsed = SystemClock.uptimeMillis() - lastCopyAt
        if (lastDelaySec > 0 && elapsed in 0..delayMs) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clearIfOurs(cm, text)
        }
        lastText = null
        lastDelaySec = 0
    }

    private fun clearIfOurs(cm: ClipboardManager, text: String) {
        // 仅当剪贴板仍是我们写入的内容时才清空，避免误清用户后续复制
        val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (current == text) cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
