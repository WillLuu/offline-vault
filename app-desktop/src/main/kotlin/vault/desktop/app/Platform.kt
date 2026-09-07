package vault.desktop.app

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.io.File
import java.awt.event.MouseEvent
import kotlin.system.exitProcess

// ============================================================================
// 桌面平台工具：剪贴板（F9）+ 空闲活动监听（F8）。
// ============================================================================

/** 剪贴板：复制后延时清除；仅清除本应用写入的内容（与 Android ClipboardHelper 同语义）。 */
object DesktopClipboard {
    private val cb = Toolkit.getDefaultToolkit().systemClipboard
    private var ours: String? = null

    fun copy(text: String) {
        cb.setContents(StringSelection(text), null)
        ours = text
    }

    /** 到期清除：仅当剪贴板当前内容仍是我们写入的那份（不误清用户后续复制）。 */
    fun clearIfOurs(): Boolean {
        val t = ours ?: return false
        val current = runCatching {
            cb.getContents(null)?.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
        return if (current == t) {
            cb.setContents(StringSelection(""), null)
            ours = null
            true
        } else {
            ours = null
            false
        }
    }

    /** 失焦/最小化/退出兜底：延时未到也立即清（镜像 Android flushNow）。 */
    fun flushNow() { clearIfOurs() }
}

/** 全局活动监听：任何鼠标按下/键盘输入都重置空闲计时（F8；修复 Android decorView 监听不到控件消费事件的旧问题——AWT 全局钩子天然覆盖）。 */
object ActivityMonitor {
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()

    fun install(onEvent: () -> Unit = {}) {
        Toolkit.getDefaultToolkit().addAWTEventListener(
            { ev ->
                if (ev is MouseEvent && ev.id == MouseEvent.MOUSE_PRESSED ||
                    ev is KeyEvent && ev.id == KeyEvent.KEY_PRESSED
                ) {
                    lastActivityMs = System.currentTimeMillis()
                    onEvent()
                }
            },
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK
        )
    }

    fun idleMillis(): Long = System.currentTimeMillis() - lastActivityMs
}

fun exitApp() { exitProcess(0) }
