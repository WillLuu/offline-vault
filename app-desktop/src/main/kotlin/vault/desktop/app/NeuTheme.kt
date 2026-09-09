package vault.desktop.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ============================================================================
// 柔和现代混合风色板（2026-09-08 用户定稿：放弃纯新拟物，转白底现代风）。
//  - 背景：极浅灰白；主面板：纯白 + 极淡投影；侧栏：稍深一档浅灰
//  - 主色：品牌紫 5F6FD6（主按钮填充+白字、聚焦边框、选中态）
//  - 深色主题：深灰底 + 深灰面板 + 浅紫主色 + 深色文字
// ============================================================================

data class NeuColors(
    val bg: Color,              // 页面背景
    val surface: Color,         // 主面板（白 / 深灰面板）
    val sidebar: Color,         // 侧栏（稍深一档）
    val onSurface: Color,       // 主文字
    val onSurfaceVariant: Color,// 次文字
    val primary: Color,         // 主紫（按钮填充 / 聚焦边框 / 选中态）
    val onPrimary: Color,       // 主按钮文字（浅色主题=白 / 深色主题=深底色）
    val border: Color,          // 输入框边框
    val divider: Color,         // 分隔细线
    val hoverBg: Color,         // 悬停浅灰
    val error: Color,
    val accent: Color,          // 复制钮等点缀绿
    val insetBg: Color,         // 凹陷底（比面板暗一档）
    val bevelDark: Color,       // 凹陷浮雕暗端（左上）
    val bevelLight: Color       // 凹陷浮雕亮端（右下）
)

val LightNeu = NeuColors(
    bg = Color(0xFFF5F6FA),
    surface = Color.White,
    sidebar = Color(0xFFEEF0F6),
    onSurface = Color(0xFF333333),
    onSurfaceVariant = Color(0xFF8A9099),
    primary = Color(0xFF5F6FD6),
    onPrimary = Color.White,
    border = Color(0xFFE4E7EE),
    divider = Color.White.copy(alpha = 0.6f),
    hoverBg = Color(0xFFE9EBF2),
    error = Color(0xFFEF4444),
    accent = Color(0xFF3FB598),
    insetBg = Color(0xFFDDE3EE),
    bevelDark = Color(0xFFB4BDCE),
    bevelLight = Color(0xFFFFFFFF)
)

val DarkNeu = NeuColors(
    bg = Color(0xFF1E2128),
    surface = Color(0xFF2A2E37),
    sidebar = Color(0xFF23262E),
    onSurface = Color(0xFFE6E9F0),
    onSurfaceVariant = Color(0xFF9AA1AE),
    primary = Color(0xFF8EA0FF),
    onPrimary = Color(0xFF1E2128),
    border = Color(0xFF3A3F4A),
    divider = Color.White.copy(alpha = 0.08f),
    hoverBg = Color(0xFF333844),
    error = Color(0xFFEF4444),
    accent = Color(0xFF4ECFAE),
    insetBg = Color(0xFF14161D),
    bevelDark = Color(0xFF0D0F14),
    bevelLight = Color(0xFF3C4351)
)

val LocalNeu = staticCompositionLocalOf { LightNeu }

@Composable
fun NeuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkNeu else LightNeu
    val scheme = if (dark) darkColorScheme(
        background = c.bg, surface = c.surface, onSurface = c.onSurface,
        onSurfaceVariant = c.onSurfaceVariant, primary = c.primary, error = c.error
    ) else lightColorScheme(
        background = c.bg, surface = c.surface, onSurface = c.onSurface,
        onSurfaceVariant = c.onSurfaceVariant, primary = c.primary, error = c.error
    )
    CompositionLocalProvider(LocalNeu provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
