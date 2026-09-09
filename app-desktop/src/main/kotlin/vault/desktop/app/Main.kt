package vault.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import vault.desktop.DesktopVault
import java.awt.MouseInfo
import kotlin.system.exitProcess

// ============================================================================
// 桌面应用入口（无边框自绘窗体 + 新拟态标题栏，全量覆盖新拟态风格）。
//  - 全程离线：仅消费 vault-desktop 门面，无任何网络依赖（SEC-9）
//  - 空闲自动锁（F8）+ 剪贴板延时清除（F9）+ 退出/手动锁冲刷；最小化不锁（D3 采纳建议值）
//  ponytail: 标题栏拖动用 pointerInput + AWT 全局鼠标坐标（CMP 1.6.11 未暴露
//  WindowDraggableArea）；最小化走 JNA ShowWindow（WindowPlacement.Minimized 未暴露）。
//  | 升级阈值：CMP 官方 API 就绪后替换。
// ============================================================================

fun main() {
    val vault = DesktopVault()
    val model = AppModel(vault)
    ActivityMonitor.install()
    model.start()

    application {
        val ws = rememberWindowState(width = 1120.dp, height = 740.dp)
        val onExit = {
            DesktopClipboard.flushNow()
            model.stop()
            exitProcess(0)
        }
        Window(
            onCloseRequest = onExit,
            title = "秘匣 · 密码保险库",
            state = ws,
            undecorated = true,
            resizable = true,
        ) {
            NeuTheme {
                Column(Modifier.fillMaxSize().background(LocalNeu.current.bg)) {
                    TitleBar(ws, onExit)
                    AppRoot(model)
                }
            }
        }
    }
}

/** 新拟态标题栏：品牌徽章 + 标题（拖拽移动区）+ 最大化/最小化/关闭钮。 */
@Composable
private fun ApplicationScope.TitleBar(ws: WindowState, onExit: () -> Unit) {
    val neu = LocalNeu.current
    val density = LocalDensity.current
    var grabPx by remember { mutableStateOf<Offset?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(neu.bg)
            // 手动拖动：以"窗口左上角 − 抓取点"恒定偏移跟随全局鼠标（避免窗口追指针抖动）
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { grab ->
                        val m = MouseInfo.getPointerInfo().location
                        with(density) {
                            grabPx = Offset(
                                m.x - ws.position.x.toPx(),
                                m.y - ws.position.y.toPx()
                            )
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val g = grabPx ?: return@detectDragGestures
                        val m = MouseInfo.getPointerInfo().location
                        with(density) {
                            ws.position = WindowPosition(
                                (m.x - g.x).toDp(),
                                (m.y - g.y).toDp()
                            )
                        }
                    }
                )
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(1f))
        // 最大化/还原（透明底，hover 浅灰）
        val maxIso = remember { MutableInteractionSource() }
        val maxHover by maxIso.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .size(32.dp)
                .hoverable(maxIso)
                .clickable(interactionSource = maxIso, indication = null) {
                    ws.placement = if (ws.placement == WindowPlacement.Maximized)
                        WindowPlacement.Floating else WindowPlacement.Maximized
                }
                .background(if (maxHover) neu.hoverBg else Color.Transparent, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (ws.placement == WindowPlacement.Maximized) "❐" else "□",
                color = neu.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        // 最小化（JNA ShowWindow；透明底，hover 浅灰）
        val minIso = remember { MutableInteractionSource() }
        val minHover by minIso.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .size(32.dp)
                .hoverable(minIso)
                .clickable(interactionSource = minIso, indication = null) {
                    java.awt.Window.getWindows().firstOrNull { it.isVisible }
                        ?.let { minimizeWindow(it) }
                }
                .background(if (minHover) neu.hoverBg else Color.Transparent, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("─", color = neu.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        // 关闭（hover 变红，Windows 惯例）
        val closeIso = remember { MutableInteractionSource() }
        val closeHover by closeIso.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .size(32.dp)
                .hoverable(closeIso)
                .clickable(interactionSource = closeIso, indication = null, onClick = onExit)
                .background(if (closeHover) neu.error.copy(alpha = 0.9f) else Color.Transparent, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "✕", color = if (closeHover) Color.White else neu.onSurfaceVariant, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .size(30.dp)
                    .clickable(onClick = onExit),
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.width(4.dp))
    }
}
