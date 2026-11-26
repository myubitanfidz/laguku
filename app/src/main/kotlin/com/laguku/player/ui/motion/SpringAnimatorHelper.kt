package com.laguku.player.ui.motion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import com.laguku.player.ui.glass.LiquidGlassDrawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Motion and micro-interaction engine combining Laguku's liquid ease curve
 * with a Choreographer delta-time critically-damped spring solver ("apple" preset).
 */
object SpringAnimatorHelper {

    const val MOTION_PRESET = "apple"
    const val SPRING_STIFFNESS = 420f
    const val SPRING_DAMPING_RATIO = 0.80f
    const val SPRING_PRESS_SCALE = 0.96f

    data class SpringProfile(val stiffness: Float, val dampingRatio: Float)

    fun springFromResponse(responseMs: Float, dampingFraction: Float): SpringProfile {
        val respSec = (responseMs / 1000f).coerceAtLeast(0.05f)
        val omega = (2.0 * PI / respSec).toFloat()
        return SpringProfile(
            stiffness = omega * omega,
            dampingRatio = dampingFraction.coerceIn(0.2f, 1.5f)
        )
    }

    val SPRING_SNAPPY = springFromResponse(responseMs = 240f, dampingFraction = 0.82f)
    val SPRING_SMOOTH = springFromResponse(responseMs = 340f, dampingFraction = 0.88f)
    val SPRING_BOUNCY = springFromResponse(responseMs = 420f, dampingFraction = 0.68f)
    val SPRING_REDUCED_MOTION = SpringProfile(stiffness = 1600f, dampingRatio = 1.0f)

    val SPRING_FLUID_SCROLL = SpringProfile(stiffness = 145f, dampingRatio = 0.94f)
    val SPRING_LOUPE_GLIDE = SpringProfile(stiffness = 280f, dampingRatio = 0.84f)
    val SPRING_MORPH_SHEET = SpringProfile(stiffness = 320f, dampingRatio = 0.86f)

    object GelPhysicsMath {
        const val ACTIVE_LIFT_SCALE = 1.06f
        const val PRESS_COMPRESSION_SCALE = 0.94f
        const val VELOCITY_STRETCH_SENSITIVITY = 0.00035f
        const val MAX_VELOCITY_STRETCH = 1.24f
        const val NEIGHBOR_BLEED_RADIUS_RATIO = 1.4f

        fun computeConstantVolumeStretch(
            velocityPxPerSec: Float,
            reducedMotion: Boolean = false
        ): Float {
            if (reducedMotion) return 1f
            return (1f + abs(velocityPxPerSec) * VELOCITY_STRETCH_SENSITIVITY)
                .coerceIn(1f, MAX_VELOCITY_STRETCH)
        }

        fun computeConstantVolumeSquash(stretch: Float): Float {
            val s = stretch.coerceAtLeast(1f)
            return (1f / sqrt(s)).coerceIn(0.80f, 1f)
        }

        fun computeNeighborBleedIntensity(
            distancePx: Float,
            controlSizePx: Float,
            reducedMotion: Boolean = false
        ): Float {
            if (reducedMotion || controlSizePx <= 0f) return 0f
            val maxRadius = controlSizePx * NEIGHBOR_BLEED_RADIUS_RATIO
            if (distancePx >= maxRadius) return 0f
            val u = (distancePx.coerceAtLeast(0f) / maxRadius).coerceIn(0f, 1f)
            return (exp(-3f * u) * (1f - u)).coerceIn(0f, 1f)
        }
    }

    const val CURVE_X1 = 0.22f
    const val CURVE_Y1 = 1f
    const val CURVE_X2 = 0.36f
    const val CURVE_Y2 = 1f
    val LIQUID_EASE = PathInterpolator(CURVE_X1, CURVE_Y1, CURVE_X2, CURVE_Y2)

    const val PRESS_SCALE = 0.97f
    const val PRESS_IN_DURATION_MS = 140L
    const val PRESS_RELEASE_DURATION_MS = 220L
    const val CROSSFADE_DURATION_MS = 420L
    const val LYRIC_SCROLL_DURATION_MS = 340
    const val LYRIC_SCALE_DURATION_MS = 260L
    const val MODE_SWITCH_IN_DURATION_MS = 300L
    const val MODE_SWITCH_OUT_DURATION_MS = 240L
    const val SHEET_ENTER_DURATION_MS = 340L
    const val SHEET_EXIT_DURATION_MS = 280L

