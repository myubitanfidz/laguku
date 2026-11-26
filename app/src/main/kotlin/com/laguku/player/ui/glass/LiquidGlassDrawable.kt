package com.laguku.player.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.laguku.player.model.CoverPalette
import com.laguku.player.ui.motion.SpringAnimatorHelper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Liquid Glass surface drawable:
 * - Layered translucent fill (56% dark base + 14% accent/glass tint)
 * - Subtle top-edge sheen
 * - 1.2dp diagonal Specular Rim Highlight (34% white top-left to 5% white bottom-right)
 *
 * Zero object allocations inside [draw].
 */
class LiquidGlassDrawable(
    private var cornerRadiusPx: Float,
    rimWidthPx: Float,
    initialPalette: CoverPalette = CoverPaletteExtractor.defaultPalette()
) : Drawable() {

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
        initialPalette: CoverPalette = CoverPaletteExtractor.defaultPalette()
    ) : this(
        cornerRadiusPx = cornerRadiusDp * context.resources.displayMetrics.density,
        rimWidthPx = 1.2f * context.resources.displayMetrics.density,
        initialPalette = initialPalette
    )

    private var palette: CoverPalette = initialPalette
    private var baseAlphaFraction: Float = 0.56f
    private var accentAlphaFraction: Float = 0.14f
    private var useAccentGlowAsTint: Boolean = false
    private var drawableAlpha: Int = 255
    private var amoledProgress: Float = 0f
    private var specularIntensity: Float = 0f
    private var stretchX: Float = 1f

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

    private val fillRect = RectF()
    private val rimRect = RectF()

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
        if (abs(stretchX - clamped) < 0.002f) return
        stretchX = clamped
        invalidateSelf()
    }

    fun setMotionState(
        velocityPxPerSec: Float,
        touchActive: Boolean,
        dwellFlash: Float = 0f
    ) {
        val nextStretch = OpticsMath.computeVelocityStretchX(velocityPxPerSec)
        val nextSpecular = OpticsMath.computeSpecularIntensity(touchActive, nextStretch, dwellFlash)
        var changed = false
        if (abs(stretchX - nextStretch) >= 0.002f) {
            stretchX = nextStretch
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
        if (fillRect.isEmpty) return
        val maxRadius = minOf(fillRect.width(), fillRect.height()) * 0.5f
        val radius = cornerRadiusPx.coerceAtMost(maxRadius)
        val rimRadius = (radius - rimPaint.strokeWidth * 0.5f).coerceAtLeast(0f)

        val stretched = stretchX > 1.001f
        val saveCount = if (stretched) {
            canvas.save().also {
                canvas.scale(stretchX, 1f, fillRect.centerX(), fillRect.centerY())
            }
        } else {
            0
        }

        canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        canvas.drawRoundRect(fillRect, radius, radius, sheenPaint)
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
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        sheenPaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
        specularPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun applyPaletteColors() {
        val alphaScale = drawableAlpha / 255f
        val aB = (baseAlphaFraction * alphaScale).coerceIn(0f, 1f)
        val aT = (accentAlphaFraction * alphaScale).coerceIn(0f, 1f)
        val outA = (aT + aB * (1f - aT)).coerceIn(0f, 1f)
        if (outA <= 0.001f) {
            fillPaint.color = 0
            return
        }

        val baseDarken = 1f - 0.55f * amoledProgress
        val bR = (((palette.deepBackdrop ushr 16) and 0xFF) * baseDarken).roundToInt()
        val bG = (((palette.deepBackdrop ushr 8) and 0xFF) * baseDarken).roundToInt()
        val bB = ((palette.deepBackdrop and 0xFF) * baseDarken).roundToInt()

        val tintSource = if (useAccentGlowAsTint) palette.accentGlow else palette.glassTint
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

        sheenPaint.shader = LinearGradient(
            left,
            top,
            left,
            sheenBottom,
            SHEEN_TOP_COLOR,
            SHEEN_BOTTOM_COLOR,
            Shader.TileMode.CLAMP
        )

        val contrastFactor = SpringAnimatorHelper.AmoledTransitionMath.computeRimContrastFactor(amoledProgress)
        val topLeftAlpha = (0x57 * contrastFactor).roundToInt().coerceIn(0, 255)
        val bottomRightAlpha = (0x0D * (1f + amoledProgress)).roundToInt().coerceIn(0, 255)
        val rimTopLeft = (topLeftAlpha shl 24) or 0xFFFFFF
        val rimBottomRight = (bottomRightAlpha shl 24) or 0xFFFFFF

        rimPaint.shader = LinearGradient(
            left,
            top,
            right,
            bottom,
            rimTopLeft,
            rimBottomRight,
            Shader.TileMode.CLAMP
        )

        specularPaint.shader = LinearGradient(
            left,
            top,
            left + bounds.width() * 0.65f,
            top + bounds.height() * 0.55f,
            SPECULAR_TOP_LEFT_COLOR,
            SHEEN_BOTTOM_COLOR,
            Shader.TileMode.CLAMP
        )
        specularPaint.alpha = (drawableAlpha * specularIntensity).roundToInt().coerceIn(0, 255)
    }

    private companion object {
        const val SHEEN_TOP_COLOR = 0x22FFFFFF
        const val SHEEN_BOTTOM_COLOR = 0x00FFFFFF
        const val SPECULAR_TOP_LEFT_COLOR = 0xCCFFFFFF.toInt()
    }
}
