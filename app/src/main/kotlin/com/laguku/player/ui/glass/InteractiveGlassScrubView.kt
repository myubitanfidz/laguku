package com.laguku.player.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.laguku.player.model.CoverPalette
import com.laguku.player.ui.motion.SpringAnimatorHelper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Interactive Liquid Glass pill bar supporting press-hold magnification,
 * 1:1 horizontal finger scrubbing with rubber-band edge damping, dynamic
 * indicator width interpolation, and spring snap-to-slot release.
 *
 * Zero object allocations inside [dispatchDraw] and [onTouchEvent].
 */
class InteractiveGlassScrubView(
    context: Context,
    cornerRadiusDp: Float = 24f
) : FrameLayout(context) {

    object ScrubMath {

        fun computeRubberBand(
            position: Float,
            minBound: Float,
            maxBound: Float,
            maxOvershoot: Float
        ): Float {
            if (minBound >= maxBound) return minBound
            if (position in minBound..maxBound) return position
            val overshootLimit = maxOvershoot.coerceAtLeast(1f)
            val bound = if (position < minBound) minBound else maxBound
            val delta = position - bound
            val sign = if (delta < 0f) -1f else 1f
            val damped = (1f - 1f / (abs(delta) * 0.55f / overshootLimit + 1f)) * overshootLimit
            return bound + sign * damped
        }

        fun nearestSlotIndex(centerX: Float, slotCenters: FloatArray): Int {
            if (slotCenters.isEmpty()) return 0
            var bestIdx = 0
            var bestDist = abs(centerX - slotCenters[0])
            for (i in 1 until slotCenters.size) {
                val dist = abs(centerX - slotCenters[i])
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            return bestIdx
        }

        fun interpolateSlotWidth(
            centerX: Float,
            slotCenters: FloatArray,
            slotWidths: FloatArray
        ): Float {
            if (slotCenters.isEmpty() || slotWidths.isEmpty()) return 0f
            val count = minOf(slotCenters.size, slotWidths.size)
            if (count == 1 || centerX <= slotCenters[0]) return slotWidths[0]
            if (centerX >= slotCenters[count - 1]) return slotWidths[count - 1]
            for (i in 0 until count - 1) {
                val c0 = slotCenters[i]
                val c1 = slotCenters[i + 1]
                if (centerX in c0..c1) {
                    val span = (c1 - c0).coerceAtLeast(1f)
                    val t = ((centerX - c0) / span).coerceIn(0f, 1f)
                    return slotWidths[i] + (slotWidths[i + 1] - slotWidths[i]) * t
                }
            }
            return slotWidths[count - 1]
        }
    }

    var onSlotPreviewChanged: ((Int) -> Unit)? = null
    var onSlotSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxOvershootPx = 14f * density
    private val verticalPaddingPx = (5f * density).toInt()
    private val horizontalPaddingPx = (6f * density).toInt()

    private val containerGlass = LiquidGlassDrawable(context, cornerRadiusDp)
    private val indicatorGlass = LiquidGlassDrawable(context, (cornerRadiusDp - 6f).coerceAtLeast(14f))
    private val indicatorBounds = Rect()

    private val slotsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
    }

    private var slotCenters = FloatArray(0)
    private var slotWidths = FloatArray(0)
    private var selectedIndex: Int = 0
    private var previewIndex: Int = 0

    private var capsuleCenterX: Float = 0f
    private var capsuleWidth: Float = 0f
    private var capsuleScaleX: Float = 1f
    private var capsuleScaleY: Float = 1f
    private var indicatorInitialized: Boolean = false

    private var downX: Float = 0f
    private var isPressedScrub: Boolean = false
    private var hasDragged: Boolean = false

    init {
        background = containerGlass
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        containerGlass.setTintProfile(baseFraction = 0.64f, accentFraction = 0.16f)
        indicatorGlass.setTintProfile(baseFraction = 0.28f, accentFraction = 0.42f, preferAccentGlow = true)
        addView(slotsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setSlots(views: List<View>) {
        slotsRow.removeAllViews()
        slotCenters = FloatArray(views.size)
        slotWidths = FloatArray(views.size)
        for (v in views) {
            slotsRow.addView(v, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        selectedIndex = selectedIndex.coerceIn(0, (views.size - 1).coerceAtLeast(0))
        previewIndex = selectedIndex
        indicatorInitialized = false
        requestLayout()
    }

    fun setPalette(palette: CoverPalette) {
        containerGlass.setPalette(palette)
        indicatorGlass.setPalette(palette)
        invalidate()
    }

    fun getSelectedIndex(): Int = selectedIndex

    fun setSelectedIndex(index: Int, animate: Boolean = true) {
        if (slotCenters.isEmpty()) {
            selectedIndex = index.coerceAtLeast(0)
            previewIndex = selectedIndex
            return
        }
        val target = index.coerceIn(0, slotCenters.lastIndex)
        selectedIndex = target
        previewIndex = target
        moveCapsuleToSlot(target, animate)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val count = slotsRow.childCount
        if (count <= 0) return
        if (slotCenters.size != count) {
            slotCenters = FloatArray(count)
            slotWidths = FloatArray(count)
        }
        val rowLeft = slotsRow.left.toFloat()
        for (i in 0 until count) {
            val child = slotsRow.getChildAt(i)
            slotCenters[i] = rowLeft + (child.left + child.right) * 0.5f
            slotWidths[i] = (child.right - child.left).toFloat().coerceAtLeast(1f)
        }
        if (!indicatorInitialized && !isPressedScrub) {
            val idx = selectedIndex.coerceIn(0, count - 1)
            capsuleCenterX = slotCenters[idx]
            capsuleWidth = slotWidths[idx]
            indicatorInitialized = true
        } else if (!isPressedScrub && changed) {
            val idx = selectedIndex.coerceIn(0, count - 1)
            capsuleCenterX = slotCenters[idx]
            capsuleWidth = slotWidths[idx]
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (slotCenters.isNotEmpty() && capsuleWidth > 0f && height > 0) {
            val halfW = capsuleWidth * capsuleScaleX * 0.5f
            val baseHalfH = ((height - verticalPaddingPx * 2) * 0.5f).coerceAtLeast(4f)
            val halfH = baseHalfH * capsuleScaleY
            val cy = height * 0.5f
            indicatorBounds.set(
                (capsuleCenterX - halfW).roundToInt(),
                (cy - halfH).roundToInt(),
                (capsuleCenterX + halfW).roundToInt(),
                (cy + halfH).roundToInt()
            )
            indicatorGlass.bounds = indicatorBounds
            indicatorGlass.draw(canvas)
        }
        super.dispatchDraw(canvas)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return slotCenters.isNotEmpty()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (slotCenters.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                isPressedScrub = true
                hasDragged = false
                previewIndex = selectedIndex
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                indicatorGlass.setTintProfile(baseFraction = 0.24f, accentFraction = 0.52f, preferAccentGlow = true)
                animateCapsuleScale(PRESS_SCALE_X, PRESS_SCALE_Y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isPressedScrub) return false
                val dx = event.x - downX
                if (!hasDragged && abs(dx) > touchSlop * 0.4f) {
                    hasDragged = true
                }
                if (hasDragged) {
                    SpringAnimatorHelper.cancelSpring(this, CHANNEL_CENTER)
                    SpringAnimatorHelper.cancelSpring(this, CHANNEL_WIDTH)
                    val firstC = slotCenters[0]
                    val lastC = slotCenters[slotCenters.lastIndex]
                    val clampedCenter = ScrubMath.computeRubberBand(event.x, firstC, lastC, maxOvershootPx)
                    capsuleCenterX = clampedCenter
                    capsuleWidth = ScrubMath.interpolateSlotWidth(clampedCenter, slotCenters, slotWidths)

                    val hovered = ScrubMath.nearestSlotIndex(clampedCenter, slotCenters)
                    if (hovered != previewIndex) {
                        previewIndex = hovered
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSlotPreviewChanged?.invoke(hovered)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isPressedScrub) return false
                isPressedScrub = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val target = if (hasDragged) {
                    ScrubMath.nearestSlotIndex(capsuleCenterX, slotCenters)
                } else {
                    ScrubMath.nearestSlotIndex(event.x, slotCenters)
                }
                selectedIndex = target
                previewIndex = target
                indicatorGlass.setTintProfile(baseFraction = 0.28f, accentFraction = 0.42f, preferAccentGlow = true)
                animateCapsuleScale(1f, 1f)
                moveCapsuleToSlot(target, animate = true)
                onSlotSelected?.invoke(target)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        SpringAnimatorHelper.cancelSpring(this)
        super.onDetachedFromWindow()
    }

    private fun moveCapsuleToSlot(index: Int, animate: Boolean) {
        if (index !in slotCenters.indices) return
        val targetX = slotCenters[index]
        val targetW = slotWidths[index]
        if (!animate || !indicatorInitialized) {
            SpringAnimatorHelper.cancelSpring(this, CHANNEL_CENTER)
            SpringAnimatorHelper.cancelSpring(this, CHANNEL_WIDTH)
            capsuleCenterX = targetX
            capsuleWidth = targetW
            indicatorInitialized = true
            invalidate()
            return
        }
        SpringAnimatorHelper.animateSpring(
            key = this,
            start = capsuleCenterX,
            target = targetX,
            valueThreshold = 0.25f,
            channel = CHANNEL_CENTER,
            onUpdate = { x ->
                capsuleCenterX = x
                invalidate()
            }
        )
        SpringAnimatorHelper.animateSpring(
            key = this,
            start = capsuleWidth,
            target = targetW,
            valueThreshold = 0.25f,
            channel = CHANNEL_WIDTH,
            onUpdate = { w ->
                capsuleWidth = w
                invalidate()
            }
        )
    }

    private fun animateCapsuleScale(targetX: Float, targetY: Float) {
        SpringAnimatorHelper.animateSpring(
            key = this,
            start = capsuleScaleX,
            target = targetX,
            channel = CHANNEL_SCALE_X,
            onUpdate = { sx ->
                capsuleScaleX = sx
                invalidate()
            }
        )
        SpringAnimatorHelper.animateSpring(
            key = this,
            start = capsuleScaleY,
            target = targetY,
            channel = CHANNEL_SCALE_Y,
            onUpdate = { sy ->
                capsuleScaleY = sy
                invalidate()
            }
        )
    }

    private companion object {
        const val PRESS_SCALE_X = 1.06f
        const val PRESS_SCALE_Y = 1.12f
        const val CHANNEL_CENTER = 10
        const val CHANNEL_WIDTH = 11
        const val CHANNEL_SCALE_X = 12
        const val CHANNEL_SCALE_Y = 13
    }
}
