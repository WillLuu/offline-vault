package com.qiqiao.passwordvault.ui.common

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局新拟态阴影参数（设置页可手动调节，SharedPreferences 持久化）。
 *
 * 四组参数**完全独立**：
 * - dark*        外阴影·暗影（凸起外侧的暗 halo）
 * - light*       外阴影·亮影（凸起外侧的亮 halo）
 * - insetDark*  内阴影·暗（凹陷内壁的暗带，angle 方向反向侧）
 * - insetLight* 内阴影·亮（凹陷内壁的亮带，angle 侧）
 *
 * 每组 4 参数：
 * - offsetDp   阴影大小/偏移距离（默认外 9dp / 内 6dp）
 * - blurDp     边缘模糊半径（默认外 18dp / 内 12dp，对齐模板 inset 6px 6px 12px）
 * - alpha      透明度 10..100%（默认 100）
 * - angleDeg   角度 0..360°（默认 45° = 右下；0° = 右，90° = 下）
 *
 * 参数变更通过 updateDark/updateLight/updateInsetDark/updateInsetLight 提交：
 * 立即持久化 + 通知所有存活 NeuSurface 重绘（设置页拖杆实时预览）。
 */
object NeuShadowPrefs {

    private const val FILE = "neu_shadow"
    private const val K_D_OFFSET = "dark_offset_dp"
    private const val K_D_BLUR = "dark_blur_dp"
    private const val K_D_ALPHA = "dark_alpha_percent"
    private const val K_D_ANGLE = "dark_angle_deg"
    private const val K_L_OFFSET = "light_offset_dp"
    private const val K_L_BLUR = "light_blur_dp"
    private const val K_L_ALPHA = "light_alpha_percent"
    private const val K_L_ANGLE = "light_angle_deg"
    private const val K_I_D_OFFSET = "inset_dark_offset_dp"
    private const val K_I_D_BLUR = "inset_dark_blur_dp"
    private const val K_I_D_ALPHA = "inset_dark_alpha_percent"
    private const val K_I_D_ANGLE = "inset_dark_angle_deg"
    private const val K_I_L_OFFSET = "inset_light_offset_dp"
    private const val K_I_L_BLUR = "inset_light_blur_dp"
    private const val K_I_L_ALPHA = "inset_light_alpha_percent"
    private const val K_I_L_ANGLE = "inset_light_angle_deg"

    // ---- 默认参数（用户实测定稿配方，2026-09-05 截图定稿）----
    // 外·暗影 3/2/80%/55° · 外·亮影 3/3/50%/210°
    // 内·暗 5/2/80%/55° · 内·亮 2/2/70%/55°
    const val DEFAULT_DARK_OFFSET_DP = 3f
    const val DEFAULT_DARK_BLUR_DP = 2f
    const val DEFAULT_DARK_ALPHA_PERCENT = 80
    const val DEFAULT_DARK_ANGLE_DEG = 55f
    const val DEFAULT_LIGHT_OFFSET_DP = 3f
    const val DEFAULT_LIGHT_BLUR_DP = 3f
    const val DEFAULT_LIGHT_ALPHA_PERCENT = 50
    const val DEFAULT_LIGHT_ANGLE_DEG = 210f
    const val DEFAULT_INSET_D_OFFSET_DP = 5f
    const val DEFAULT_INSET_D_BLUR_DP = 2f
    const val DEFAULT_INSET_D_ALPHA_PERCENT = 80
    const val DEFAULT_INSET_D_ANGLE_DEG = 55f
    const val DEFAULT_INSET_L_OFFSET_DP = 2f
    const val DEFAULT_INSET_L_BLUR_DP = 2f
    const val DEFAULT_INSET_L_ALPHA_PERCENT = 70
    const val DEFAULT_INSET_L_ANGLE_DEG = 55f

