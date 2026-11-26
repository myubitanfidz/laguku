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

        canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        canvas.drawRoundRect(fillRect, radius, radius, sheenPaint)

        val rimRadius = (radius - rimPaint.strokeWidth * 0.5f).coerceAtLeast(0f)
        canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint)
    }

    override fun setAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(0, 255)
        if (drawableAlpha == clamped) return
        drawableAlpha = clamped
        applyPaletteColors()
        sheenPaint.alpha = clamped
        rimPaint.alpha = clamped
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        sheenPaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
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
    }

    private companion object {
        const val SHEEN_TOP_COLOR = 0x22FFFFFF
        const val SHEEN_BOTTOM_COLOR = 0x00FFFFFF
    }
}
