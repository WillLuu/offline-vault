package com.qiqiao.passwordvault.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.res.ResourcesCompat
import com.qiqiao.passwordvault.R

/**
 * 新拟态立体拨动开关（用户定稿 CSS 的原生等价实现）。
 *
 * 等价 CSS：
 *   .track  { border-radius: 16px; background: <off/on 底色>;
 *             box-shadow: inset 4px 4px 8px <暗>, inset -4px -4px 8px <亮>; }   ← 凹陷
 *   .thumb  { width/height: 24px; background: #fff;
 *             box-shadow: 4px 4px 8px <暗>, -4px -4px 8px <亮>; }               ← 凸起
 *   input:checked + .thumb { transform: translateX(32px); }
 *
 * 光源统一左上：凸起 = 左上亮 / 右下暗；凹陷 = 左上暗 / 右下亮。
 *
 * 工艺（与 NeuSurface 完全一致，勿改）：
 * - 轨道凹陷：纯色填充 + clipPath 圆角内画 BlurMaskFilter 环带
 *   （外矩形 − 平移 inner 的 DIFFERENCE；禁 DST_IN/saveLayer——红魔 HW 下不生效）。
 * - 滑块凸起：setShadowLayer 双 pass（暗 halo 右下、亮 halo 左上）+ 不透明 fill 盖中央。
 * - 内阴影颜色随轨道底色加深/提亮（等价 CSS #4a3db0/#8a7af0 随主色联动），
 *   偏移/模糊/强度走 NeuShadowPrefs 内阴影两组参数，设置页可调实时生效。
 * - 开启轨道色 = 主题 colorPrimary（浅 #635BFF / 深 #7C71FF / 莫兰迪联动）。
 */
class NeuSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 开关状态。程序性赋值**不触发**监听器（等价 suppressSwitch 语义）；
     * 用户点击（performClick）才回调。
     */
    var isChecked: Boolean = false
        set(value) {
            targetChecked = value
            animateTo(if (value) 1f else 0f)
        }

    /** 仅用户点击时回调（程序性赋值不触发，调用方无需再防重入）。 */
    private var onCheckedChange: ((NeuSwitch, Boolean) -> Unit)? = null

    fun setOnCheckedChangeListener(listener: ((NeuSwitch, Boolean) -> Unit)?) {
        onCheckedChange = listener
    }

    // ---- 几何（精致比例：52×30 轨道 / r15 胶囊 / 22 球 / 4 边距 / 22 行程）----
    private val density = resources.displayMetrics.density
    private val trackW = dp(52f)
    private val trackH = dp(30f)
    private val thumbR = dp(11f)
    private val pad = dp(4f)
    private val travel = trackW - trackH // = 22dp

    private var targetChecked = false

    /** 0 = 全关，1 = 全开（滑块位置与轨道颜色共用同一进度）。 */
    private var progress = 0f

    // ---- 颜色（init 里按主题解析）----
    private var trackOff = 0xFFD6DAE2.toInt()   // pwdInsetBg 下沉色
    private var trackOn = 0xFF635BFF.toInt()    // colorPrimary 基准
    private var trackOnColor = 0xFF8B86C8.toInt()// 开关开启轨道：灰紫（降饱和×0.55 + 调暗×0.72，免刺眼大色块）
    private var shadowDark = 0xFFA3B1C6.toInt() // pwdShadowDark
    private var shadowLight = 0xFFFFFFFF.toInt()// pwdShadowLight
    private val thumbColor = 0xFFFFFFFF.toInt()

    // 滑块内图标色：关 = 灰（随 off 色联动），开 = 深灰紫（白底上可读）
    private var glyphOffColor = 0xFF8A94A6.toInt()
    private var glyphOnColor = 0xFF4E4A80.toInt()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.9f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPath = Path()
    private val trackRect = RectF()
    private val tmpPath = Path()
    private val innerRect = RectF()

    private var animator: ValueAnimator? = null

    private val prefsListener = { invalidate() }

    init {
        trackOff = resolveColor(R.attr.pwdInsetBg, trackOff)
        trackOn = resolveColor(androidx.appcompat.R.attr.colorPrimary, trackOn)
        shadowDark = resolveColor(R.attr.pwdShadowDark, shadowDark)
        shadowLight = resolveColor(R.attr.pwdShadowLight, shadowLight)
        // 开启轨道改"灰紫"：降饱和 ×0.55 + 调暗 ×0.72（默认主紫在灰白背景上刺眼、大色块喧宾夺主）
        // 开启轨道底色（用户定稿 2026-09-06）：浅色↔深色两主题**互换**彼此的原配色；莫兰迪保持原样。
        //   light = 原深色的 #5165CD；dark = 原浅色的 #4B5593；morandi = 原 soften(#9A8778)=#6A625B。
        val themeKey = com.qiqiao.passwordvault.util.ThemePrefs.currentTheme(context)
        trackOnColor = when (themeKey) {
            "dark" -> 0xFF4B5593.toInt()
            "morandi" -> soften(trackOn)
            else -> 0xFF5165CD.toInt()
        }
        glyphOnColor = blend(trackOnColor, Color.BLACK, 0.5f)
        glyphOffColor = blend(trackOff, Color.BLACK, 0.4f)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NeuShadowPrefs.addListener(prefsListener)
    }

    override fun onDetachedFromWindow() {
        NeuShadowPrefs.removeListener(prefsListener)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(trackW.toInt(), trackH.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val viewAlpha = if (isEnabled) 1f else 0.45f
        if (viewAlpha < 1f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(),
            (viewAlpha * 255).toInt())

        // 1) 轨道填充：off/on 底色随进度混合（开启落到加深提饱和后的实心紫，而非浅主色）
        val trackColor = blend(trackOff, trackOnColor, progress)
        trackRect.set(0f, 0f, trackW, trackH)
        trackPaint.color = trackColor
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, trackPaint)

        // 2) 轨道内阴影环带（凹陷：左上暗带 + 右下亮带，颜色随轨道色联动）
        //    开启态内壁暗带更深更宽 → 真正的"凹槽"；关闭态浅刻痕即可
        val (idx, idy) = NeuShadowPrefs.offsetVector(
            NeuShadowPrefs.insetDarkAngleDeg, NeuShadowPrefs.insetDarkOffsetDp, density)
        val (ilx, ily) = NeuShadowPrefs.offsetVector(
            NeuShadowPrefs.insetLightAngleDeg, NeuShadowPrefs.insetLightOffsetDp, density)
        val darkK = lerp(0.30f, 0.52f, progress)   // 关浅开深：开态内壁暗色占比更高 → 凹槽更重
        val lightK = lerp(0.60f, 0.22f, progress)  // 开态压缩亮带，制造更锐的凹槽口
        val insetDark = applyAlpha(
            blend(trackColor, Color.BLACK, darkK),
            NeuShadowPrefs.alphaFactor(NeuShadowPrefs.insetDarkAlphaPercent))
        val insetLight = applyAlpha(
            blend(trackColor, Color.WHITE, lightK),
            NeuShadowPrefs.alphaFactor(NeuShadowPrefs.insetLightAlphaPercent))

        canvas.save()
        tmpPath.reset()
        tmpPath.addRoundRect(trackRect, trackH / 2f, trackH / 2f, Path.Direction.CW)
        canvas.clipPath(tmpPath)
        drawRing(canvas, insetDark, NeuShadowPrefs.insetDarkBlurDp * density, idx, idy)
        drawRing(canvas, insetLight, NeuShadowPrefs.insetLightBlurDp * density, -ilx, -ily)
        canvas.restore()

        // 3) 滑块（凸起白球，光源左上）。阴影随进度在两态间插值：
        //    关（浅底）：白高光稍强、暗影稍大稍淡即可成形；
        //    开（灰紫深底）：白高光收敛为细亮边（近消失），暗影改**更深近黑 + 更紧小**，
        //    避免在深底上糊成灰雾、也避免左上亮斑过大。
        val cx = pad + thumbR + travel * progress
        val cy = trackH / 2f

        // 右下暗投影：开态颜色更深（黑 blend 0.28→0.55）、α 更克制（0.66→0.50）、blur/偏移收紧
        val darkBlend = lerp(0.28f, 0.55f, progress)
        val darkAlpha = lerp(0.66f, 0.50f, progress)
        val thumbDark = applyAlpha(blend(shadowDark, Color.BLACK, darkBlend), darkAlpha)
        thumbPaint.color = thumbDark
        thumbPaint.setShadowLayer(
            dp(lerp(4.2f, 3.0f, progress)),
            dp(lerp(2.4f, 2.0f, progress)),
            dp(lerp(3.2f, 2.6f, progress)),
            thumbDark)
        canvas.drawCircle(cx, cy, thumbR, thumbPaint)

        // 左上白高光：开态收敛成细亮边（blur/偏移/α 同时压低，深底不糊斑）
        val thumbLight = applyAlpha(shadowLight, lerp(0.85f, 0.70f, progress))
        thumbPaint.color = thumbLight
        thumbPaint.setShadowLayer(
            dp(lerp(1.8f, 1.0f, progress)),
            -dp(lerp(1.2f, 0.8f, progress)),
            -dp(lerp(1.4f, 1.0f, progress)),
            thumbLight)
        canvas.drawCircle(cx, cy, thumbR, thumbPaint)

        thumbPaint.clearShadowLayer()
        thumbPaint.color = thumbColor
        canvas.drawCircle(cx, cy, thumbR, thumbPaint)

        // 4) 滑块图标（✓ = 已开启 / ✕ = 关闭）——颜色随进度在灰/深紫间过渡，白底上始终可读
        val glyphColor = blend(glyphOffColor, glyphOnColor, progress)
        glyphPaint.color = glyphColor
        buildGlyph(glyphPath, cx, cy, if (targetChecked) 1f else 0f)
        canvas.drawPath(glyphPath, glyphPaint)

        if (viewAlpha < 1f) canvas.restore()
    }

    /** 勾/叉 path（s = 相对滑块半径的缩放）。 */
    private fun buildGlyph(path: Path, cx: Float, cy: Float, mode: Float) {
        path.reset()
        val s = thumbR * 0.44f
        if (mode >= 0.5f) {
            // ✓：左下起笔 → 中点 → 右上收笔
            path.moveTo(cx - 0.52f * s, cy + 0.02f * s)
            path.lineTo(cx - 0.14f * s, cy + 0.40f * s)
            path.lineTo(cx + 0.56f * s, cy - 0.42f * s)
        } else {
            // ✕
            path.moveTo(cx - 0.38f * s, cy - 0.38f * s)
            path.lineTo(cx + 0.38f * s, cy + 0.38f * s)
            path.moveTo(cx - 0.38f * s, cy + 0.38f * s)
            path.lineTo(cx + 0.38f * s, cy - 0.38f * s)
        }
    }

    /** 内阴影环带：外矩形 − 平移 (dx,dy) 后的胶囊 shape，高斯模糊（同 NeuSurface.drawRingHalo）。 */
    private fun drawRing(canvas: Canvas, argb: Int, blurPx: Float, dx: Float, dy: Float) {
        val pad = blurPx * 2f + 2f
        val outer = Path()
        outer.addRect(-pad, -pad, trackW + pad, trackH + pad, Path.Direction.CW)
        innerRect.set(dx, dy, trackW + dx, trackH + dy)
        val inner = Path()
        inner.addRoundRect(innerRect, trackH / 2f, trackH / 2f, Path.Direction.CW)
        val ring = Path()
        ring.op(outer, inner, Path.Op.DIFFERENCE)

        ringPaint.color = argb
        ringPaint.maskFilter = if (blurPx > 0.5f) {
            BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        } else null
        canvas.drawPath(ring, ringPaint)
        ringPaint.maskFilter = null
    }

    // ---- 交互：点击拨动（仅此处回调监听器）----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        targetChecked = !targetChecked
        val nowChecked = targetChecked
        animateTo(if (nowChecked) 1f else 0f)
        onCheckedChange?.invoke(this, nowChecked)
        return true
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 220
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ---- 工具 ----

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun blend(from: Int, to: Int, t: Float): Int {
        val a = (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt()
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.argb(a, r, g, b)
    }

    private fun applyAlpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** 灰紫化：降饱和（×0.55）+ 调暗（×0.72），让开启轨道成为低刺激的强调底色而非大色块。 */
    private fun soften(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 0.55f).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * 0.72f).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun resolveColor(@AttrRes attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                try {
                    ResourcesCompat.getColor(context.resources, tv.resourceId, context.theme)
                } catch (e: Exception) {
                    tv.data
                }
            } else tv.data
        } else fallback
    }
}