    // 兼容旧引用（阴影调节 SeekBar 量程等）
    const val DEFAULT_ALPHA_PERCENT = DEFAULT_DARK_ALPHA_PERCENT

    const val MIN_OFFSET_DP = 0f
    const val MAX_OFFSET_DP = 20f
    const val MIN_BLUR_DP = 0f
    const val MAX_BLUR_DP = 40f
    const val MIN_ALPHA = 10
    const val MAX_ALPHA = 100
    const val MIN_ANGLE = 0f
    const val MAX_ANGLE = 360

    // ---- 外阴影·暗影（dark）----
    @Volatile var darkOffsetDp: Float = DEFAULT_DARK_OFFSET_DP; private set
    @Volatile var darkBlurDp: Float = DEFAULT_DARK_BLUR_DP; private set
    @Volatile var darkAlphaPercent: Int = DEFAULT_DARK_ALPHA_PERCENT; private set
    @Volatile var darkAngleDeg: Float = DEFAULT_DARK_ANGLE_DEG; private set

    // ---- 外阴影·亮影（light）----
    @Volatile var lightOffsetDp: Float = DEFAULT_LIGHT_OFFSET_DP; private set
    @Volatile var lightBlurDp: Float = DEFAULT_LIGHT_BLUR_DP; private set
    @Volatile var lightAlphaPercent: Int = DEFAULT_LIGHT_ALPHA_PERCENT; private set
    @Volatile var lightAngleDeg: Float = DEFAULT_LIGHT_ANGLE_DEG; private set

    // ---- 内阴影·暗（insetDark）----
    @Volatile var insetDarkOffsetDp: Float = DEFAULT_INSET_D_OFFSET_DP; private set
    @Volatile var insetDarkBlurDp: Float = DEFAULT_INSET_D_BLUR_DP; private set
    @Volatile var insetDarkAlphaPercent: Int = DEFAULT_INSET_D_ALPHA_PERCENT; private set
    @Volatile var insetDarkAngleDeg: Float = DEFAULT_INSET_D_ANGLE_DEG; private set

    // ---- 内阴影·亮（insetLight）----
    @Volatile var insetLightOffsetDp: Float = DEFAULT_INSET_L_OFFSET_DP; private set
    @Volatile var insetLightBlurDp: Float = DEFAULT_INSET_L_BLUR_DP; private set
    @Volatile var insetLightAlphaPercent: Int = DEFAULT_INSET_L_ALPHA_PERCENT; private set
    @Volatile var insetLightAngleDeg: Float = DEFAULT_INSET_L_ANGLE_DEG; private set

    private lateinit var sp: SharedPreferences

