package vault.desktop.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.core.animateFloatAsState

// ============================================================================
// 组件库（2026-09-08 用户定稿：放弃纯新拟物，转「柔和现代混合风」）。
//  - NeuSurface：白底大圆角面板 + 极浅投影（深色主题=深灰面板+细边框）
//  - NeuField：白底 + 1dp 边框，聚焦变紫（一眼看出能输入）
//  - NeuButton：主紫填充 + 白字，hover 变浅、按下缩放 0.96（微动效）
//  - NeuIconButton：透明底，hover 浅灰圆底（Windows 11 标题栏风格）
//  全部颜色来自 LocalNeu（浅/深双主题）。
// ============================================================================

/** 白底圆角面板 + 极浅投影（替代原 Path 自绘阴影）。 */
@Composable
fun NeuSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    val neu = LocalNeu.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = neu.surface,
        shadowElevation = 2.dp,
        border = if (androidx.compose.foundation.isSystemInDarkTheme())
            BorderStroke(1.dp, neu.border) else null
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** 凹陷输入框（现代版：白底 + 1dp 边框，聚焦变紫——一眼看出能输入）。 */
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
                keyboardOptions.copy(imeAction = ImeAction.Done) else keyboardOptions,
            keyboardActions = if (onEnter != null)
                KeyboardActions(onDone = { onEnter() }) else KeyboardActions.Default,
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

/** 主操作按钮：主紫填充 + 白字；hover 变浅、按下缩放 0.96（微动效）。danger 红底。 */
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
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "btnPressScale")

    val bg = when {
        !enabled -> neu.onSurfaceVariant.copy(alpha = 0.4f)
        danger -> if (hovered) neu.error else neu.error.copy(alpha = 0.88f)
        hovered -> neu.primary.copy(alpha = 0.85f)
        else -> neu.primary
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
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

/** 主操作按钮（强调填充 + 白字，宽度常为 fillMaxWidth）。 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    NeuButton(text, modifier = modifier.fillMaxWidth(), enabled = enabled, onClick = onClick)
}

/** 圆形图标钮：透明底，hover 浅灰圆底 + 微缩放（Windows 11 标题栏风格）。 */
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
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "iconPressScale")

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = iso, indication = null, onClick = onClick)
            .hoverable(iso)
            .background(
                if (hovered) neu.hoverBg else Color.Transparent,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** 行内文字按钮：hover 浅灰底。 */
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
            .background(if (hovered) neu.hoverBg else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

// ---------------- 对话框壳：遮罩 + 白底大圆角卡（现代风弹窗统一外壳） ----------------

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
                cornerRadius = 16.dp,
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
