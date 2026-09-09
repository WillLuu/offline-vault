package vault.desktop.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ============================================================================
// 组件库（2026-09-08 二次定稿：全组件立体风格）。
//  深度语言（全部用桌面端可用的原生手段，无自绘/无渐变/无半透明叠加）：
//   - 层级 0：页面底色（极浅灰白 / 深灰）
//   - 层级 1：侧栏 / 输入框（稍深一档底色或白底细边框）
//   - 层级 2：卡片 / 面板 / 次按钮（白底 + 2dp 投影）
//   - 层级 3：主按钮 / 对话框（主紫填充 / 4-8dp 投影）
//  微交互：hover 抬升（投影加大）、按下下沉（缩放 0.96 + 投影收缩）——
//  「按下去是往桌面里按，不是飘走」。
// ============================================================================

/** 层级 2：白底圆角面板 + 投影。 */
@Composable
fun NeuSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    elevation: Dp = 2.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    val neu = LocalNeu.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = neu.surface,
        shadowElevation = elevation,
        border = if (neu.isDark) BorderStroke(1.dp, neu.border) else null
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** 层级 1：输入框。白底 + 1dp 边框（未聚焦灰 / 聚焦主紫）+ 1dp 微投影。 */
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
    onEnter: (() -> Unit)? = null,
) {
    val neu = LocalNeu.current
    val iso = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .background(neu.insetBg, RoundedCornerShape(10.dp))
            .drawBehind {
                // 柔和凹陷：不用单条硬描边，而是沿内缘叠 4 圈低透明度描边，
                // 每圈向内收 1.6px、alpha 平方衰减；颜色走竖向渐变
                // （顶暗 → 中部透明 → 底微亮），模拟真实内阴影的渐隐，无轮廓线
                val passes = 4
                val rBase = 10.dp.toPx()
                for (i in 0 until passes) {
                    val inset = 0.6f + i * 1.6f
                    val t = 1f - i.toFloat() / passes
                    val a = t * t * 0.42f
                    val w = size.width - inset * 2f
                    val h = size.height - inset * 2f
                    if (w <= 0f || h <= 0f) break
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                neu.bevelDark.copy(alpha = a),
                                Color.Transparent,
                                neu.bevelLight.copy(alpha = a * 0.5f)
                            ),
                            startY = inset,
                            endY = inset + h
                        ),
                        topLeft = Offset(inset, inset),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(
                            (rBase - i * 1.2f).coerceAtLeast(2f),
                            (rBase - i * 1.2f).coerceAtLeast(2f)
                        ),
                        style = Stroke(width = 1.4f)
                    )
                }
            }
            // 整框可点：点内边距空白处也聚焦并落光标（文字区由 BasicTextField 自己处理）
            // indication = null：关掉默认涟漪/悬浮高亮层
            .clickable(interactionSource = iso, indication = null) { focusRequester.requestFocus() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // BasicTextField 的文本选择区自带默认 indication（矩形 hover/按压高亮层），
        // 会在凹陷上叠出一块"大方框背影"——在其作用域内置空 LocalIndication
        CompositionLocalProvider(LocalIndication provides NoIndication) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.focusRequester(focusRequester).fillMaxWidth(),
            singleLine = singleLine,
            minLines = minLines,
            textStyle = TextStyle(
                color = neu.onSurface,
                fontSize = MaterialTheme.typography.bodyMedium.fontSize
            ),
            cursorBrush = SolidColor(neu.primary),
            visualTransformation = if (isPassword) PasswordVisualTransformation()
            else VisualTransformation.None,
            keyboardOptions = if (onEnter != null)
                keyboardOptions.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
            else keyboardOptions,
            keyboardActions = if (onEnter != null)
                androidx.compose.foundation.text.KeyboardActions(
                    onDone = { onEnter() }
                ) else androidx.compose.foundation.text.KeyboardActions.Default,
            interactionSource = iso,
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
}

/** 空 indication：drawIndication 什么都不画，吞掉组件内部默认的高亮/涟漪层 */
private object NoIndication : Indication {
    private val instance = object : IndicationInstance {
        override fun ContentDrawScope.drawIndication() {}
    }
    @Composable
    override fun rememberUpdatedInstance(
        interactionSource: InteractionSource
    ): IndicationInstance = instance
}

