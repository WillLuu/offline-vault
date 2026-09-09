package vault.desktop.app

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
        border = if (isSystemInDarkTheme()) BorderStroke(1.dp, neu.border) else null
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
    val focused by iso.collectIsFocusedAsState()
    val borderColor = if (focused) neu.primary else neu.border

    Box(
        modifier = modifier
            .shadow(1.dp, RoundedCornerShape(10.dp))
            .background(neu.surface, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
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

/** 层级 3：主操作按钮。主紫填充白字；hover 抬升投影，按下缩放 0.96 + 投影收缩。 */
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
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "btnScale")
    val shadow by animateDpAsState(
        if (pressed) 1.dp else if (hovered) 4.dp else 2.dp, label = "btnShadow"
    )

    val bg = when {
        !enabled -> neu.onSurfaceVariant.copy(alpha = 0.4f)
        danger -> if (hovered) neu.error else neu.error.copy(alpha = 0.88f)
        hovered -> neu.primary.copy(alpha = 0.88f)
        else -> neu.primary
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = shadow.toPx()
            }
            .clickable(interactionSource = iso, indication = null, enabled = enabled, onClick = onClick)
            .background(bg, RoundedCornerShape(12.dp))
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

/** 圆形图标钮：白底浮起，hover 投影加大，按下微缩。 */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    val neu = LocalNeu.current
    val iso = remember { MutableInteractionSource() }
    val hovered by iso.collectIsHoveredAsState()
    val pressed by iso.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "iconScale")
    val shadow by animateDpAsState(if (hovered) 3.dp else 1.dp, label = "iconShadow")

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = shadow.toPx()
            }
            .clickable(interactionSource = iso, indication = null, onClick = onClick)
            .hoverable(iso)
            .background(neu.surface, CircleShape),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 行内文字按钮（低强调操作）。 */
@Composable
fun NeuTextButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val neu = LocalNeu.current
    val iso = remember { MutableInteractionSource() }
    val hovered by iso.collectIsHoveredAsState()
    Text(
        text,
        color = neu.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clickable(interactionSource = iso, indication = null, onClick = onClick)
            .hoverable(iso)
            .background(
                if (hovered) neu.hoverBg else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
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
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            NeuSurface(
                cornerRadius = 16.dp,
                elevation = 8.dp,
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
