package com.qiqiao.passwordvault.ui.common

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.core.content.res.ResourcesCompat
import com.qiqiao.passwordvault.R

/**
 * 新拟态真双影容器（纯色填充 + 暗/亮双阴影版）。
 *
 * 等价于 CSS：
 *   .neu    { box-shadow:  DXd DYd BLURd dark, -DXl -DYl BLURl light; background: <底色>; }
 *   .neu-in { box-shadow: inset DXd DYd BLURd dark, inset -DXl -DYl BLURl light; background: <下沉色>; }
 *
 * 渲染顺序（关键，上一版凹陷失败的根因）：
 * - RAISED：先画两个外扩 halo，再画不透明 fill 盖住中央 → halo 只剩边缘可见。
 * - INSET：先画不透明 fill（下沉色），**再**在其上画内阴影层
 *   （TRANSPARENT shape + setShadowLayer halo，DST_IN 裁到 shape 内部）。
 *   上一版顺序反了（fill 最后画），内阴影被 fill 完全盖住 → "凹陷没做内阴影"。
 *
 * 方向语义（CSS inset 对齐）：
 * - setShadowLayer(blur, +dx, +dy) 的 halo 相对原 shape 偏右下 → 裁剪后内部可见带在**左上内壁**。
 *   即 dark(+dx,+dy) → 内部左上暗带；light(-dx,-dy) → 内部右下亮带。
 * - RAISED 不裁剪：dark(+dx,+dy) → 外部右下暗 halo；light(-dx,-dy) → 外部左上亮 halo。
 *
 * 阴影参数全局统一且暗/亮两组独立（NeuShadowPrefs，设置页可调，实时生效）。
 */
class NeuSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Shape { ROUND_RECT, CIRCLE }

    /** 保留档位枚举以兼容既有 XML/代码；实际参数全局统一走 NeuShadowPrefs。 */
    enum class Elevation { NONE, XS, SM, LG }

    enum class Direction { RAISED, INSET }

    private val density = resources.displayMetrics.density

    private var shape: Shape = Shape.ROUND_RECT

    var elevation: Elevation = Elevation.LG

    var direction: Direction = Direction.RAISED
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 圆角半径（px）。程序化创建（无 XML attr）时默认 0，必须显式设置，
     * 否则凹陷态的内阴影环带是直角带（列表 chip 踩过）。赋值即重绘。
     */
    var cornerRadiusPx: Float
        get() = cornerRadius
        set(value) {
            if (cornerRadius != value) {
                cornerRadius = value
                invalidate()
            }
        }

    private var cornerRadius: Float = 0f

    private var darkColor: Int = 0xFFA3B1C6.toInt()
    private var lightColor: Int = 0xFFFFFFFF.toInt()
    /** RAISED 主面 = 页面底色（同色凸起） */
    private var raisedFillColor: Int = 0xFFE0E5EC.toInt()
    /** INSET 主面 = 下沉色（比底色略深一档；下沉感主要由内阴影塑形） */
    private var insetFillColor: Int = 0xFFD6DAE2.toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val darkShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lightShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** 内阴影环带专用 paint（BlurMaskFilter） */
    private val insetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** drawRingHalo 专用临时矩形（不复用 tmpRect，避免与 DST_IN 裁剪的 shape 矩形互踩） */
    private val insetRect = RectF()

    private val tmpRect = RectF()

    private val prefsListener = { invalidate() }

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.NeuSurface, defStyleAttr, 0)
        try {
            shape = when (a.getInt(R.styleable.NeuSurface_neuShape, 0)) {
                1 -> Shape.CIRCLE
                else -> Shape.ROUND_RECT
            }
            elevation = when (a.getInt(R.styleable.NeuSurface_neuElevation, 3)) {
                0 -> Elevation.NONE
                1 -> Elevation.XS
                2 -> Elevation.SM
                else -> Elevation.LG
            }
            direction = when (a.getInt(R.styleable.NeuSurface_neuDirection, 0)) {
                1 -> Direction.INSET
                else -> Direction.RAISED
            }
            cornerRadius = a.getDimension(R.styleable.NeuSurface_neuCornerRadius, 0f)
        } finally {
            a.recycle()
        }

        darkColor = resolveThemeColor(context, R.attr.pwdShadowDark, darkColor)
        lightColor = resolveThemeColor(context, R.attr.pwdShadowLight, lightColor)
        raisedFillColor = resolveThemeColor(context, android.R.attr.colorBackground, raisedFillColor)
        insetFillColor = resolveThemeColor(context, R.attr.pwdInsetBg, insetFillColor)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        NeuShadowPrefs.addListener(prefsListener)
    }

    override fun onDetachedFromWindow() {
        NeuShadowPrefs.removeListener(prefsListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (elevation == Elevation.NONE) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val (ddx, ddy) = NeuShadowPrefs.offsetVector(
            NeuShadowPrefs.darkAngleDeg, NeuShadowPrefs.darkOffsetDp, density)
        val (ldx, ldy) = NeuShadowPrefs.offsetVector(
            NeuShadowPrefs.lightAngleDeg, NeuShadowPrefs.lightOffsetDp, density)
        val darkBlur = NeuShadowPrefs.darkBlurDp * density
        val lightBlur = NeuShadowPrefs.lightBlurDp * density
        val darkArgb = applyAlpha(darkColor, NeuShadowPrefs.alphaFactor(NeuShadowPrefs.darkAlphaPercent))
        val lightArgb = applyAlpha(lightColor, NeuShadowPrefs.alphaFactor(NeuShadowPrefs.lightAlphaPercent))

        // 内阴影两组独立参数（与外阴影完全解耦，设置页分别调节）
        val (idx, idy) = NeuShadowPrefs.offsetVector(
            NeuShadowPrefs.insetDarkAngleDeg, NeuShadowPrefs.insetDarkOffsetDp, density)
        val insetDarkBlur = NeuShadowPrefs.insetDarkBlurDp * density
        val insetDarkArgb = applyAlpha(darkColor, NeuShadowPrefs.alphaFactor(NeuShadowPrefs.insetDarkAlphaPercent))
        val (ilx, ily) = NeuShadowPrefs.offsetVector(
            NeuShadowPrefs.insetLightAngleDeg, NeuShadowPrefs.insetLightOffsetDp, density)
        val insetLightBlur = NeuShadowPrefs.insetLightBlurDp * density
        val insetLightArgb = applyAlpha(lightColor, NeuShadowPrefs.alphaFactor(NeuShadowPrefs.insetLightAlphaPercent))

        if (shape == Shape.CIRCLE) {
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f
            tmpRect.set(cx - r, cy - r, cx + r, cy + r)
        } else {
            tmpRect.set(0f, 0f, w, h)
        }

        if (direction == Direction.RAISED) {
            // 凸起：先画两个外扩 halo，再画 fill 盖住中央
            darkShadowPaint.color = darkArgb
            darkShadowPaint.setShadowLayer(darkBlur, ddx, ddy, darkArgb)
            drawShape(canvas, darkShadowPaint)

            lightShadowPaint.color = lightArgb
            lightShadowPaint.setShadowLayer(lightBlur, ldx, ldy, lightArgb)
            drawShape(canvas, lightShadowPaint)

            fillPaint.clearShadowLayer()
            fillPaint.color = raisedFillColor
            drawShape(canvas, fillPaint)
        } else {
            // 凹陷：fill（下沉色）+ clipPath 内画环带。
            // 不用 saveLayer+DST_IN：实测红魔 HW 加速下 DST_IN 不生效，
            // 圆角 fill 外残留"直角模糊晕"（视觉=凹陷变直角、调参无效）。
            // clipPath 等价 CSS background-clip: padding-box——直接裁进圆角形状。
            fillPaint.clearShadowLayer()
            fillPaint.color = insetFillColor
            drawShape(canvas, fillPaint)

            canvas.save()
            canvas.clipPath(buildShapePath(0f, 0f, w, h))
            drawRingHalo(canvas, w, h, insetDarkArgb, insetDarkBlur, idx, idy)
            drawRingHalo(canvas, w, h, insetLightArgb, insetLightBlur, -ilx, -ily)
            canvas.restore()
        }
    }

    /** 构造 shape 的 Path（ROUND_RECT=圆角矩形 / CIRCLE=圆），可带偏移。 */
    private fun buildShapePath(offX: Float, offY: Float, w: Float, h: Float): Path {
        val p = Path()
        if (shape == Shape.CIRCLE) {
            p.addCircle(w / 2f + offX, h / 2f + offY, minOf(w, h) / 2f, Path.Direction.CW)
        } else {
            insetRect.set(offX, offY, w + offX, h + offY)
            p.addRoundRect(insetRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }
        return p
    }

    /**
     * 确定性内阴影环带（替代 setShadowLayer——后者在 HW 加速下对非文本图元的 halo
     * 存在平台差异，会把圆角渲染成直角且强度不可控）。
     *
     * 算法：构造「大外矩形 − 平移 (dx,dy) 后的圆角 shape」的环形 Path，
     * 用 BlurMaskFilter(高斯) 模糊后绘制。inner 平移使环带在 offset 反向侧更宽，
     * 与 CSS `inset dx dy blur` 语义一致。最后由调用方用 DST_IN 裁进 shape。
     */
    private fun drawRingHalo(canvas: Canvas, w: Float, h: Float, argb: Int, blurPx: Float, dx: Float, dy: Float) {
        val pad = blurPx * 2f + 2f
        val outer = Path()
        outer.addRect(-pad, -pad, w + pad, h + pad, Path.Direction.CW)

        val inner = Path()
        if (shape == Shape.CIRCLE) {
            inner.addCircle(w / 2f + dx, h / 2f + dy, minOf(w, h) / 2f, Path.Direction.CW)
        } else {
            insetRect.set(dx, dy, w + dx, h + dy)
            inner.addRoundRect(insetRect, cornerRadius, cornerRadius, Path.Direction.CW)
        }

        val ring = Path()
        ring.op(outer, inner, Path.Op.DIFFERENCE)

        insetPaint.color = argb
        insetPaint.maskFilter = if (blurPx > 0.5f) {
            BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
        canvas.drawPath(ring, insetPaint)
        insetPaint.maskFilter = null
    }

    /** 把 alpha 因子（0..1）乘到颜色的 alpha 通道。 */
    private fun applyAlpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun drawShape(canvas: Canvas, paint: Paint) {
        drawShape(canvas, tmpRect, paint)
    }

    private fun drawShape(canvas: Canvas, rect: RectF, paint: Paint) {
        if (shape == Shape.CIRCLE) {
            val cx = (rect.left + rect.right) / 2f
            val cy = (rect.top + rect.bottom) / 2f
            val r = minOf(rect.width(), rect.height()) / 2f
            canvas.drawCircle(cx, cy, r, paint)
        } else {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
    }

    companion object {
        private fun resolveThemeColor(context: Context, @AttrRes attr: Int, fallback: Int): Int {
            val tv = TypedValue()
            return if (context.theme.resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) {
                    try {
                        ResourcesCompat.getColor(context.resources, tv.resourceId, context.theme)
                    } catch (e: Exception) {
                        tv.data
                    }
                } else {
                    tv.data
                }
            } else {
                fallback
            }
        }
    }
}