    /** Application 启动时调用一次（MainApplication.onCreate）。 */
    fun init(appContext: Context) {
        sp = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        darkOffsetDp = sp.getFloat(K_D_OFFSET, DEFAULT_DARK_OFFSET_DP).coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP)
        lightOffsetDp = sp.getFloat(K_L_OFFSET, DEFAULT_LIGHT_OFFSET_DP).coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP)
        insetDarkOffsetDp = sp.getFloat(K_I_D_OFFSET, DEFAULT_INSET_D_OFFSET_DP).coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP)
        insetLightOffsetDp = sp.getFloat(K_I_L_OFFSET, DEFAULT_INSET_L_OFFSET_DP).coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP)
        darkBlurDp = sp.getFloat(K_D_BLUR, DEFAULT_DARK_BLUR_DP).coerceIn(MIN_BLUR_DP, MAX_BLUR_DP)
        lightBlurDp = sp.getFloat(K_L_BLUR, DEFAULT_LIGHT_BLUR_DP).coerceIn(MIN_BLUR_DP, MAX_BLUR_DP)
        insetDarkBlurDp = sp.getFloat(K_I_D_BLUR, DEFAULT_INSET_D_BLUR_DP).coerceIn(MIN_BLUR_DP, MAX_BLUR_DP)
        insetLightBlurDp = sp.getFloat(K_I_L_BLUR, DEFAULT_INSET_L_BLUR_DP).coerceIn(MIN_BLUR_DP, MAX_BLUR_DP)
        darkAlphaPercent = sp.getInt(K_D_ALPHA, DEFAULT_DARK_ALPHA_PERCENT).coerceIn(MIN_ALPHA, MAX_ALPHA)
        lightAlphaPercent = sp.getInt(K_L_ALPHA, DEFAULT_LIGHT_ALPHA_PERCENT).coerceIn(MIN_ALPHA, MAX_ALPHA)
        insetDarkAlphaPercent = sp.getInt(K_I_D_ALPHA, DEFAULT_INSET_D_ALPHA_PERCENT).coerceIn(MIN_ALPHA, MAX_ALPHA)
        insetLightAlphaPercent = sp.getInt(K_I_L_ALPHA, DEFAULT_INSET_L_ALPHA_PERCENT).coerceIn(MIN_ALPHA, MAX_ALPHA)
        darkAngleDeg = sp.getFloat(K_D_ANGLE, DEFAULT_DARK_ANGLE_DEG).coerceIn(MIN_ANGLE, MAX_ANGLE.toFloat())
        lightAngleDeg = sp.getFloat(K_L_ANGLE, DEFAULT_LIGHT_ANGLE_DEG).coerceIn(MIN_ANGLE, MAX_ANGLE.toFloat())
        insetDarkAngleDeg = sp.getFloat(K_I_D_ANGLE, DEFAULT_INSET_D_ANGLE_DEG).coerceIn(MIN_ANGLE, MAX_ANGLE.toFloat())
        insetLightAngleDeg = sp.getFloat(K_I_L_ANGLE, DEFAULT_INSET_L_ANGLE_DEG).coerceIn(MIN_ANGLE, MAX_ANGLE.toFloat())
    }

    private val listeners = mutableListOf<() -> Unit>()

    /** NeuSurface 在 attachedToWindow 时注册，detached 时反注册。 */
    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun notifyAll_() { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    /** 通用提交：持久化 + 全量刷新。参数名带 p 前缀避免遮蔽同名属性。 */
    private fun commit(
        pOffset: Float?, pBlur: Float?, pAlpha: Int?, pAngle: Float?,
        curOffset: Float, curBlur: Float, curAlpha: Int, curAngle: Float,
        kOffset: String, kBlur: String, kAlpha: String, kAngle: String
    ): Quartet {
        val o = (pOffset ?: curOffset).coerceIn(MIN_OFFSET_DP, MAX_OFFSET_DP)
        val b = (pBlur ?: curBlur).coerceIn(MIN_BLUR_DP, MAX_BLUR_DP)
        val a = (pAlpha ?: curAlpha).coerceIn(MIN_ALPHA, MAX_ALPHA)
        val g = (pAngle ?: curAngle).coerceIn(MIN_ANGLE, MAX_ANGLE.toFloat())
        sp.edit()
            .putFloat(kOffset, o).putFloat(kBlur, b)
            .putInt(kAlpha, a).putFloat(kAngle, g)
            .apply()
        notifyAll_()
        return Quartet(o, b, a, g)
    }

    /** 提交暗影参数。传 null 表示保持不变。返回生效值。 */
    fun updateDark(offsetDp: Float? = null, blurDp: Float? = null, alphaPercent: Int? = null, angleDeg: Float? = null): Quartet {
        val q = commit(offsetDp, blurDp, alphaPercent, angleDeg,
            darkOffsetDp, darkBlurDp, darkAlphaPercent, darkAngleDeg,
            K_D_OFFSET, K_D_BLUR, K_D_ALPHA, K_D_ANGLE)
        darkOffsetDp = q.offset; darkBlurDp = q.blur; darkAlphaPercent = q.alpha; darkAngleDeg = q.angle
        return q
    }

    /** 提交亮影参数。传 null 表示保持不变。返回生效值。 */
    fun updateLight(offsetDp: Float? = null, blurDp: Float? = null, alphaPercent: Int? = null, angleDeg: Float? = null): Quartet {
        val q = commit(offsetDp, blurDp, alphaPercent, angleDeg,
            lightOffsetDp, lightBlurDp, lightAlphaPercent, lightAngleDeg,
            K_L_OFFSET, K_L_BLUR, K_L_ALPHA, K_L_ANGLE)
        lightOffsetDp = q.offset; lightBlurDp = q.blur; lightAlphaPercent = q.alpha; lightAngleDeg = q.angle
        return q
    }

    /** 提交内阴影·暗参数。传 null 表示保持不变。返回生效值。 */
    fun updateInsetDark(offsetDp: Float? = null, blurDp: Float? = null, alphaPercent: Int? = null, angleDeg: Float? = null): Quartet {
        val q = commit(offsetDp, blurDp, alphaPercent, angleDeg,
            insetDarkOffsetDp, insetDarkBlurDp, insetDarkAlphaPercent, insetDarkAngleDeg,
            K_I_D_OFFSET, K_I_D_BLUR, K_I_D_ALPHA, K_I_D_ANGLE)
        insetDarkOffsetDp = q.offset; insetDarkBlurDp = q.blur; insetDarkAlphaPercent = q.alpha; insetDarkAngleDeg = q.angle
        return q
    }

    /** 提交内阴影·亮参数。传 null 表示保持不变。返回生效值。 */
    fun updateInsetLight(offsetDp: Float? = null, blurDp: Float? = null, alphaPercent: Int? = null, angleDeg: Float? = null): Quartet {
        val q = commit(offsetDp, blurDp, alphaPercent, angleDeg,
            insetLightOffsetDp, insetLightBlurDp, insetLightAlphaPercent, insetLightAngleDeg,
            K_I_L_OFFSET, K_I_L_BLUR, K_I_L_ALPHA, K_I_L_ANGLE)
        insetLightOffsetDp = q.offset; insetLightBlurDp = q.blur; insetLightAlphaPercent = q.alpha; insetLightAngleDeg = q.angle
        return q
    }

    /** 恢复默认（外阴影 9/18/100/45；内阴影 6/12/100/45，暗亮同默认）。 */
    fun resetToDefault() {
        updateDark(DEFAULT_DARK_OFFSET_DP, DEFAULT_DARK_BLUR_DP, DEFAULT_DARK_ALPHA_PERCENT, DEFAULT_DARK_ANGLE_DEG)
        updateLight(DEFAULT_LIGHT_OFFSET_DP, DEFAULT_LIGHT_BLUR_DP, DEFAULT_LIGHT_ALPHA_PERCENT, DEFAULT_LIGHT_ANGLE_DEG)
        updateInsetDark(DEFAULT_INSET_D_OFFSET_DP, DEFAULT_INSET_D_BLUR_DP, DEFAULT_INSET_D_ALPHA_PERCENT, DEFAULT_INSET_D_ANGLE_DEG)
        updateInsetLight(DEFAULT_INSET_L_OFFSET_DP, DEFAULT_INSET_L_BLUR_DP, DEFAULT_INSET_L_ALPHA_PERCENT, DEFAULT_INSET_L_ANGLE_DEG)
    }

    /** 角度 → (dx, dy) 单位向量 × offset。 */
    fun offsetVector(angleDeg: Float, offsetDp: Float, density: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val off = offsetDp * density
        return Pair((Math.cos(rad) * off).toFloat(), (Math.sin(rad) * off).toFloat())
    }

    /** 透明度 → 0..1 因子。 */
    fun alphaFactor(alphaPercent: Int): Float = alphaPercent / 100f

    data class Quartet(val offset: Float, val blur: Float, val alpha: Int, val angle: Float)
}