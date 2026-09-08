package vault.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color.Companion.Black
import androidx.compose.foundation.layout.fillMaxSize

// ============================================================================
// 新拟态组件库（Compose 移植版，参数=2026-09-06 定稿档）。
//  - RAISED：暗影(+dx,+dy) 右下外晕 + 亮影(-dx,-dy) 左上外晕；面 = 左上 Hi → 右下 Lo 对角渐变
//  - INSET：面 = 左上 Hi(深) → 右下 Lo(亮) 对角渐变 + 内阴影环带（暗在左上内壁、亮在右下内壁）
//  - 阴影实现：CMP 1.6.11 桌面端无 BlurMaskFilter（Android 专属 API），改用
//    「同心环带分层」模拟软阴影——逐层外扩/内收的 EvenOdd 环带 + 透明度递减，
//    纯公开 DrawScope API，无 skiko 内部耦合。
//  颜色全部来自 LocalNeu（逐字节移植 Android colors.xml + NeuShadowPrefs 定稿档）。
// ============================================================================

enum class NeuDir { Raised, Inset }

data class NeuShadowSpec(
    val ddx: Float, val ddy: Float,   // 暗影位移方向（55°）
    val ldx: Float, val ldy: Float,   // 亮影位移方向（210°）
    val stepPx: Float                 // 每层环带间距
)

@Composable
private fun rememberShadowSpec(offset: Dp): NeuShadowSpec {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val offPx = with(density) { offset.toPx() }
    val rad = Math.toRadians(55.0)
    val radL = Math.toRadians(210.0)
    return remember(offPx) {
        NeuShadowSpec(
            ddx = (offPx * Math.cos(rad)).toFloat(), ddy = (offPx * Math.sin(rad)).toFloat(),
            ldx = (offPx * Math.cos(radL)).toFloat(), ldy = (offPx * Math.sin(radL)).toFloat(),
            stepPx = with(density) { 1.dp.toPx() }
        )
    }
}

/**
 * 同心环带软阴影：以 (shiftDx, shiftDy) 方向逐层偏移的 EvenOdd 环带，
 * j 越大偏移越深/越远、越淡；绘制顺序 j=layers → 1（贴边最后画、最浓）。
 */
private fun DrawScope.neuRing(
    w: Float, h: Float, r: Float,
    shiftDx: Float, shiftDy: Float, stepPx: Float, layers: Int,
    color: Color, maxAlpha: Float
) {
    val pad = 60f
    for (j in layers downTo 1) {
        val sx = shiftDx * j * stepPx
        val sy = shiftDy * j * stepPx
        val a = maxAlpha * (1f - (j - 1f) / layers)
        if (a <= 0.01f) continue
        val ring = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(-pad, -pad, w + pad, h + pad))
            addRoundRect(RoundRect(sx, sy, w + sx, h + sy, CornerRadius(r, r)))
        }
        drawPath(ring, color.copy(alpha = a))
    }
}

/**
 * 新拟态面板。RAISED 凸起（卡/按钮/顶栏），INSET 凹陷（输入框/密码槽）。
 * contentPadding：子内容与面板边缘的间距（需容纳环带，调用方给）。
 */
@Composable
fun NeuSurface(
    dir: NeuDir,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    offset: Dp = 3.dp,
    alphaDark: Float = 0.8f,
    alphaLight: Float = 0.5f,
    layers: Int = 6,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    val neu = LocalNeu.current
    val spec = rememberShadowSpec(offset)
    val rPx = with(androidx.compose.ui.platform.LocalDensity.current) { cornerRadius.toPx() }

    Box(
        modifier = modifier.drawBehind {
            val w = size.width; val h = size.height; val r = rPx
            when (dir) {
                NeuDir.Raised -> {
                    // 暗影环（右下外晕，逐层外扩渐淡）
                    neuRing(w, h, r, spec.ddx, spec.ddy, spec.stepPx, layers,
                        neu.shadowDark, alphaDark * 0.85f)
                    // 亮影环（左上外晕）
                    neuRing(w, h, r, spec.ldx, spec.ldy, spec.stepPx, layers,
                        neu.shadowLight, alphaLight)
                    // 主面：对角渐变盖住环带内侧
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(neu.raisedHi, neu.raisedLo),
                            start = Offset.Zero, end = Offset(w, h)
                        ),
                        cornerRadius = CornerRadius(r, r)
                    )
                }

                NeuDir.Inset -> {
                    // 凹面：左上深 → 右下亮
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(neu.insetHi, neu.insetLo),
                            start = Offset.Zero, end = Offset(w, h)
                        ),
                        cornerRadius = CornerRadius(r, r)
                    )
                    // 内阴影环带：clip 进形状后逐层画（暗左上 / 亮右下）
                    val shapePath = Path().apply {
                        addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r)))
                    }
                    val darkSpec = spec.copy(
                        ddx = spec.ddx * 0.5f, ddy = spec.ddy * 0.5f,
                        ldx = -spec.ldx * 0.0f, ldy = -spec.ldy * 0.0f
                    )
                    clipPath(shapePath) {
                        neuRing(w, h, r, darkSpec.ddx, darkSpec.ddy, spec.stepPx, layers,
                            neu.shadowDark, alphaDark * 0.9f)
                    }
                    clipPath(shapePath) {
                        neuRing(w, h, r, -spec.ldx * 0.35f, -spec.ldy * 0.35f, spec.stepPx, layers,
                            neu.shadowLight, alphaLight * 0.9f)
                    }
                }
            }
        }
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

// ---------------- 常用新拟态控件 ----------------

/** 凹陷输入框（搜索/表单字段）。hint=占位文案；密码由调用方传 visualTransformation。 */
@Composable
fun NeuField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val neu = LocalNeu.current
    NeuSurface(
        dir = NeuDir.Inset,
        cornerRadius = 16.dp,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = TextStyle(
                color = neu.textIn,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize
            ),
            cursorBrush = SolidColor(neu.primary),
            visualTransformation = if (isPassword) PasswordVisualTransformation()
            else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            decorationBox = { inner ->
                Box(Modifier.defaultMinSize(minHeight = 22.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(hint, color = neu.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    inner()
                }
            }
        )
    }
}

/** 凸起胶囊按钮。danger=true 时文字标红（删除类）；enabled=false 降透明。onClick 为尾参。 */
@Composable
fun NeuButton(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    onClick: () -> Unit
) {
    val neu = LocalNeu.current
    NeuSurface(
        dir = NeuDir.Raised,
        cornerRadius = 22.dp,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        contentPadding = contentPadding
    ) {
        Text(
            text,
            color = if (danger) neu.error else neu.onPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(contentPadding)
        )
    }
}

/** 凸起圆钮（图标）。 */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    NeuSurface(
        dir = NeuDir.Raised,
        cornerRadius = sizeDp / 2,
        modifier = modifier,
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier.size(sizeDp).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

/** 行内文字按钮（编辑/删除/取消等）。 */
@Composable
fun NeuTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text,
        color = LocalNeu.current.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

// ---------------- 对话框壳：半透明遮罩 + 凸起圆角卡（新拟态弹窗统一外壳） ----------------

@Composable
fun NeuDialogShell(
    width: Dp,
    onDismiss: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val neu = LocalNeu.current
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            // 内层阻断点击穿透（no-op clickable 消费事件）
            NeuSurface(
                dir = NeuDir.Raised,
                cornerRadius = 26.dp,
                modifier = Modifier.width(width),
                contentPadding = PaddingValues(24.dp)
            ) {
                Column(
                    modifier = Modifier.clickable(enabled = true, onClick = {}),
                    content = content
                )
            }
        }
    }
}