    object ScrollEdgeMath {
        fun computeDissolutionProgress(scrollOffsetPx: Int, maxTransitionPx: Float): Float {
            if (scrollOffsetPx <= 0 || maxTransitionPx <= 0f) return 0f
            return (scrollOffsetPx.toFloat() / maxTransitionPx).coerceIn(0f, 1f)
        }
    }

    object SwipeDismissMath {
        fun shouldDismiss(
            dragDyPx: Float,
            velocityYPxPerSec: Float,
            thresholdPx: Float
        ): Boolean {
            if (thresholdPx <= 0f) return false
            return dragDyPx >= thresholdPx || (dragDyPx > thresholdPx * 0.35f && velocityYPxPerSec >= 900f)
        }
    }

    object AmoledTransitionMath {
        const val AMOLED_STIFFNESS = 380f
        const val AMOLED_DAMPING_RATIO = 0.85f

        fun computeOverlayAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

        fun computeRimContrastFactor(progress: Float): Float =
            1f + 0.40f * progress.coerceIn(0f, 1f)
    }

    private const val CHANNEL_SCALE = 0
    private const val CHANNEL_REVEAL = 1
    private const val CHANNEL_BACKDROP_IN = 2
    private const val CHANNEL_BACKDROP_OUT = 3
    private const val CHANNEL_BG_COLOR = 4
    private const val CHANNEL_SWIPE_DISMISS = 5

    /**
     * Pure JVM-testable semi-implicit Euler spring solver.
     */
    class SpringSolver(
        var value: Float = 0f,
        var target: Float = 0f,
        var velocity: Float = 0f,
        var stiffness: Float = SPRING_STIFFNESS,
        var dampingRatio: Float = SPRING_DAMPING_RATIO,
        var valueThreshold: Float = 0.001f,
        var velocityThreshold: Float = 0.01f
    ) {
        fun isSettled(): Boolean {
            return abs(value - target) <= valueThreshold && abs(velocity) <= velocityThreshold
        }

        /**
         * Advances the spring by [dtSeconds] using fixed sub-steps for stability.
         * Returns true when the spring has reached equilibrium and snapped to [target].
         */
        fun step(dtSeconds: Float): Boolean {
            if (dtSeconds <= 0f) return isSettled()
            var remaining = dtSeconds.coerceAtMost(0.032f)
            val damping = 2f * dampingRatio * sqrt(stiffness)
            val maxSubStep = 0.008f
            while (remaining > 0f) {
                val h = if (remaining > maxSubStep) maxSubStep else remaining
                val force = stiffness * (target - value) - damping * velocity
                velocity += force * h
                value += velocity * h
                remaining -= h
            }
            if (isSettled()) {
                value = target
                velocity = 0f
                return true
            }
            return false
        }
    }

    private class SpringTask(
        val key: Any,
        val channel: Int,
        val solver: SpringSolver,
        var onUpdate: (Float) -> Unit,
        var onEnd: (() -> Unit)?
    )

