package com.laguku.player.ui.motion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import kotlin.math.abs
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

    val SPRING_FLUID_SCROLL = SpringProfile(stiffness = 145f, dampingRatio = 0.94f)
    val SPRING_LOUPE_GLIDE = SpringProfile(stiffness = 280f, dampingRatio = 0.84f)
    val SPRING_MORPH_SHEET = SpringProfile(stiffness = 320f, dampingRatio = 0.86f)

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
                velocity = 0f,
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
        springReveal(
            view = view,
            fromTranslationY = 24f,
            fromScale = 0.92f,
            stiffness = SPRING_MORPH_SHEET.stiffness,
            dampingRatio = SPRING_MORPH_SHEET.dampingRatio,
            onEnd = onEnd
        )
    }

    fun springMorphDismiss(
        view: View,
        anchorView: View?,
        rootView: View,
        onEnd: (() -> Unit)? = null
    ) {
        applyMorphPivot(view, anchorView, rootView)
        springDismiss(
            view = view,
            toTranslationY = 24f,
            toScale = 0.92f,
            stiffness = SPRING_MORPH_SHEET.stiffness,
            dampingRatio = SPRING_MORPH_SHEET.dampingRatio,
            onEnd = {
                view.pivotX = view.width * 0.5f
                view.pivotY = view.height * 0.5f
                onEnd?.invoke()
            }
        )
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

    /**
     * Attaches physical press feedback (scale to 0.96 with apple spring physics)
     * without intercepting clicks or allocating in the touch listener.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attachPressFeedback(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> springScale(v, SPRING_PRESS_SCALE)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springScale(v, 1f)
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
