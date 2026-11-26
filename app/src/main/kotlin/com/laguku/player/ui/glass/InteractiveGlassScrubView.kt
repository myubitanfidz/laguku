package com.laguku.player.ui.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    class ScrubDwellController(val dwellDelayMs: Long = DWELL_DELAY_MS) {
        companion object {
            const val DWELL_DELAY_MS = 240L
        }

        var committedIndex: Int = 0
            private set
        var hoveredIndex: Int = 0
            private set
        var dwellCommitted: Boolean = false
            private set
        private var hoveredAtMs: Long = 0L

        fun onTouchDown(initialSelectedIndex: Int) {
            committedIndex = initialSelectedIndex
            hoveredIndex = initialSelectedIndex
            dwellCommitted = false
            hoveredAtMs = 0L
        }

        fun onHoverSlot(index: Int, nowMs: Long): Boolean {
            if (index == hoveredIndex) return false
            hoveredIndex = index
            hoveredAtMs = nowMs
            dwellCommitted = false
            return true
        }

        fun onDwellTimeout(nowMs: Long): Int? {
            if (dwellCommitted || hoveredIndex == committedIndex) return null
            if (nowMs - hoveredAtMs < dwellDelayMs) return null
            committedIndex = hoveredIndex
            dwellCommitted = true
            return hoveredIndex
        }

        fun onTouchUp(targetIndex: Int, isCancel: Boolean): Int? {
            val result = if (isCancel || (dwellCommitted && targetIndex == committedIndex)) {
                null
            } else {
                committedIndex = targetIndex
                targetIndex
            }
            dwellCommitted = false
            hoveredAtMs = 0L
            return result
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dwellController = ScrubDwellController()

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
    private var lastMoveX: Float = 0f
    private var lastMoveTimeMs: Long = 0L
    private var isPressedScrub: Boolean = false
    private var hasDragged: Boolean = false
    private var neutralLensMode: Boolean = false

    private val dwellRunnable = Runnable {
        val committed = dwellController.onDwellTimeout(SystemClock.elapsedRealtime()) ?: return@Runnable
        if (committed in slotCenters.indices) {
            selectedIndex = committed
            previewIndex = committed
            indicatorGlass.setSpecularIntensity(1f)
            val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.CLOCK_TICK
            }
            performHapticFeedback(haptic)
            onSlotSelected?.invoke(committed)
            invalidate()
        }
    }

    init {
        background = containerGlass
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        containerGlass.setTintProfile(baseFraction = 0.64f, accentFraction = 0.06f, preferAccentGlow = false)
        indicatorGlass.setTintProfile(baseFraction = 0.28f, accentFraction = 0.42f, preferAccentGlow = true)
        addView(slotsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setNeutralLensMode(neutral: Boolean) {
        neutralLensMode = neutral
        if (neutral) {
            containerGlass.setTintProfile(baseFraction = 0.64f, accentFraction = 0.04f, preferAccentGlow = false)
            indicatorGlass.setTintProfile(baseFraction = 0.22f, accentFraction = 0.10f, preferAccentGlow = false)
        } else {
            containerGlass.setTintProfile(baseFraction = 0.64f, accentFraction = 0.06f, preferAccentGlow = false)
            indicatorGlass.setTintProfile(baseFraction = 0.28f, accentFraction = 0.42f, preferAccentGlow = true)
        }
        invalidate()
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
        val nowMs = SystemClock.elapsedRealtime()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                lastMoveX = event.x
                lastMoveTimeMs = nowMs
                isPressedScrub = true
                hasDragged = false
                previewIndex = selectedIndex
                dwellController.onTouchDown(selectedIndex)
                mainHandler.removeCallbacks(dwellRunnable)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                indicatorGlass.setTintProfile(
                    baseFraction = 0.24f,
                    accentFraction = if (neutralLensMode) 0.12f else 0.52f,
                    preferAccentGlow = !neutralLensMode
                )
                indicatorGlass.setMotionState(0f, touchActive = true)
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
                    val dtSec = ((nowMs - lastMoveTimeMs).coerceAtLeast(1L)) / 1000f
                    val vx = (event.x - lastMoveX) / dtSec
                    lastMoveX = event.x
                    lastMoveTimeMs = nowMs

                    val firstC = slotCenters[0]
                    val lastC = slotCenters[slotCenters.lastIndex]
                    val clampedCenter = ScrubMath.computeRubberBand(event.x, firstC, lastC, maxOvershootPx)
                    capsuleCenterX = clampedCenter
                    capsuleWidth = ScrubMath.interpolateSlotWidth(clampedCenter, slotCenters, slotWidths)
                    indicatorGlass.setMotionState(vx, touchActive = true)

                    val hovered = ScrubMath.nearestSlotIndex(clampedCenter, slotCenters)
                    if (dwellController.onHoverSlot(hovered, nowMs)) {
                        previewIndex = hovered
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSlotPreviewChanged?.invoke(hovered)
                        mainHandler.removeCallbacks(dwellRunnable)
                        if (hovered != selectedIndex) {
                            mainHandler.postDelayed(dwellRunnable, ScrubDwellController.DWELL_DELAY_MS)
                        }
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isPressedScrub) return false
                isPressedScrub = false
                mainHandler.removeCallbacks(dwellRunnable)
                parent?.requestDisallowInterceptTouchEvent(false)
                val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                val target = if (isCancel) {
                    selectedIndex
                } else if (hasDragged) {
                    ScrubMath.nearestSlotIndex(capsuleCenterX, slotCenters)
                } else {
                    ScrubMath.nearestSlotIndex(event.x, slotCenters)
                }
                selectedIndex = target
                previewIndex = target
                indicatorGlass.setTintProfile(
                    baseFraction = if (neutralLensMode) 0.22f else 0.28f,
                    accentFraction = if (neutralLensMode) 0.10f else 0.42f,
                    preferAccentGlow = !neutralLensMode
                )
                indicatorGlass.setMotionState(0f, touchActive = false)
                animateCapsuleScale(1f, 1f)
                moveCapsuleToSlot(target, animate = true)
                val commitIdx = dwellController.onTouchUp(target, isCancel)
                if (commitIdx != null) {
                    onSlotSelected?.invoke(commitIdx)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun cancelActiveInteractions() {
        mainHandler.removeCallbacks(dwellRunnable)
        isPressedScrub = false
        hasDragged = false
        SpringAnimatorHelper.cancelSpring(this)
        indicatorGlass.setMotionState(0f, touchActive = false)
        capsuleScaleX = 1f
        capsuleScaleY = 1f
        if (selectedIndex in slotCenters.indices) {
            capsuleCenterX = slotCenters[selectedIndex]
            capsuleWidth = slotWidths[selectedIndex]
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelActiveInteractions()
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
            stiffness = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.stiffness,
            dampingRatio = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.dampingRatio,
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
            stiffness = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.stiffness,
            dampingRatio = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.dampingRatio,
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
            stiffness = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.stiffness,
            dampingRatio = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.dampingRatio,
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
            stiffness = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.stiffness,
            dampingRatio = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.dampingRatio,
            channel = CHANNEL_SCALE_Y,
            onUpdate = { sy ->
                capsuleScaleY = sy
                invalidate()
            }
        )
    }

    private companion object {
        const val PRESS_SCALE_X = 1.06f
        const val PRESS_SCALE_Y = 1.06f
        const val CHANNEL_CENTER = 10
        const val CHANNEL_WIDTH = 11
        const val CHANNEL_SCALE_X = 12
        const val CHANNEL_SCALE_Y = 13
    }
}