    private val activeTasks = ArrayList<SpringTask>(16)
    private var choreographerRunning = false
    private var lastFrameNanos = 0L
    private var hostActive = true
    private val morphLocA = IntArray(2)
    private val morphLocB = IntArray(2)

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!hostActive || activeTasks.isEmpty()) {
                choreographerRunning = false
                lastFrameNanos = 0L
                return
            }
            val dtSec = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNanos).coerceIn(1_000_000L, 32_000_000L)) / 1_000_000_000f
            }
            lastFrameNanos = frameTimeNanos

            var i = 0
            while (i < activeTasks.size) {
                val task = activeTasks[i]
                val settled = task.solver.step(dtSec)
                task.onUpdate(task.solver.value)
                if (settled) {
                    activeTasks.removeAt(i)
                    task.onEnd?.invoke()
                } else {
                    i++
                }
            }

            if (activeTasks.isNotEmpty() && hostActive) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                choreographerRunning = false
                lastFrameNanos = 0L
            }
        }
    }

    fun isReducedMotionEnabled(context: Context): Boolean {
        return runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ) == 0f
        }.getOrDefault(false)
    }

    fun setHostActive(active: Boolean) {
        hostActive = active
        if (!active) {
            if (choreographerRunning) {
                runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
                choreographerRunning = false
                lastFrameNanos = 0L
            }
            while (activeTasks.isNotEmpty()) {
                val task = activeTasks.removeAt(activeTasks.lastIndex)
                task.solver.value = task.solver.target
                task.solver.velocity = 0f
                task.onUpdate(task.solver.target)
                task.onEnd?.invoke()
            }
        }
    }

    fun animateSpring(
        key: Any,
        start: Float,
        target: Float,
        stiffness: Float = SPRING_STIFFNESS,
        dampingRatio: Float = SPRING_DAMPING_RATIO,
        valueThreshold: Float = 0.001f,
        channel: Int = 0,
        initialVelocity: Float = 0f,
        onUpdate: (Float) -> Unit,
        onEnd: (() -> Unit)? = null
    ) {
        if (!hostActive) {
            cancelSpring(key, channel)
            onUpdate(target)
            onEnd?.invoke()
            return
        }

        val existing = findTask(key, channel)
        if (existing != null) {
            existing.solver.target = target
            existing.solver.stiffness = stiffness
            existing.solver.dampingRatio = dampingRatio
            if (initialVelocity != 0f) {
                existing.solver.velocity = initialVelocity
            }
            existing.solver.valueThreshold = valueThreshold
            existing.solver.velocityThreshold = (valueThreshold * 10f).coerceAtLeast(0.01f)
            existing.onUpdate = onUpdate
            existing.onEnd = onEnd
            if (existing.solver.isSettled()) {
                activeTasks.remove(existing)
                existing.solver.value = target
                existing.solver.velocity = 0f
                onUpdate(target)
                onEnd?.invoke()
                return
            }
        } else {
            val solver = SpringSolver(
                value = start,
                target = target,
                velocity = initialVelocity,
                stiffness = stiffness,
                dampingRatio = dampingRatio,
                valueThreshold = valueThreshold,
                velocityThreshold = (valueThreshold * 10f).coerceAtLeast(0.01f)
            )
            if (solver.isSettled()) {
                onUpdate(target)
                onEnd?.invoke()
                return
            }
            activeTasks.add(SpringTask(key, channel, solver, onUpdate, onEnd))
        }

        ensureChoreographerRunning()
    }

    fun cancelSpring(key: Any, channel: Int = -1) {
        var i = activeTasks.size - 1
        while (i >= 0) {
            val t = activeTasks[i]
            if (t.key === key && (channel < 0 || t.channel == channel)) {
                activeTasks.removeAt(i)
            }
            i--
        }
        if (activeTasks.isEmpty() && choreographerRunning) {
            runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
            choreographerRunning = false
            lastFrameNanos = 0L
        }
    }

    fun getSpringValue(key: Any, channel: Int): Float? {
        return findTask(key, channel)?.solver?.value
    }

    fun springScale(view: View, targetScale: Float) {
        animateSpring(
            key = view,
            start = view.scaleX,
            target = targetScale,
            channel = CHANNEL_SCALE,
            onUpdate = { s ->
                view.scaleX = s
                view.scaleY = s
            }
        )
    }

    fun springReveal(
        view: View,
        fromTranslationY: Float = 0f,
        fromScale: Float = 0.96f,
        fromTranslationX: Float = 0f,
        stiffness: Float = SPRING_STIFFNESS,
        dampingRatio: Float = SPRING_DAMPING_RATIO,
        onEnd: (() -> Unit)? = null
    ) {
        cancelSpring(view, CHANNEL_REVEAL)
        view.visibility = View.VISIBLE
        if (!hostActive) {
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            onEnd?.invoke()
            return
        }
        view.alpha = 0f
        view.translationX = fromTranslationX
        view.translationY = fromTranslationY
        view.scaleX = fromScale
        view.scaleY = fromScale
        animateSpring(
            key = view,
            start = 0f,
            target = 1f,
            stiffness = stiffness,
            dampingRatio = dampingRatio,
            channel = CHANNEL_REVEAL,
            onUpdate = { p ->
                val clampedAlpha = p.coerceIn(0f, 1f)
                val inv = 1f - p
                val scale = fromScale + (1f - fromScale) * p
                view.alpha = clampedAlpha
                view.translationX = fromTranslationX * inv
                view.translationY = fromTranslationY * inv
                view.scaleX = scale
                view.scaleY = scale
            },
            onEnd = onEnd
        )
    }

    fun springDismiss(
        view: View,
        toTranslationY: Float = 0f,
        toScale: Float = 0.96f,
        toTranslationX: Float = 0f,
        endVisibility: Int = View.GONE,
        stiffness: Float = SPRING_STIFFNESS,
        dampingRatio: Float = SPRING_DAMPING_RATIO,
        onEnd: (() -> Unit)? = null
    ) {
        cancelSpring(view, CHANNEL_REVEAL)
        if (view.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        if (!hostActive) {
            view.visibility = endVisibility
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            onEnd?.invoke()
            return
        }
        val startAlpha = view.alpha
        val startScale = view.scaleX
        val startTx = view.translationX
        val startTy = view.translationY
        animateSpring(
            key = view,
            start = 0f,
            target = 1f,
            stiffness = stiffness,
            dampingRatio = dampingRatio,
            channel = CHANNEL_REVEAL,
            onUpdate = { p ->
                val clamped = p.coerceIn(0f, 1f)
                view.alpha = startAlpha * (1f - clamped)
                view.translationX = startTx + (toTranslationX - startTx) * p
                view.translationY = startTy + (toTranslationY - startTy) * p
                val s = startScale + (toScale - startScale) * p
                view.scaleX = s
                view.scaleY = s
            },
            onEnd = {
                view.visibility = endVisibility
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                onEnd?.invoke()
            }
        )
    }

    fun springMorphReveal(
        view: View,
        anchorView: View?,
        rootView: View,
        onEnd: (() -> Unit)? = null
    ) {
        applyMorphPivot(view, anchorView, rootView)
        val reduced = isReducedMotionEnabled(view.context)
        val profile = if (reduced) SPRING_REDUCED_MOTION else SPRING_SMOOTH
        val silhouetteGlass = findSingleGlassSilhouette(view)
        cancelSpring(view, CHANNEL_REVEAL)
        view.visibility = View.VISIBLE
        if (!hostActive || reduced) {
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            silhouetteGlass?.setMaterializationProgress(1f)
            onEnd?.invoke()
            return
        }
        val fromScale = 0.88f
        val fromTy = 18f
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = fromTy
        view.scaleX = fromScale
        view.scaleY = fromScale
        silhouetteGlass?.setMaterializationProgress(0.05f)
        animateSpring(
            key = view,
            start = 0f,
            target = 1f,
            stiffness = profile.stiffness,
            dampingRatio = profile.dampingRatio,
            channel = CHANNEL_REVEAL,
            onUpdate = { p ->
                val mat = p.coerceIn(0.05f, 1f)
                val scale = fromScale + (1f - fromScale) * p
                view.translationY = fromTy * (1f - p)
                view.scaleX = scale
                view.scaleY = scale
                if (silhouetteGlass != null) {
                    silhouetteGlass.setMaterializationProgress(mat)
                } else {
                    view.alpha = p.coerceIn(0f, 1f)
                }
            },
            onEnd = {
                silhouetteGlass?.setMaterializationProgress(1f)
                view.alpha = 1f
                onEnd?.invoke()
            }
        )
    }

    fun springMorphDismiss(
        view: View,
        anchorView: View?,
        rootView: View,
        onEnd: (() -> Unit)? = null
    ) {
        applyMorphPivot(view, anchorView, rootView)
        val reduced = isReducedMotionEnabled(view.context)
        val profile = if (reduced) SPRING_REDUCED_MOTION else SPRING_SMOOTH
        val silhouetteGlass = findSingleGlassSilhouette(view)
        cancelSpring(view, CHANNEL_REVEAL)
        if (view.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        if (!hostActive || reduced) {
            view.visibility = View.GONE
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.pivotX = view.width * 0.5f
            view.pivotY = view.height * 0.5f
            silhouetteGlass?.setMaterializationProgress(1f)
            onEnd?.invoke()
            return
        }
        val startScale = view.scaleX
        val startTy = view.translationY
        val toScale = 0.88f
        val toTy = 18f
        animateSpring(
            key = view,
            start = 0f,
            target = 1f,
            stiffness = profile.stiffness,
            dampingRatio = profile.dampingRatio,
            channel = CHANNEL_REVEAL,
            onUpdate = { p ->
                val clamped = p.coerceIn(0f, 1f)
                view.translationY = startTy + (toTy - startTy) * p
                val s = startScale + (toScale - startScale) * p
                view.scaleX = s
                view.scaleY = s
                if (silhouetteGlass != null) {
                    silhouetteGlass.setMaterializationProgress((1f - clamped).coerceAtLeast(0.01f))
                } else {
                    view.alpha = 1f - clamped
                }
            },
            onEnd = {
                view.visibility = View.GONE
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                view.pivotX = view.width * 0.5f
                view.pivotY = view.height * 0.5f
                silhouetteGlass?.setMaterializationProgress(1f)
                onEnd?.invoke()
            }
        )
    }

    private fun findSingleGlassSilhouette(view: View): LiquidGlassDrawable? {
        (view.background as? LiquidGlassDrawable)?.let { return it }
        val group = view as? ViewGroup ?: return null
        for (i in 0 until group.childCount) {
            val childGlass = group.getChildAt(i).background as? LiquidGlassDrawable
            if (childGlass != null) return childGlass
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attachSwipeToDismiss(
        dragTarget: View,
        sheetCard: View,
        thresholdDp: Float = 84f,
        onDismiss: () -> Unit
    ) {
        val density = dragTarget.resources.displayMetrics.density
        val thresholdPx = thresholdDp * density
        var downRawY = 0f
        var lastRawY = 0f
        var lastTimeMs = 0L
        var velocityY = 0f
        dragTarget.setOnTouchListener { _, event ->
            val nowMs = android.os.SystemClock.elapsedRealtime()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelSpring(sheetCard, CHANNEL_SWIPE_DISMISS)
                    downRawY = event.rawY
                    lastRawY = event.rawY
                    lastTimeMs = nowMs
                    velocityY = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downRawY).coerceAtLeast(0f)
                    val dtSec = ((nowMs - lastTimeMs).coerceAtLeast(1L)) / 1000f
                    velocityY = (event.rawY - lastRawY) / dtSec
                    lastRawY = event.rawY
                    lastTimeMs = nowMs
                    sheetCard.translationY = dy
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = sheetCard.translationY
                    val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                    if (!isCancel && SwipeDismissMath.shouldDismiss(dy, velocityY, thresholdPx)) {
                        sheetCard.translationY = 0f
                        onDismiss()
                    } else {
                        val profile = if (isReducedMotionEnabled(sheetCard.context)) SPRING_REDUCED_MOTION else SPRING_BOUNCY
                        animateSpring(
                            key = sheetCard,
                            start = dy,
                            target = 0f,
                            stiffness = profile.stiffness,
                            dampingRatio = profile.dampingRatio,
                            valueThreshold = 0.5f,
                            channel = CHANNEL_SWIPE_DISMISS,
                            onUpdate = { ty -> sheetCard.translationY = ty }
                        )
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun applyMorphPivot(view: View, anchorView: View?, rootView: View) {
        val w = if (view.width > 0) view.width else rootView.width
        val h = if (view.height > 0) view.height else rootView.height
        if (w <= 0 || h <= 0) return
        if (anchorView != null && anchorView.width > 0 && anchorView.height > 0) {
            anchorView.getLocationInWindow(morphLocA)
            rootView.getLocationInWindow(morphLocB)
            val px = (morphLocA[0] - morphLocB[0] + anchorView.width * 0.5f).coerceIn(0f, w.toFloat())
            val py = (morphLocA[1] - morphLocB[1] + anchorView.height * 0.5f).coerceIn(0f, h.toFloat())
            view.pivotX = px
            view.pivotY = py
        } else {
            view.pivotX = w * 0.5f
            view.pivotY = h * 0.9f
        }
    }

    fun broadcastNeighborLightBleed(
        sourceView: View,
        localTouchX: Float,
        localTouchY: Float,
        active: Boolean
    ) {
        val parentGroup = sourceView.parent as? ViewGroup ?: return
        val reducedMotion = isReducedMotionEnabled(sourceView.context)
        if (!active || reducedMotion) {
            for (i in 0 until parentGroup.childCount) {
                val child = parentGroup.getChildAt(i)
                if (child !== sourceView) {
                    (child.background as? LiquidGlassDrawable)?.setNeighborLightBleed(0f, 0f, 0f)
                }
            }
            return
        }

        val parentTouchX = sourceView.left + localTouchX
        val parentTouchY = sourceView.top + localTouchY
        val controlSize = maxOf(sourceView.width, sourceView.height).toFloat().coerceAtLeast(1f)
        for (i in 0 until parentGroup.childCount) {
            val child = parentGroup.getChildAt(i)
            if (child === sourceView || child.visibility != View.VISIBLE) continue
            val siblingGlass = child.background as? LiquidGlassDrawable ?: continue
            val cx = child.left + child.width * 0.5f
            val cy = child.top + child.height * 0.5f
            val dist = hypot(parentTouchX - cx, parentTouchY - cy)
            val intensity = GelPhysicsMath.computeNeighborBleedIntensity(dist, controlSize, reducedMotion = false)
            siblingGlass.setNeighborLightBleed(
                parentTouchX - child.left,
                parentTouchY - child.top,
                intensity
            )
        }
    }

    /**
     * Attaches physical press feedback (scale to 0.96 with apple spring physics,
     * internal fingertip illumination, and neighbor light bleed)
     * without intercepting clicks or allocating in the touch listener.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            val reduced = isReducedMotionEnabled(v.context)
            val glass = v.background as? LiquidGlassDrawable
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!reduced) {
                        springScale(v, SPRING_PRESS_SCALE)
                    }
                    glass?.setFingertipIllumination(event.x, event.y, 1f)
                    broadcastNeighborLightBleed(v, event.x, event.y, active = true)
                }
                MotionEvent.ACTION_MOVE -> {
                    glass?.setFingertipIllumination(event.x, event.y, 1f)
                    broadcastNeighborLightBleed(v, event.x, event.y, active = true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    springScale(v, 1f)
                    glass?.setFingertipIllumination(event.x, event.y, 0f)
                    broadcastNeighborLightBleed(v, event.x, event.y, active = false)
                }
            }
            false
        }
    }

    fun crossfadeBackdrop(
        incomingView: ImageView,
        outgoingView: ImageView,
        newBitmap: Bitmap?,
        onComplete: (() -> Unit)? = null
    ) {
        cancelSpring(incomingView, CHANNEL_BACKDROP_IN)
        cancelSpring(outgoingView, CHANNEL_BACKDROP_OUT)

        incomingView.setImageBitmap(newBitmap)
        val targetAlpha = if (newBitmap != null) 1f else 0f
        if (!hostActive) {
            incomingView.alpha = targetAlpha
            outgoingView.alpha = 0f
            outgoingView.setImageBitmap(null)
            onComplete?.invoke()
            return
        }

        incomingView.alpha = 0f
        animateSpring(
            key = incomingView,
            start = 0f,
            target = targetAlpha,
            stiffness = 280f,
            dampingRatio = 0.88f,
            channel = CHANNEL_BACKDROP_IN,
            onUpdate = { a -> incomingView.alpha = a.coerceIn(0f, 1f) }
        )

        animateSpring(
            key = outgoingView,
            start = outgoingView.alpha,
            target = 0f,
            stiffness = 280f,
            dampingRatio = 0.88f,
            channel = CHANNEL_BACKDROP_OUT,
            onUpdate = { a -> outgoingView.alpha = a.coerceIn(0f, 1f) },
            onEnd = {
                outgoingView.setImageBitmap(null)
                onComplete?.invoke()
            }
        )
    }

    fun animateBackgroundColor(view: View, fromColor: Int, toColor: Int) {
        cancelSpring(view, CHANNEL_BG_COLOR)
        val startColor = (view.background as? ColorDrawable)?.color ?: fromColor
        if (!hostActive || startColor == toColor) {
            view.setBackgroundColor(toColor)
            return
        }

        val sA = (startColor ushr 24) and 0xFF
        val sR = (startColor ushr 16) and 0xFF
        val sG = (startColor ushr 8) and 0xFF
        val sB = startColor and 0xFF

        val tA = (toColor ushr 24) and 0xFF
        val tR = (toColor ushr 16) and 0xFF
        val tG = (toColor ushr 8) and 0xFF
        val tB = toColor and 0xFF

        animateSpring(
            key = view,
            start = 0f,
            target = 1f,
            stiffness = 280f,
            dampingRatio = 0.88f,
            channel = CHANNEL_BG_COLOR,
            onUpdate = { p ->
                val f = p.coerceIn(0f, 1f)
                val a = (sA + (tA - sA) * f).roundToInt().coerceIn(0, 255)
                val r = (sR + (tR - sR) * f).roundToInt().coerceIn(0, 255)
                val g = (sG + (tG - sG) * f).roundToInt().coerceIn(0, 255)
                val b = (sB + (tB - sB) * f).roundToInt().coerceIn(0, 255)
                view.setBackgroundColor((a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        )
    }

    private fun findTask(key: Any, channel: Int): SpringTask? {
        for (i in 0 until activeTasks.size) {
            val t = activeTasks[i]
            if (t.key === key && t.channel == channel) return t
        }
        return null
    }

    private fun ensureChoreographerRunning() {
        if (choreographerRunning || !hostActive) return
        choreographerRunning = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
}