/** 层级 3：主操作按钮。主紫填充白字；hover 提亮、按下加深；投影恒定无变换。 */
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
    val iso = remember { MutableInteractionSource() }
    val hovered by iso.collectIsHoveredAsState()
    val pressed by iso.collectIsPressedAsState()
    // 投影恒定 + 禁用缩放变换：桌面端 shadowElevation 按未缩放
    // 的原始轮廓绘制，按下缩放会让投影"脱胶"露在按钮外圈，形成明显深色边缘。
    // 按压反馈改由按钮自身颜色加深承担（纯内部变化，不产生外圈）。
    // 禁用态投影归零：浅灰填充 + 深色投影带 = 看起来像一圈边框。
    val shadow = if (enabled) 2.dp else 0.dp

    // 全部用不透明混色：半透明填充会让图层投影从内部透出来，形成边缘暗圈
    val bg = when {
        !enabled -> lerp(neu.surface, neu.onSurfaceVariant, 0.22f)
        danger && pressed -> lerp(neu.surface, neu.error, 0.7f)
        danger && hovered -> neu.error
        danger -> lerp(neu.surface, neu.error, 0.88f)
        pressed -> lerp(neu.surface, neu.primary, 0.75f)
        hovered -> lerp(neu.surface, neu.primary, 0.88f)
        else -> neu.primary
    }
    val btnShape = RoundedCornerShape(12.dp)
    // 底部深色唇边：比面色更深两成的同色"侧壁"，从面色下方露出 3dp。
    // 实体按键的厚度感来自底部比顶面深，而非四周的浅灰投影。
    val lipColor = lerp(Color.Black, bg, 0.72f)

    Box(
        modifier = modifier
            .graphicsLayer {
                shape = btnShape            // 关键：不设 shape 投影按矩形轮廓画，圆角下露白直角
                shadowElevation = shadow.toPx()
            }
            .drawBehind {
                // 唇边画在最底层，面色背景覆盖其上 → 只有侧壁方向露出的一弯可见。
                // 光源左上 → 壁厚朝右下方露出，方向与水平成 60°（dx=cos60, dy=sin60）
                val lip = 3.dp.toPx()
                drawRoundRect(
                    color = lipColor,
                    topLeft = Offset(lip * 0.5f, lip * 0.866f),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                )
            }
            .clickable(interactionSource = iso, indication = null, enabled = enabled, onClick = onClick)
            .background(bg, btnShape)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) neu.onPrimary else neu.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** 主操作按钮（同 NeuButton，宽度常为撑满）。 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    NeuButton(text, modifier = modifier.fillMaxWidth(), enabled = enabled, onClick = onClick)
}

/** 圆形图标钮：白底浮起（投影恒定），按下底色变灰。 */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    val neu = LocalNeu.current
    val iso = remember { MutableInteractionSource() }
    val pressed by iso.collectIsPressedAsState()
    val shadow = 1.5.dp   // 恒定投影；不做缩放（缩放与投影轮廓不同步会露出外圈）

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer {
                shape = CircleShape         // 同上：投影跟随圆形轮廓
                shadowElevation = shadow.toPx()
            }
            .clickable(interactionSource = iso, indication = null, onClick = onClick)
            .background(if (pressed) neu.hoverBg else neu.surface, CircleShape),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 次级功能按钮：白底凸起胶囊（投影恒定，按下底色变灰），统一"功能键必凸"。 */
@Composable
fun NeuTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val neu = LocalNeu.current
    val iso = remember { MutableInteractionSource() }
    val pressed by iso.collectIsPressedAsState()
    val shadow = 1.5.dp   // 恒定投影；不做缩放（缩放与投影轮廓不同步会露出外圈）
    val pillShape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .graphicsLayer {
                shape = pillShape
                shadowElevation = shadow.toPx()
            }
            .background(if (pressed) neu.hoverBg else neu.surface, pillShape)
            .clickable(interactionSource = iso, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = neu.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

// ---------------- 对话框壳：浮起最高的白底大圆角卡 ----------------

@Composable
fun NeuDialogShell(
    width: Dp,
    onDismiss: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            NeuSurface(
                cornerRadius = 16.dp,
                elevation = 8.dp,
                modifier = Modifier.width(width),
                contentPadding = PaddingValues(24.dp)
            ) {
                Column(
                    // 吞掉面板内点击、防止穿透到遮罩关闭弹窗；
                    // indication = null：默认涟漪/悬浮高亮层会覆盖整个内容区，
                    // 鼠标悬浮时显现一块"大方框"边缘（此前弹窗观感问题的真凶）
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                    content = content
                )
            }
        }
    }
}
