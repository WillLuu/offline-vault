package vault.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

// ============================================================================
// 新拟态组件库（逐参数移植 设计模板/templates/03_Neumorphism_新拟态.html）：
//  - 面与底同色（--bg），立体感完全来自双向 box-shadow——CSS 阴影用
//    「同心环带分层」模拟：EvenOdd 环带逐层外扩/内收 + 透明度衰减（近边浓、远边淡），
//    纯公开 DrawScope API（CMP 1.6.11 桌面端无 BlurMaskFilter，已实测 jar 确认）。
//  - 阴影规格（模板 CSS 变量）：
//      d-out    9px 9px 18px   （.neu 卡/面板）
//      d-out-sm 5px 5px 10px   （按钮）
//      d-out-xs 3px 3px 6px    （小圆钮/禁用态）
//      d-in     inset 6px 6px 12px  （凹陷输入）
//      d-in-sm  inset 3px 3px 6px   （小凹陷）
//  - 圆角：r-lg 26 / r-md 18 / r-sm 12 / r-full 999
//  颜色全部来自 LocalNeu（逐字节移植 colors.xml + values-night）。
// ============================================================================

enum class NeuDir { Raised, Inset }
enum class NeuElev { MD, SM, XS }

private data class ShadowSpec(val offX: Float, val offY: Float, val blur: Float)

private fun shadowSpec(elev: NeuElev): ShadowSpec = when (elev) {
    NeuElev.MD -> ShadowSpec(9f, 9f, 18f)
    NeuElev.SM -> ShadowSpec(5f, 5f, 10f)
    NeuElev.XS -> ShadowSpec(3f, 3f, 6f)
}

/** 同心环带软阴影：环带沿 (sx, sy) 逐层偏移，j 越大越深/淡；绘制顺序 j=layers→1（贴边最后、最浓）。 */
private fun DrawScope.neuRing(
    w: Float, h: Float, r: Float,
    sx: Float, sy: Float, blur: Float,
    color: Color, edgeAlpha: Float, layers: Int = 10
) {
    val pad = blur * 0.5f + 4f
    for (j in layers downTo 1) {
        val t = j / layers.toFloat()
        val ox = sx * t; val oy = sy * t
        val a = edgeAlpha + (1f - edgeAlpha) * (1f - t)
        if (a <= 0.01f) continue
        val ring = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(-pad, -pad, w + pad, h + pad))
            addRoundRect(RoundRect(ox, oy, w + ox, h + oy, CornerRadius(r, r)))
        }
        drawPath(ring, color.copy(alpha = a.coerceIn(0f, 1f)))
    }
}

/**
 * 新拟态面板：面 = 底色（与页面同色无缝），dir 选凸起/凹陷，elev 选阴影档位。
 * RAISED：暗环右下 + 亮环左上（面最后画、盖住环带内侧）；
 * INSET：面先画，暗带左上内壁 + 亮带右下内壁（clip 内逐层）。
 */
@Composable
fun NeuSurface(
    dir: NeuDir = NeuDir.Raised,
    elev: NeuElev = NeuElev.MD,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    val neu = LocalNeu.current
    val spec = shadowSpec(elev)
    val rPx = with(androidx.compose.ui.platform.LocalDensity.current) { cornerRadius.toPx() }

    Box(
        modifier = modifier.drawBehind {
            val w = size.width; val h = size.height; val r = rPx
            if (dir == NeuDir.Raised) {
                neuRing(w, h, r, spec.offX, spec.offY, spec.blur, neu.shadowDark, 0.85f)
                neuRing(w, h, r, -spec.offX, -spec.offY, spec.blur, neu.shadowLight, 0.9f)
                drawRoundRect(neu.bg, topLeft = Offset.Zero, size = size, cornerRadius = CornerRadius(r, r))
            } else {
                drawRoundRect(neu.bg, topLeft = Offset.Zero, size = size, cornerRadius = CornerRadius(r, r))
                val shape = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r)))
                }
                clipPath(shape) {
                    neuRing(w, h, r, spec.offX, spec.offY, spec.blur, neu.shadowDark, 0.9f)
                    neuRing(w, h, r, -spec.offX, -spec.offY, spec.blur, neu.shadowLight, 0.9f)
                }
            }
        }
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

// ---------------- 常用新拟态控件（按钮=模板 .btn：d-out-sm + r-full + 正文色文字） ----------------

/** 凹陷输入框（模板 mimic：d-in-sm + r-sm 12 + text-in 文字色）。 */
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
        elev = NeuElev.SM,
        cornerRadius = 12.dp,
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
                Box(
                    Modifier.defaultMinSize(minHeight = 22.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
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

/** 凸起胶囊按钮（模板 .btn：d-out-sm + r-full；正文色文字，danger 红字）。 */
@Composable
fun NeuButton(
    text: String,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 13.dp),
    onClick: () -> Unit
) {
    val neu = LocalNeu.current
    NeuSurface(
        dir = NeuDir.Raised,
        elev = NeuElev.SM,
        cornerRadius = 999.dp,
        modifier = modifier.alpha(if (enabled) 1f else 0.45f),
        contentPadding = contentPadding
    ) {
        Text(
            text,
            color = when {
                !enabled -> neu.onSurfaceVariant
                danger -> neu.error
                else -> neu.onSurface
            },
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(contentPadding)
        )
    }
}

/** 凸起圆钮（XS 档小阴影）。 */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    NeuSurface(
        dir = NeuDir.Raised,
        elev = NeuElev.XS,
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

// ---------------- 对话框壳：遮罩 + 凸起大圆角卡（r-lg 26） ----------------

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
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
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
