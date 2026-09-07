package vault.desktop.app

import vault.desktop.DesktopVault

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlin.system.exitProcess

// ============================================================================
// 桌面应用入口（PC 端 PRD M5 MVP：Material3 标准样式；新拟态重绘为 P2）。
//  - 全程离线：仅消费 vault-desktop 门面，无任何网络依赖（SEC-9）
//  - 空闲自动锁（F8）+ 剪贴板延时清除（F9）+ 退出/手动锁冲刷；最小化不锁（D3 采纳建议值）
// ============================================================================

fun main() {
    val vault = DesktopVault()
    val model = AppModel(vault)
    ActivityMonitor.install()
    model.start()

    application {
        val dark = isSystemInDarkTheme()
        MaterialTheme(if (dark) darkColorScheme() else lightColorScheme()) {
            Window(
                onCloseRequest = {
                    DesktopClipboard.flushNow()
                    model.stop()
                    exitProcess(0)
                },
                title = "秘匣 · 密码保险库（桌面版）",
                state = rememberWindowState(width = 1120.dp, height = 740.dp),
            ) {
                AppRoot(model)
            }
        }
    }
}
