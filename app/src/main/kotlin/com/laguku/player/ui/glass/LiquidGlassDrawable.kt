package com.laguku.player.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.laguku.player.model.CoverPalette
import com.laguku.player.ui.motion.SpringAnimatorHelper
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Liquid Glass surface drawable:
 * - Strict 2-layer separation support (Content Layer vs Functional Layer)
 * - Regular vs Clear variant gating & Small vs Large polarity rules
 * - Continuous Transparency (iOS 27) + Accessibility Parity (Reduce Transparency / Increase Contrast / Reduce Motion)
 * - Zero object allocations inside [draw].
 */
class LiquidGlassDrawable(
    private var cornerRadiusPx: Float,
    rimWidthPx: Float,
    initialPalette: CoverPalette = CoverPaletteExtractor.defaultPalette()
) : Drawable() {

    enum class SurfaceLayer {
        CONTENT,
        FUNCTIONAL
    }

    enum class GlassVariant {
        REGULAR,
        CLEAR
    }

    enum class GlassSurfaceScale {
        SMALL,
        LARGE
    }

    data class AccessibilityFlags(
        val reduceTransparency: Boolean = false,
        val increaseContrast: Boolean = false,
        val reduceMotion: Boolean = false
    )

    object LayerTokens {
        const val LIGHT_PAGE_GROUND: Int = 0xFFF2F2F7.toInt()
        const val LIGHT_ELEVATED_CARD: Int = 0xFFFFFFFF.toInt()
        const val LIGHT_NESTED_CARD: Int = 0xFFF6F6F8.toInt()
        const val LIGHT_HAIRLINE: Int = 0x1F3C3C43

        const val DARK_BASE_GROUND: Int = 0xFF131316.toInt()
        const val DARK_ELEVATED_CARD: Int = 0xFF1D1D20.toInt()
        const val DARK_NESTED_CARD: Int = 0xFF26262A.toInt()
        const val DARK_HAIRLINE: Int = 0x1FFFFFFF

        const val REDUCED_TRANSPARENCY_ALPHA: Float = 0.92f
        const val REDUCED_TRANSPARENCY_LIGHT_FILL: Int = 0xEBFCFCFE.toInt()
        const val REDUCED_TRANSPARENCY_DARK_FILL: Int = 0xEB222226.toInt()

        const val HIGH_CONTRAST_LIGHT_FILL: Int = 0xFFFFFFFF.toInt()
        const val HIGH_CONTRAST_LIGHT_BORDER: Int = 0xFF000000.toInt()
        const val HIGH_CONTRAST_DARK_FILL: Int = 0xFF000000.toInt()
        const val HIGH_CONTRAST_DARK_BORDER: Int = 0xFFFFFFFF.toInt()

        const val MIN_CONTINUOUS_ALPHA: Float = 0.26f
        const val MAX_CONTINUOUS_ALPHA: Float = 0.88f
        const val CLEAR_MEDIA_DIMMING_VEIL: Int = 0x59000000

        fun contentCardColor(isDarkMode: Boolean, highContrast: Boolean = false): Int {
            return when {
                highContrast && isDarkMode -> HIGH_CONTRAST_DARK_FILL
                highContrast -> HIGH_CONTRAST_LIGHT_FILL
                isDarkMode -> DARK_ELEVATED_CARD
                else -> LIGHT_ELEVATED_CARD
            }
        }

        fun nestedCardColor(isDarkMode: Boolean, highContrast: Boolean = false): Int {
            return when {
                highContrast && isDarkMode -> DARK_ELEVATED_CARD
                highContrast -> LIGHT_PAGE_GROUND
                isDarkMode -> DARK_NESTED_CARD
                else -> LIGHT_NESTED_CARD
            }
        }

        fun pageGroundColor(isDarkMode: Boolean, highContrast: Boolean = false): Int {
            return when {
                highContrast && isDarkMode -> HIGH_CONTRAST_DARK_FILL
                highContrast -> HIGH_CONTRAST_LIGHT_FILL
                isDarkMode -> DARK_BASE_GROUND
                else -> LIGHT_PAGE_GROUND
            }
        }

        fun hairlineColor(isDarkMode: Boolean): Int {
            return if (isDarkMode) DARK_HAIRLINE else LIGHT_HAIRLINE
        }

        fun resolveContinuousTransparencyAlpha(
            baseAlpha: Float,
            transparencyLevel: Float,
            accessibility: AccessibilityFlags = AccessibilityFlags()
        ): Float {
            if (accessibility.increaseContrast) return 1.0f
            if (accessibility.reduceTransparency) return REDUCED_TRANSPARENCY_ALPHA
            val clampedBase = baseAlpha.coerceIn(MIN_CONTINUOUS_ALPHA, MAX_CONTINUOUS_ALPHA)
            val t = transparencyLevel.coerceIn(0f, 1f)
            return if (t <= 0.5f) {
                val frac = t / 0.5f
                MIN_CONTINUOUS_ALPHA + (clampedBase - MIN_CONTINUOUS_ALPHA) * frac
            } else {
                val frac = (t - 0.5f) / 0.5f
                clampedBase + (MAX_CONTINUOUS_ALPHA - clampedBase) * frac
            }
        }

        fun isRefractionAllowed(accessibility: AccessibilityFlags): Boolean {
            return !accessibility.reduceTransparency && !accessibility.increaseContrast
        }

        fun isGelMotionAllowed(accessibility: AccessibilityFlags): Boolean {
            return !accessibility.reduceMotion
        }

        fun canUseClearVariant(
            overRichMedia: Boolean,
            dimmingVeilSafe: Boolean,
            boldBrightForeground: Boolean
        ): Boolean {
            return overRichMedia && dimmingVeilSafe && boldBrightForeground
        }

        fun readSystemAccessibilityFlags(context: Context): AccessibilityFlags {
            val reduceMotion = SpringAnimatorHelper.isReducedMotionEnabled(context)
            val increaseContrast = runCatching {
                Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
            }.getOrDefault(false)
            val reduceTransparency = runCatching {
                Settings.Global.getInt(context.contentResolver, "accessibility_reduce_transparency", 0) == 1 ||
                    Settings.Secure.getInt(context.contentResolver, "reduce_transparency", 0) == 1
            }.getOrDefault(false)
            return AccessibilityFlags(
                reduceTransparency = reduceTransparency,
                increaseContrast = increaseContrast,
                reduceMotion = reduceMotion
            )
        }
    }

    object TabBackdropSamplerMath {
        const val MAX_SAMPLE_DIM_PX = 32
        const val DEFAULT_SAMPLE_DIM_PX = 16
        const val SMOOTHING_FACTOR = 0.24f

        data class BackdropSample(val argb: Int, val luminance: Float)

        fun clampSampleDimension(requestedPx: Int): Int = requestedPx.coerceIn(4, MAX_SAMPLE_DIM_PX)

        fun computeAverageSample(
            pixels: IntArray,
            count: Int = pixels.size,
            fallbackArgb: Int = LayerTokens.DARK_BASE_GROUND
        ): BackdropSample {
            val limit = minOf(count, pixels.size).coerceAtLeast(0)
            var sumR = 0L
            var sumG = 0L
            var sumB = 0L
            var totalWeight = 0L
            for (i in 0 until limit) {
                val px = pixels[i]
                val a = (px ushr 24) and 0xFF
                if (a < 16) continue
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                sumR += r * a
                sumG += g * a
                sumB += b * a
                totalWeight += a
            }
            if (totalWeight <= 0L) {
                val r = (fallbackArgb ushr 16) and 0xFF
                val g = (fallbackArgb ushr 8) and 0xFF
                val b = fallbackArgb and 0xFF
                val lum = ((0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f).coerceIn(0f, 1f)
                return BackdropSample((0xFF shl 24) or (fallbackArgb and 0x00FFFFFF), lum)
            }
            val avgR = (sumR / totalWeight).toInt().coerceIn(0, 255)
            val avgG = (sumG / totalWeight).toInt().coerceIn(0, 255)
            val avgB = (sumB / totalWeight).toInt().coerceIn(0, 255)
            val lum = ((0.2126f * avgR + 0.7152f * avgG + 0.0722f * avgB) / 255f).coerceIn(0f, 1f)
            return BackdropSample((0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB, lum)
        }

        fun interpolateSample(
            currentArgb: Int,
            currentLum: Float,
            target: BackdropSample,
            factor: Float = SMOOTHING_FACTOR
        ): BackdropSample {
            val t = factor.coerceIn(0.05f, 1f)
            val inv = 1f - t
            val cR = (currentArgb ushr 16) and 0xFF
            val cG = (currentArgb ushr 8) and 0xFF
            val cB = currentArgb and 0xFF
            val tR = (target.argb ushr 16) and 0xFF
            val tG = (target.argb ushr 8) and 0xFF
            val tB = target.argb and 0xFF
            val outR = (cR * inv + tR * t).roundToInt().coerceIn(0, 255)
            val outG = (cG * inv + tG * t).roundToInt().coerceIn(0, 255)
            val outB = (cB * inv + tB * t).roundToInt().coerceIn(0, 255)
            val outLum = (currentLum.coerceIn(0f, 1f) * inv + target.luminance.coerceIn(0f, 1f) * t).coerceIn(0f, 1f)
            return BackdropSample((0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB, outLum)
        }
    }

    object BezelOpticsMath {
        const val MIN_BEZEL_DP = 8f
        const val MAX_BEZEL_DP = 20f
        const val MAX_BEZEL_SIDE_FRACTION = 0.33f
        const val MAX_DISPLACEMENT_RATIO = 0.6f
        const val CENTER_WARP_ENABLED = false
        const val MIN_DISPERSION = 0.02f
        const val MAX_DISPERSION = 0.06f
        const val SMALL_POLARITY_FLIP_THRESHOLD = 0.58f
        const val SHADOW_ALPHA_PLAIN = 0.08f
        const val SHADOW_ALPHA_DENSE_TEXT = 0.22f

        fun computeRoundedRectSdf(
            px: Float,
            py: Float,
            halfW: Float,
            halfH: Float,
            radius: Float
        ): Float {
            val hw = halfW.coerceAtLeast(0f)
            val hh = halfH.coerceAtLeast(0f)
            val r = radius.coerceIn(0f, minOf(hw, hh))
            val qx = abs(px) - (hw - r)
            val qy = abs(py) - (hh - r)
            val outDist = hypot(maxOf(qx, 0f), maxOf(qy, 0f))
            val inDist = minOf(maxOf(qx, qy), 0f)
            return outDist + inDist - r
        }

        fun computeBezelWidthPx(
            widthPx: Float,
            heightPx: Float,
            radiusPx: Float,
            density: Float
        ): Float {
            val safeDensity = density.coerceAtLeast(0.5f)
            val minSide = minOf(widthPx, heightPx).coerceAtLeast(1f)
            val raw = 0.5f * radiusPx.coerceAtLeast(0f)
            val clampedDp = raw.coerceIn(MIN_BEZEL_DP * safeDensity, MAX_BEZEL_DP * safeDensity)
            return clampedDp.coerceAtMost(minSide * MAX_BEZEL_SIDE_FRACTION).coerceAtLeast(1f)
        }

        fun computeBezelDisplacement(
            insidePx: Float,
            bezelWidthPx: Float,
            materialization: Float = 1f
        ): Float {
            if (insidePx <= 0f || bezelWidthPx <= 0f || insidePx >= bezelWidthPx) return 0f
            val t = (1f - insidePx / bezelWidthPx).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val mat = materialization.coerceIn(0f, 1f)
            return smooth * (MAX_DISPLACEMENT_RATIO * bezelWidthPx) * mat
        }

        fun computeChromaticOffsets(
            baseShift: Float,
            dispersion: Float = 0.04f
        ): Triple<Float, Float, Float> {
            val d = dispersion.coerceIn(MIN_DISPERSION, MAX_DISPERSION)
            return Triple(baseShift * (1f + d), baseShift, baseShift * (1f - d))
        }

        fun resolvePolarityIsLight(
            surfaceScale: GlassSurfaceScale,
            backdropLuminance: Float,
            defaultLightMode: Boolean
        ): Boolean {
            if (surfaceScale == GlassSurfaceScale.LARGE) return defaultLightMode
            return backdropLuminance.coerceIn(0f, 1f) > SMALL_POLARITY_FLIP_THRESHOLD
        }

        fun computeRimLightAlphas(
            isLightPolarity: Boolean,
            materialization: Float = 1f
        ): Triple<Float, Float, Float> {
            val mat = materialization.coerceIn(0f, 1f)
            return if (isLightPolarity) {
                Triple(0.90f * mat, 0.16f * mat, 0.55f * mat)
            } else {
                Triple(0.32f * mat, 0.08f * mat, 0.22f * mat)
            }
        }

        fun computeAdaptiveShadowAlpha(contentDensity: Float): Float {
            val d = contentDensity.coerceIn(0f, 1f)
            return SHADOW_ALPHA_PLAIN + (SHADOW_ALPHA_DENSE_TEXT - SHADOW_ALPHA_PLAIN) * d
        }

        fun computeAmbientShadowColor(
            accentColor: Int,
            shadowAlpha: Float,
            bleedFraction: Float = 0.18f
        ): Int {
            val a = (shadowAlpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val bleed = bleedFraction.coerceIn(0f, 0.45f)
            val r = (((accentColor ushr 16) and 0xFF) * bleed).roundToInt().coerceIn(0, 255)
            val g = (((accentColor ushr 8) and 0xFF) * bleed).roundToInt().coerceIn(0, 255)
            val b = ((accentColor and 0xFF) * bleed).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    object OpticsMath {
        const val MAX_STRETCH_X = 1.12f

        fun computeVelocityStretchX(
            velocityPxPerSec: Float,
            referenceVelocityPxPerSec: Float = 1800f
        ): Float {
            val v = abs(velocityPxPerSec)
            if (v <= 1f || referenceVelocityPxPerSec <= 0f) return 1f
            val ratio = v / (v + referenceVelocityPxPerSec * 0.5f)
            return (1f + (MAX_STRETCH_X - 1f) * ratio).coerceIn(1f, MAX_STRETCH_X)
        }

        fun computeSpecularIntensity(
            touchActive: Boolean,
            stretchX: Float,
            dwellFlash: Float = 0f
        ): Float {
            val touchBoost = if (touchActive) 0.35f else 0f
            val stretchFrac = ((stretchX - 1f) / (MAX_STRETCH_X - 1f)).coerceIn(0f, 1f)
            val dwellBoost = dwellFlash.coerceIn(0f, 1f) * 0.65f
            return (touchBoost + stretchFrac * 0.35f + dwellBoost).coerceIn(0f, 1f)
        }
    }

    constructor(
        context: Context,
        cornerRadiusDp: Float = 22f,
        initialPalette: CoverPalette = CoverPaletteExtractor.defaultPalette(),
        rimWidthDp: Float = 1.2f
    ) : this(
        cornerRadiusPx = cornerRadiusDp * context.resources.displayMetrics.density,
        rimWidthPx = rimWidthDp * context.resources.displayMetrics.density,
        initialPalette = initialPalette
    ) {
        density = context.resources.displayMetrics.density
    }

    private var density: Float = (rimWidthPx / 1.2f).coerceAtLeast(1f)
    private var palette: CoverPalette = initialPalette
    private var baseAlphaFraction: Float = 0.56f
    private var accentAlphaFraction: Float = 0.14f
    private var useAccentGlowAsTint: Boolean = false
    private var drawableAlpha: Int = 255
    private var amoledProgress: Float = 0f
    private var specularIntensity: Float = 0f
    private var stretchX: Float = 1f
    private var stretchY: Float = 1f

    private var illuminationIntensity: Float = 0f
    private var illuminationX: Float = 0f
    private var illuminationY: Float = 0f
    private var neighborBleedIntensity: Float = 0f
    private var neighborBleedX: Float = 0f
    private var neighborBleedY: Float = 0f

    private var surfaceScale: GlassSurfaceScale = GlassSurfaceScale.SMALL
    private var glassVariant: GlassVariant = GlassVariant.REGULAR
    private var transparencyLevel: Float = 0.5f
    private var accessibilityFlags: AccessibilityFlags = AccessibilityFlags()
    private val defaultRimWidthPx: Float = rimWidthPx.coerceAtLeast(1f)

    private var backdropLuminance: Float = 0.18f
    private var sampledAmbientColor: Int? = null
    private var contentDensity: Float = 0.35f
    private var materializationProgress: Float = 1f
    private var defaultLightMode: Boolean = false

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bezelLensingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimWidthPx.coerceAtLeast(1f)
    }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (rimWidthPx * 1.15f).coerceAtLeast(1.2f)
        alpha = 0
    }
    private val illuminationShader = RadialGradient(
        0f,
        0f,
        1f,
        intArrayOf(0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP
    )
    private val neighborBleedShader = RadialGradient(
        0f,
        0f,
        1f,
        intArrayOf(0xFFFFFFFF.toInt(), 0x44FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.50f, 1f),
        Shader.TileMode.CLAMP
    )
    private val illuminationMatrix = Matrix()
    private val neighborBleedMatrix = Matrix()
    private val illuminationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = illuminationShader
        alpha = 0
    }
    private val neighborBleedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = neighborBleedShader
        alpha = 0
    }

    private val shadowRect = RectF()
    private val fillRect = RectF()
    private val rimRect = RectF()
    private val rimStops = floatArrayOf(0f, 0.5f, 1f)
    private val rimColors = IntArray(3)
    private val agslBezelHelper: AgslBezelHelper? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslBezelHelper()
    } else {
        null
    }

    init {
        applyPaletteColors()
    }

    fun setPalette(newPalette: CoverPalette) {
        if (palette == newPalette) return
        palette = newPalette
        applyPaletteColors()
        rebuildShaders(bounds)
        invalidateSelf()
    }

    fun setCornerRadius(radiusPx: Float) {
        if (cornerRadiusPx == radiusPx) return
        cornerRadiusPx = radiusPx
        invalidateSelf()
    }

    fun setTintProfile(
        baseFraction: Float,
        accentFraction: Float,
        preferAccentGlow: Boolean = false
    ) {
        baseAlphaFraction = baseFraction.coerceIn(0f, 1f)
        accentAlphaFraction = accentFraction.coerceIn(0f, 1f)
        useAccentGlowAsTint = preferAccentGlow
        applyPaletteColors()
        invalidateSelf()
    }

    fun setSurfaceScale(scale: GlassSurfaceScale) {
        if (surfaceScale == scale) return
        surfaceScale = scale
        applyPaletteColors()
        rebuildShaders(bounds)
        invalidateSelf()
    }

    fun setGlassVariant(variant: GlassVariant) {
        if (glassVariant == variant) return
        glassVariant = variant
        applyPaletteColors()
        rebuildShaders(bounds)
        invalidateSelf()
    }

    fun setContinuousTransparency(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        if (abs(transparencyLevel - clamped) < 0.005f) return
        transparencyLevel = clamped
        applyPaletteColors()
        rebuildShaders(bounds)
        invalidateSelf()
    }

    fun setAccessibilityFlags(flags: AccessibilityFlags) {
        if (accessibilityFlags == flags) return
        accessibilityFlags = flags
        applyPaletteColors()
        rebuildShaders(bounds)
        invalidateSelf()
    }

    fun getAccessibilityFlags(): AccessibilityFlags = accessibilityFlags

    fun getSurfaceScale(): GlassSurfaceScale = surfaceScale

    fun getGlassVariant(): GlassVariant = glassVariant

    fun setBackdropLuminance(luminance: Float) {
        val clamped = luminance.coerceIn(0f, 1f)
        if (abs(backdropLuminance - clamped) < 0.01f) return
        backdropLuminance = clamped
        applyPaletteColors()
        rebuildShaders(bounds)
        invalidateSelf()
    }

    fun setSampledBackdrop(ambientColor: Int, luminance: Float) {
        val clampedLum = luminance.coerceIn(0f, 1f)
        val opaqueColor = (0xFF shl 24) or (ambientColor and 0x00FFFFFF)
        if (sampledAmbientColor == opaqueColor && abs(backdropLuminance - clampedLum) < 0.008f) {
            return
        }
        val prevLight = BezelOpticsMath.resolvePolarityIsLight(surfaceScale, backdropLuminance, defaultLightMode)
        sampledAmbientColor = opaqueColor
        backdropLuminance = clampedLum
        val nextLight = BezelOpticsMath.resolvePolarityIsLight(surfaceScale, backdropLuminance, defaultLightMode)

        applyPaletteColors()
        if (prevLight != nextLight) {
            rebuildShaders(bounds)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !bounds.isEmpty &&
            LayerTokens.isRefractionAllowed(accessibilityFlags)
        ) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val maxR = minOf(w, h) * 0.5f
            val r = cornerRadiusPx.coerceAtMost(maxR)
            val bezelW = BezelOpticsMath.computeBezelWidthPx(w, h, r, density)
            bezelLensingPaint.shader = agslBezelHelper?.updateAndGetShader(
                width = w,
                height = h,
                cornerRadius = r,
                bezelWidth = bezelW,
                materialization = materializationProgress.coerceIn(0f, 1f),
                dispersion = 0.04f,
                accentColor = resolveEffectiveAgslTintColor(),
                lightPolarity = if (nextLight) 1f else 0f
            )
        }
        invalidateSelf()
    }

    fun setContentDensity(densityFraction: Float) {
        val clamped = densityFraction.coerceIn(0f, 1f)
        if (abs(contentDensity - clamped) < 0.01f) return
        contentDensity = clamped
        applyPaletteColors()
        invalidateSelf()
    }

    fun setMaterializationProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (abs(materializationProgress - clamped) < 0.005f) return
        materializationProgress = clamped
        applyPaletteColors()
        rebuildShaders(bounds)
        invalidateSelf()
    }

    fun getMaterializationProgress(): Float = materializationProgress

    fun setAmoledProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (abs(amoledProgress - clamped) < 0.001f) return
        amoledProgress = clamped
        applyPaletteColors()
        rebuildShaders(bounds)
        invalidateSelf()
    }

    fun setSpecularIntensity(intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        if (abs(specularIntensity - clamped) < 0.005f) return
        specularIntensity = clamped
        specularPaint.alpha = (drawableAlpha * specularIntensity).roundToInt().coerceIn(0, 255)
        invalidateSelf()
    }

    fun setStretchX(stretch: Float) {
        val clamped = stretch.coerceIn(1f, OpticsMath.MAX_STRETCH_X)
        if (abs(stretchX - clamped) < 0.002f && abs(stretchY - 1f) < 0.002f) return
        stretchX = clamped
        stretchY = 1f
        invalidateSelf()
    }

    fun setGelDeformation(scaleX: Float, scaleY: Float) {
        val sx = scaleX.coerceIn(0.80f, SpringAnimatorHelper.GelPhysicsMath.MAX_VELOCITY_STRETCH)
        val sy = scaleY.coerceIn(0.80f, SpringAnimatorHelper.GelPhysicsMath.MAX_VELOCITY_STRETCH)
        if (abs(stretchX - sx) < 0.002f && abs(stretchY - sy) < 0.002f) return
        stretchX = sx
        stretchY = sy
        invalidateSelf()
    }

    fun setFingertipIllumination(x: Float, y: Float, intensity: Float) {
        val nextI = if (accessibilityFlags.reduceMotion || accessibilityFlags.increaseContrast) {
            0f
        } else {
            intensity.coerceIn(0f, 1f)
        }
        if (abs(illuminationIntensity - nextI) < 0.005f &&
            abs(illuminationX - x) < 1f &&
            abs(illuminationY - y) < 1f
        ) return
        illuminationIntensity = nextI
        illuminationX = x
        illuminationY = y
        updateIlluminationMatrices()
        invalidateSelf()
    }

    fun setNeighborLightBleed(x: Float, y: Float, intensity: Float) {
        val nextI = if (accessibilityFlags.reduceMotion || accessibilityFlags.increaseContrast) {
            0f
        } else {
            intensity.coerceIn(0f, 1f)
        }
        if (abs(neighborBleedIntensity - nextI) < 0.005f &&
            abs(neighborBleedX - x) < 1f &&
            abs(neighborBleedY - y) < 1f
        ) return
        neighborBleedIntensity = nextI
        neighborBleedX = x
        neighborBleedY = y
        updateIlluminationMatrices()
        invalidateSelf()
    }

    private fun updateIlluminationMatrices() {
        val isLightPolarity = BezelOpticsMath.resolvePolarityIsLight(surfaceScale, backdropLuminance, defaultLightMode)
        val peakAlpha = if (isLightPolarity) 0.55f else 0.32f
        val sizeRef = maxOf(fillRect.width(), fillRect.height()).coerceAtLeast(48f)

        if (illuminationIntensity > 0.005f) {
            val radius = (sizeRef * 0.72f).coerceAtLeast(36f)
            illuminationMatrix.setScale(radius, radius)
            illuminationMatrix.postTranslate(illuminationX, illuminationY)
            illuminationShader.setLocalMatrix(illuminationMatrix)
            illuminationPaint.alpha = (drawableAlpha * peakAlpha * illuminationIntensity * materializationProgress)
                .roundToInt().coerceIn(0, 255)
        } else {
            illuminationPaint.alpha = 0
        }

        if (neighborBleedIntensity > 0.005f) {
            val radius = (sizeRef * SpringAnimatorHelper.GelPhysicsMath.NEIGHBOR_BLEED_RADIUS_RATIO).coerceAtLeast(48f)
            neighborBleedMatrix.setScale(radius, radius)
            neighborBleedMatrix.postTranslate(neighborBleedX, neighborBleedY)
            neighborBleedShader.setLocalMatrix(neighborBleedMatrix)
            neighborBleedPaint.alpha = (drawableAlpha * peakAlpha * 0.65f * neighborBleedIntensity * materializationProgress)
                .roundToInt().coerceIn(0, 255)
        } else {
            neighborBleedPaint.alpha = 0
        }
    }

    fun setMotionState(
        velocityPxPerSec: Float,
        touchActive: Boolean,
        dwellFlash: Float = 0f
    ) {
        val nextStretch = OpticsMath.computeVelocityStretchX(velocityPxPerSec)
        val nextSpecular = OpticsMath.computeSpecularIntensity(touchActive, nextStretch, dwellFlash)
        var changed = false
        if (abs(stretchX - nextStretch) >= 0.002f || abs(stretchY - 1f) >= 0.002f) {
            stretchX = nextStretch
            stretchY = 1f
            changed = true
        }
        if (abs(specularIntensity - nextSpecular) >= 0.005f) {
            specularIntensity = nextSpecular
            specularPaint.alpha = (drawableAlpha * specularIntensity).roundToInt().coerceIn(0, 255)
            changed = true
        }
        if (changed) invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        fillRect.set(bounds)
        val shadowOffsetY = 1.5f * density
        shadowRect.set(
            bounds.left.toFloat(),
            bounds.top + shadowOffsetY,
            bounds.right.toFloat(),
            bounds.bottom + shadowOffsetY
        )
        val halfStroke = rimPaint.strokeWidth * 0.5f
        rimRect.set(
            bounds.left + halfStroke,
            bounds.top + halfStroke,
            bounds.right - halfStroke,
            bounds.bottom - halfStroke
        )
        rebuildShaders(bounds)
    }

    override fun draw(canvas: Canvas) {
        if (fillRect.isEmpty || materializationProgress <= 0.002f) return
        val maxRadius = minOf(fillRect.width(), fillRect.height()) * 0.5f
        val radius = cornerRadiusPx.coerceAtMost(maxRadius)
        val rimRadius = (radius - rimPaint.strokeWidth * 0.5f).coerceAtLeast(0f)

        val stretched = abs(stretchX - 1f) > 0.001f || abs(stretchY - 1f) > 0.001f
        val saveCount = if (stretched) {
            canvas.save().also {
                canvas.scale(stretchX, stretchY, fillRect.centerX(), fillRect.centerY())
            }
        } else {
            0
        }

        // Layer 4 back: Adaptive Color-Bleeding Shadow
        if (!accessibilityFlags.increaseContrast && shadowPaint.alpha > 0) {
            canvas.drawRoundRect(shadowRect, radius, radius, shadowPaint)
        }

        // Layer 2: Diffusion & Adaptive Polarity Luminosity Fill
        canvas.drawRoundRect(fillRect, radius, radius, fillPaint)

        // Layer 1: Edge Rim Bezel Lensing (AGSL SDF + 4% chromatic dispersion on API 33+, bypassed when disabled)
        if (LayerTokens.isRefractionAllowed(accessibilityFlags) && bezelLensingPaint.shader != null) {
            canvas.drawRoundRect(fillRect, radius, radius, bezelLensingPaint)
        }

        // Layer 3: Specular Top Sheen & Internal Fingertip Illumination / Neighbor Bleed
        canvas.drawRoundRect(fillRect, radius, radius, sheenPaint)
        if (illuminationPaint.alpha > 0) {
            canvas.drawRoundRect(fillRect, radius, radius, illuminationPaint)
        }
        if (neighborBleedPaint.alpha > 0) {
            canvas.drawRoundRect(fillRect, radius, radius, neighborBleedPaint)
        }

        // Layer 4 front: 135-degree Directional Perimeter Rim Light
        canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint)
        if (specularIntensity > 0.005f) {
            canvas.drawRoundRect(rimRect, rimRadius, rimRadius, specularPaint)
        }

        if (stretched) {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun setAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(0, 255)
        if (drawableAlpha == clamped) return
        drawableAlpha = clamped
        applyPaletteColors()
        sheenPaint.alpha = clamped
        rimPaint.alpha = clamped
        specularPaint.alpha = (clamped * specularIntensity).roundToInt().coerceIn(0, 255)
        updateIlluminationMatrices()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        shadowPaint.colorFilter = colorFilter
        bezelLensingPaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        sheenPaint.colorFilter = colorFilter
        illuminationPaint.colorFilter = colorFilter
        neighborBleedPaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
        specularPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun applyPaletteColors() {
        val mat = materializationProgress.coerceIn(0f, 1f)
        val alphaScale = (drawableAlpha / 255f) * mat
        val shadowAlpha = BezelOpticsMath.computeAdaptiveShadowAlpha(contentDensity) * alphaScale * (1f - 0.5f * amoledProgress)
        shadowPaint.color = BezelOpticsMath.computeAmbientShadowColor(palette.accentGlow, shadowAlpha)

        if (accessibilityFlags.increaseContrast) {
            val baseFill = LayerTokens.HIGH_CONTRAST_DARK_FILL
            val a = (255f * alphaScale).roundToInt().coerceIn(0, 255)
            fillPaint.color = (a shl 24) or (baseFill and 0x00FFFFFF)
            rimPaint.strokeWidth = (defaultRimWidthPx * 1.5f).coerceAtLeast(2f)
            return
        }
        rimPaint.strokeWidth = defaultRimWidthPx

        if (accessibilityFlags.reduceTransparency) {
            val a = (255f * LayerTokens.REDUCED_TRANSPARENCY_ALPHA * alphaScale).roundToInt().coerceIn(0, 255)
            fillPaint.color = (a shl 24) or (LayerTokens.REDUCED_TRANSPARENCY_DARK_FILL and 0x00FFFFFF)
            return
        }

        val isLightPolarity = BezelOpticsMath.resolvePolarityIsLight(surfaceScale, backdropLuminance, defaultLightMode)
        val effectiveBase = if (glassVariant == GlassVariant.CLEAR) {
            0.10f
        } else {
            LayerTokens.resolveContinuousTransparencyAlpha(
                baseAlpha = baseAlphaFraction,
                transparencyLevel = transparencyLevel,
                accessibility = accessibilityFlags
            )
        }
        val effectiveAccent = if (glassVariant == GlassVariant.CLEAR) 0.06f else accentAlphaFraction

        val aB = (effectiveBase * alphaScale).coerceIn(0f, 1f)
        val aT = (effectiveAccent * alphaScale).coerceIn(0f, 1f)
        val outA = (aT + aB * (1f - aT)).coerceIn(0f, 1f)
        if (outA <= 0.001f) {
            fillPaint.color = 0
            return
        }

        val sampled = sampledAmbientColor
        val effectiveBackdrop = if (sampled != null) blendRgb(palette.deepBackdrop, sampled, 0.22f) else palette.deepBackdrop
        val baseDarken = 1f - 0.55f * amoledProgress
        val polarityLiftedBase = if (isLightPolarity) 244f else 0f
        val bR = if (isLightPolarity) {
            ((polarityLiftedBase * 0.65f + ((effectiveBackdrop ushr 16) and 0xFF) * 0.35f) * baseDarken).roundToInt()
        } else {
            (((effectiveBackdrop ushr 16) and 0xFF) * baseDarken).roundToInt()
        }
        val bG = if (isLightPolarity) {
            ((polarityLiftedBase * 0.65f + ((effectiveBackdrop ushr 8) and 0xFF) * 0.35f) * baseDarken).roundToInt()
        } else {
            (((effectiveBackdrop ushr 8) and 0xFF) * baseDarken).roundToInt()
        }
        val bB = if (isLightPolarity) {
            ((polarityLiftedBase * 0.65f + (effectiveBackdrop and 0xFF) * 0.35f) * baseDarken).roundToInt()
        } else {
            ((effectiveBackdrop and 0xFF) * baseDarken).roundToInt()
        }

        val paletteTint = if (useAccentGlowAsTint) palette.accentGlow else palette.glassTint
        val tintSource = if (sampled != null) blendRgb(paletteTint, sampled, 0.35f) else paletteTint
        val tR = (tintSource ushr 16) and 0xFF
        val tG = (tintSource ushr 8) and 0xFF
        val tB = tintSource and 0xFF

        val invT = 1f - aT
        val outR = ((tR * aT + bR * aB * invT) / outA).roundToInt().coerceIn(0, 255)
        val outG = ((tG * aT + bG * aB * invT) / outA).roundToInt().coerceIn(0, 255)
        val outB = ((tB * aT + bB * aB * invT) / outA).roundToInt().coerceIn(0, 255)
        val alphaByte = (outA * 255f).roundToInt().coerceIn(0, 255)

        fillPaint.color = (alphaByte shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    private fun rebuildShaders(bounds: Rect) {
        if (bounds.isEmpty) return
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val right = bounds.right.toFloat()
        val bottom = bounds.bottom.toFloat()
        val sheenBottom = top + bounds.height() * 0.45f
        val mat = materializationProgress.coerceIn(0f, 1f)

        val sheenTopAlpha = if (accessibilityFlags.increaseContrast || accessibilityFlags.reduceTransparency) {
            0
        } else {
            (0x22 * mat).roundToInt().coerceIn(0, 255)
        }
        val sheenTop = (sheenTopAlpha shl 24) or 0xFFFFFF
        sheenPaint.shader = LinearGradient(
            left,
            top,
            left,
            sheenBottom,
            sheenTop,
            SHEEN_BOTTOM_COLOR,
            Shader.TileMode.CLAMP
        )

        val isLightPolarity = BezelOpticsMath.resolvePolarityIsLight(surfaceScale, backdropLuminance, defaultLightMode)
        if (accessibilityFlags.increaseContrast) {
            rimPaint.shader = null
            rimPaint.color = LayerTokens.HIGH_CONTRAST_DARK_BORDER
        } else {
            val contrastFactor = SpringAnimatorHelper.AmoledTransitionMath.computeRimContrastFactor(amoledProgress)
            val (ul, mid, lr) = BezelOpticsMath.computeRimLightAlphas(isLightPolarity, mat)
            val ulByte = (ul * 255f * contrastFactor).roundToInt().coerceIn(0, 255)
            val midByte = (mid * 255f).roundToInt().coerceIn(0, 255)
            val lrByte = (lr * 255f * (1f + 0.35f * amoledProgress)).roundToInt().coerceIn(0, 255)
            rimColors[0] = (ulByte shl 24) or 0xFFFFFF
            rimColors[1] = (midByte shl 24) or 0xFFFFFF
            rimColors[2] = (lrByte shl 24) or 0xFFFFFF

            rimPaint.shader = LinearGradient(
                left,
                top,
                right,
                bottom,
                rimColors,
                rimStops,
                Shader.TileMode.CLAMP
            )
        }

        specularPaint.shader = LinearGradient(
            left,
            top,
            left + bounds.width() * 0.65f,
            top + bounds.height() * 0.55f,
            SPECULAR_TOP_LEFT_COLOR,
            SHEEN_BOTTOM_COLOR,
            Shader.TileMode.CLAMP
        )
        specularPaint.alpha = (drawableAlpha * specularIntensity * mat).roundToInt().coerceIn(0, 255)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && LayerTokens.isRefractionAllowed(accessibilityFlags)) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val maxR = minOf(w, h) * 0.5f
            val r = cornerRadiusPx.coerceAtMost(maxR)
            val bezelW = BezelOpticsMath.computeBezelWidthPx(w, h, r, density)
            bezelLensingPaint.shader = agslBezelHelper?.updateAndGetShader(
                width = w,
                height = h,
                cornerRadius = r,
                bezelWidth = bezelW,
                materialization = mat,
                dispersion = 0.04f,
                accentColor = resolveEffectiveAgslTintColor(),
                lightPolarity = if (isLightPolarity) 1f else 0f
            )
        } else {
            bezelLensingPaint.shader = null
        }
    }

    private fun resolveEffectiveAgslTintColor(): Int {
        val sampled = sampledAmbientColor ?: return palette.accentGlow
        return blendRgb(palette.accentGlow, sampled, 0.42f)
    }

    private fun blendRgb(c1: Int, c2: Int, ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        val inv = 1f - t
        val r = (((c1 ushr 16) and 0xFF) * inv + ((c2 ushr 16) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val g = (((c1 ushr 8) and 0xFF) * inv + ((c2 ushr 8) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        val b = ((c1 and 0xFF) * inv + (c2 and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class AgslBezelHelper {
        private val shader = RuntimeShader(AGSL_BEZEL_SHADER)

        fun updateAndGetShader(
            width: Float,
            height: Float,
            cornerRadius: Float,
            bezelWidth: Float,
            materialization: Float,
            dispersion: Float,
            accentColor: Int,
            lightPolarity: Float
        ): RuntimeShader {
            shader.setFloatUniform("uResolution", width.coerceAtLeast(1f), height.coerceAtLeast(1f))
            shader.setFloatUniform("uCornerRadius", cornerRadius.coerceAtLeast(0f))
            shader.setFloatUniform("uBezelWidth", bezelWidth.coerceAtLeast(1f))
            shader.setFloatUniform("uMaterialization", materialization.coerceIn(0f, 1f))
            shader.setFloatUniform("uDispersion", dispersion.coerceIn(0.02f, 0.06f))
            shader.setFloatUniform(
                "uTintColor",
                ((accentColor ushr 16) and 0xFF) / 255f,
                ((accentColor ushr 8) and 0xFF) / 255f,
                (accentColor and 0xFF) / 255f,
                1f
            )
            shader.setFloatUniform("uLightPolarity", lightPolarity)
            return shader
        }

        private companion object {
            const val AGSL_BEZEL_SHADER = """
                uniform float2 uResolution;
                uniform float uCornerRadius;
                uniform float uBezelWidth;
                uniform float uMaterialization;
                uniform float uDispersion;
                uniform float4 uTintColor;
                uniform float uLightPolarity;

                half4 main(float2 fragCoord) {
                    float2 halfSize = uResolution * 0.5;
                    float2 p = fragCoord - halfSize;
                    float r = min(uCornerRadius, min(halfSize.x, halfSize.y));
                    float2 q = abs(p) - (halfSize - r);
                    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
                    float inside = max(-d, 0.0);

                    // centerWarp = false: keep interior text crystal sharp
                    if (inside <= 0.0 || inside >= uBezelWidth || uMaterialization <= 0.001) {
                        return half4(0.0);
                    }

                    float t = clamp(1.0 - (inside / uBezelWidth), 0.0, 1.0);
                    float m = t * t * (3.0 - 2.0 * t);

                    // Surface normal approximation for 135-degree directional light
                    float2 n = normalize(p / max(halfSize, float2(1.0)));
                    float dir135 = clamp(dot(n, normalize(float2(-0.7071, -0.7071))) * 0.5 + 0.5, 0.0, 1.0);

                    // Subtle chromatic dispersion across R/G/B rim channels (2%..6%)
                    float rBand = clamp(m * (1.0 + uDispersion), 0.0, 1.0);
                    float gBand = m;
                    float bBand = clamp(m * (1.0 - uDispersion), 0.0, 1.0);

                    float baseIntensity = mix(0.24, 0.36, uLightPolarity) * uMaterialization;
                    float3 prism = float3(
                        mix(uTintColor.r, 1.0, 0.68) * rBand,
                        mix(uTintColor.g, 1.0, 0.72) * gBand,
                        mix(uTintColor.b, 1.0, 0.78) * bBand
                    );
                    float alpha = clamp(m * baseIntensity * (0.45 + 0.55 * dir135), 0.0, 0.42);
                    return half4(prism * alpha, alpha);
                }
            """
        }
    }

    private companion object {
        const val SHEEN_BOTTOM_COLOR = 0x00FFFFFF
        const val SPECULAR_TOP_LEFT_COLOR = 0xCCFFFFFF.toInt()
    }
}
