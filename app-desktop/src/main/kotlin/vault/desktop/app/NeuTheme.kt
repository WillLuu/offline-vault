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
// 新拟态主题色（逐字节移植 Android 端 values/colors.xml + values-night/colors.xml）。
// 阴影参数采用 2026-09-06 定稿档：offset 3dp / 暗影 blur 2dp α80% 角55° / 亮影 blur 3dp α50% 角210°。
// ============================================================================

data class NeuColors(
    val bg: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val shadowDark: Color,
    val shadowLight: Color,
    val insetBg: Color,
    val textIn: Color,
    val error: Color,
    val accent: Color,
    val raisedHi: Color,
    val raisedLo: Color,
    val insetHi: Color,
    val insetLo: Color,
)

val LightNeu = NeuColors(
    bg = Color(0xFFE0E5EC),
    onSurface = Color(0xFF3A4A6B),
    onSurfaceVariant = Color(0xFF7A86A0),
    primary = Color(0xFF5F6FD6),
    onPrimary = Color(0xFF4553B8),
    shadowDark = Color(0xFFA3B1C6),
    shadowLight = Color(0xFFFFFFFF),
    insetBg = Color(0xFFD6DAE2),
    textIn = Color(0xFF28324B),
    error = Color(0xFFEF4444),
    accent = Color(0xFF3FB598),
    raisedHi = Color(0xFFFFFFFF),
    raisedLo = Color(0xFFCDD2DC),
    insetHi = Color(0xFFA8B0BC),
    insetLo = Color(0xFFFFFFFF),
)

val DarkNeu = NeuColors(
    bg = Color(0xFF252A34),
    onSurface = Color(0xFFC6CEDE),
    onSurfaceVariant = Color(0xFF8D97AB),
    primary = Color(0xFF8EA0FF),
    onPrimary = Color(0xFFAAB8FF),
    shadowDark = Color(0xFF12151C),
    shadowLight = Color(0xFF38414F),
    insetBg = Color(0xFF1A1C28),
    textIn = Color(0xFFE3E9F6),
    error = Color(0xFFEF4444),
    accent = Color(0xFF4ECFAE),
    raisedHi = Color(0xFF38414F),
    raisedLo = Color(0xFF1B1E27),
    insetHi = Color(0xFF2D313E),
    insetLo = Color(0xFF131520),
)

val LocalNeu = staticCompositionLocalOf { LightNeu }

/** 提供新拟态调色板；同时桥接 MaterialTheme（供 DropdownMenu 等 M3 组件取默认值）。 */
@Composable
fun NeuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkNeu else LightNeu
    val scheme = if (dark) darkColorScheme(
        background = c.bg, surface = c.bg, onSurface = c.onSurface,
        onSurfaceVariant = c.onSurfaceVariant, primary = c.primary, onPrimary = c.onPrimary,
        error = c.error, surfaceVariant = c.insetBg
    ) else lightColorScheme(
        background = c.bg, surface = c.bg, onSurface = c.onSurface,
        onSurfaceVariant = c.onSurfaceVariant, primary = c.primary, onPrimary = c.onPrimary,
        error = c.error, surfaceVariant = c.insetBg
    )
    CompositionLocalProvider(LocalNeu provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
