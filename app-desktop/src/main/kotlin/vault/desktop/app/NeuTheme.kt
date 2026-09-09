package vault.desktop.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ============================================================================
// 三套配色与手机端（app-android res/values/colors.xml）逐 token 对齐：
//   0 LightNeu   ← 浅色新拟态（bg #E0E5EC / primary #5F6FD6 / inset #D6DAE2）
//   1 DarkNeu    ← values-night（bg #252A34 / primary #8EA0FF / inset #1A1C28）
//   2 MorandiNeu ← Theme.PasswordVault.Morandi（bg #D9D3CB / primary #9A8778）
// 桌面端排版不变，仅换色板。
// ============================================================================

data class NeuColors(
    val bg: Color,              // 页面背景
    val surface: Color,         // 主面板（卡片/弹窗面）
    val sidebar: Color,         // 侧栏（稍深一档）
    val onSurface: Color,       // 主文字
    val onSurfaceVariant: Color,// 次文字
    val primary: Color,         // 主色（按钮填充 / 选中态）
    val onPrimary: Color,       // 主按钮文字
    val border: Color,          // 输入框边框
    val divider: Color,         // 分隔细线
    val hoverBg: Color,         // 悬停浅灰
    val error: Color,
    val accent: Color,          // 复制钮等点缀绿
    val insetBg: Color,         // 凹陷底（比面板暗一档）
    val bevelDark: Color,       // 凹陷浮雕暗端（左上）
    val bevelLight: Color,      // 凹陷浮雕亮端（右下）
    val isDark: Boolean         // 深色底（面板描边等细节跟随应用内主题，而非系统暗色）
)

/** 手机端浅色新拟态：冷紫灰底 + 白卡 + 品牌紫 */
val LightNeu = NeuColors(
    bg = Color(0xFFE0E5EC),
    surface = Color.White,
    sidebar = Color(0xFFD8DEE8),
    onSurface = Color(0xFF3A4A6B),
    onSurfaceVariant = Color(0xFF7A86A0),
    primary = Color(0xFF5F6FD6),
    onPrimary = Color.White,
    border = Color(0xFFD5D8E2),
    divider = Color(0xFFCBD2DE),
    hoverBg = Color(0xFFD6DAE2),
    error = Color(0xFFEF4444),
    accent = Color(0xFF3FB598),
    insetBg = Color(0xFFD6DAE2),
    bevelDark = Color(0xFFA3B1C6),
    bevelLight = Color(0xFFFFFFFF),
    isDark = false
)

/** 手机端深色：深紫灰底 + 亮紫主色 */
val DarkNeu = NeuColors(
    bg = Color(0xFF252A34),
    surface = Color(0xFF2D313E),
    sidebar = Color(0xFF2A2F3B),
    onSurface = Color(0xFFC6CEDE),
    onSurfaceVariant = Color(0xFF8D97AB),
    primary = Color(0xFF8EA0FF),
    onPrimary = Color(0xFF1B1E27),
    border = Color(0xFF3C4351),
    divider = Color(0xFF1F232C),
    hoverBg = Color(0xFF333A48),
    error = Color(0xFFEF4444),
    accent = Color(0xFF4ECFAE),
    insetBg = Color(0xFF1A1C28),
    bevelDark = Color(0xFF12151C),
    bevelLight = Color(0xFF38414F),
    isDark = true
)

/** 手机端 Morandi：暖灰底 + 摩卡主色 + 灰绿点缀 */
val MorandiNeu = NeuColors(
    bg = Color(0xFFD9D3CB),
    surface = Color(0xFFE0DAD2),
    sidebar = Color(0xFFD2CCC2),
    onSurface = Color(0xFF54504A),
    onSurfaceVariant = Color(0xFF6B655C),
    primary = Color(0xFF9A8778),
    onPrimary = Color.White,
    border = Color(0xFFC4BCB1),
    divider = Color(0xFFCCC4B9),
    hoverBg = Color(0xFFE3DDD5),
    error = Color(0xFFB05C50),
    accent = Color(0xFF6E8B5E),
    insetBg = Color(0xFFC5BFB6),
    bevelDark = Color(0xFFB0A89E),
    bevelLight = Color(0xFFF2EBE3),
    isDark = false
)

val NeuThemes = listOf(LightNeu, DarkNeu, MorandiNeu)
val NeuThemeNames = listOf("浅色", "深色", "莫兰迪")

val LocalNeu = staticCompositionLocalOf { LightNeu }

@Composable
fun NeuTheme(mode: Int = 0, content: @Composable () -> Unit) {
    val c = NeuThemes.getOrElse(mode) { LightNeu }
    val scheme = if (mode == 1) darkColorScheme(
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
