package com.laguku.player.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.laguku.player.model.CoverPalette
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.motion.SpringAnimatorHelper

/**
 * Authentic spinning vinyl turntable (1 rev / 15s = 24 deg/s) using Choreographer
 * delta-time rotation, inertial spin-up/coast-down, concentric vinyl grooves,
 * stationary diagonal specular light sheen, 1:1 center label, metallic spindle,
 * and zero-allocation PCM ripple rings.
 */
class VinylDiscVisualizerView(context: Context) : View(context) {

    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onSeekDeltaMs: ((Long) -> Unit)? = null
    var onCollapse: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var palette: CoverPalette = CoverPaletteExtractor.defaultPalette()

    private var discBitmap: Bitmap? = null
    private var discShader: BitmapShader? = null
    private val shaderMatrix = Matrix()

    private var defaultCoverBitmap: Bitmap? = null
    private var defaultCoverShader: BitmapShader? = null
    private val defaultArtPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val defaultShaderMatrix = Matrix()

    private var isPlaying: Boolean = false
    private var isHostActive: Boolean = true
    private var discAngleDeg: Float = 0f
    private var currentSpeedDegPerSec: Float = 0f
    private var targetSpeedDegPerSec: Float = 0f
    private var lastFrameNs: Long = 0L
    private var choreographerRunning: Boolean = false

    private var targetVisualizerLevel: Float = 0f
    private var smoothedVisualizerLevel: Float = 0f

    // Pre-allocated Paints and geometry caches (zero allocations in onDraw)
    private val vinylBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0E0E11.toInt()
    }
    private val vinylGroovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
    }
    private val vinylRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        color = 0x3DFFFFFF
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = 0x52FFFFFF
    }
    private val centerHolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF090B10.toInt()
    }
    private val spindleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = 0x88D8DEE9.toInt()
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val ringPhases = floatArrayOf(0f, 0.33f, 0.66f)
    private val grooveRadii = FloatArray(GROOVE_COUNT)

    private var cachedDiscRadius = 0f
    private var cachedLabelRadius = 0f
    private var cachedSpindleRadius = 0f
    private var cachedMaxAvail = 0f

    private val swipeController = NowPlayingView.HorizontalSwipeCommitController()
    private val swipeThresholdPx = NowPlayingView.HorizontalSwipeCommitController.SWIPE_THRESHOLD_DP * density
    private val maxSwipeOffsetPx = NowPlayingView.HorizontalSwipeCommitController.MAX_SWIPE_OFFSET_DP * density

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!shouldKeepAnimating()) {
                stopLoop()
                return
            }
            val dtSec = if (lastFrameNs == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNs).coerceIn(1_000_000L, 32_000_000L)) / 1_000_000_000f
            }
            lastFrameNs = frameTimeNanos

            val inertiaSeconds = if (isPlaying) INERTIA_UP_SECONDS else INERTIA_DOWN_SECONDS
            val inertiaRate = (dtSec / inertiaSeconds).coerceIn(0f, 1f)
            currentSpeedDegPerSec += (targetSpeedDegPerSec - currentSpeedDegPerSec) * inertiaRate
            discAngleDeg = (discAngleDeg + currentSpeedDegPerSec * dtSec) % 360f

            smoothedVisualizerLevel += (targetVisualizerLevel - smoothedVisualizerLevel) * 0.25f
            if (smoothedVisualizerLevel > 0.01f || isPlaying) {
                for (i in ringPhases.indices) {
                    ringPhases[i] = (ringPhases[i] + dtSec * 0.55f) % 1f
                }
            }

            if (!isPlaying && currentSpeedDegPerSec <= 0.15f && smoothedVisualizerLevel <= 0.01f) {
                currentSpeedDegPerSec = 0f
                smoothedVisualizerLevel = 0f
                invalidate()
                stopLoop()
                return
            }

            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (width <= 0) return false
            val delta = if (e.x < width * 0.5f) -10_000L else 10_000L
            onSeekDeltaMs?.invoke(delta)
            return true
        }
    })

    init {
        isClickable = true
    }

    fun setPalette(newPalette: CoverPalette) {
        palette = newPalette
        invalidate()
    }

    fun animateTrackTransition(direction: Int) {
        if (direction == 0 || SpringAnimatorHelper.isReducedMotionEnabled(context)) return
        val dir = direction.coerceIn(-1, 1)
        SpringAnimatorHelper.springReveal(
            this,
            fromTranslationX = dir * 36f * density,
            fromScale = 0.94f
        )
    }

    fun setCoverArtwork(bitmap: Bitmap?) {
        discBitmap = bitmap?.takeIf { !it.isRecycled }
        discShader = discBitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        artPaint.shader = discShader
        updateShaderMatrix()
        invalidate()
    }

    fun setPlaying(playing: Boolean) {
        if (this.isPlaying == playing) return
        this.isPlaying = playing
        targetSpeedDegPerSec = if (playing) TARGET_DEG_PER_SEC else 0f
        updateLoopState()
    }

    fun setHostActive(active: Boolean) {
        if (isHostActive == active) return
        isHostActive = active
        updateLoopState()
    }

    fun setVisualizerLevel(level: Float) {
        targetVisualizerLevel = level.coerceIn(0f, 1f)
        if (!isPlaying || !isHostActive || visibility != VISIBLE) {
            smoothedVisualizerLevel = 0f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cachedMaxAvail = minOf(w, h) * 0.5f
        cachedDiscRadius = cachedMaxAvail * 0.80f
        cachedLabelRadius = cachedDiscRadius * 0.46f
        cachedSpindleRadius = cachedDiscRadius * 0.075f

        val grooveStart = cachedLabelRadius * 1.09f
        val grooveEnd = cachedDiscRadius * 0.96f
        val span = (grooveEnd - grooveStart).coerceAtLeast(1f)
        for (i in 0 until GROOVE_COUNT) {
            val frac = i.toFloat() / (GROOVE_COUNT - 1).coerceAtLeast(1)
            grooveRadii[i] = grooveStart + span * frac
        }

        sheenPaint.shader = SweepGradient(0f, 0f, SHEEN_COLORS, SHEEN_POSITIONS)
        ensureDefaultCover()
        updateShaderMatrix()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateLoopState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateLoopState()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    private fun shouldKeepAnimating(): Boolean {
        return isHostActive &&
            isAttachedToWindow &&
            visibility == VISIBLE &&
            (isPlaying || currentSpeedDegPerSec > 0.15f || smoothedVisualizerLevel > 0.01f)
    }

    private fun updateLoopState() {
        if (shouldKeepAnimating()) {
            startLoop()
        } else {
            stopLoop()
            if (!isHostActive || visibility != VISIBLE) {
                currentSpeedDegPerSec = 0f
                smoothedVisualizerLevel = 0f
            }
        }
    }

    private fun startLoop() {
        if (choreographerRunning) return
        choreographerRunning = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (choreographerRunning) {
            runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
            choreographerRunning = false
            lastFrameNs = 0L
        }
    }

    private fun ensureDefaultCover() {
        if (defaultCoverShader != null) return
        val bmp = CoverPaletteExtractor.renderDefaultCoverBitmap(context, 192) ?: return
        defaultCoverBitmap = bmp
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        defaultCoverShader = shader
        defaultArtPaint.shader = shader
    }

    private fun updateShaderMatrix() {
        updateMatrixFor(discBitmap, discShader, shaderMatrix)
        updateMatrixFor(defaultCoverBitmap, defaultCoverShader, defaultShaderMatrix)
    }

    private fun updateMatrixFor(bmp: Bitmap?, shader: BitmapShader?, matrix: Matrix) {
        if (bmp == null || shader == null || cachedLabelRadius <= 0f) return
        val diameter = cachedLabelRadius * 2f
        val bw = bmp.width.toFloat().coerceAtLeast(1f)
        val bh = bmp.height.toFloat().coerceAtLeast(1f)
        val scale = maxOf(diameter / bw, diameter / bh)
        val dx = -bw * scale * 0.5f
        val dy = -bh * scale * 0.5f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        shader.setLocalMatrix(matrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0 || cachedDiscRadius <= 0f) return
        val cx = width * 0.5f
        val cy = height * 0.5f
        val discRadius = cachedDiscRadius
        val maxRippleExpand = cachedMaxAvail - discRadius

        // 1. Concentric PCM ripple rings at outer edge
        val vis = smoothedVisualizerLevel
        if (vis > 0.01f && maxRippleExpand > 2f) {
            val accentRgb = palette.accentGlow and 0x00FFFFFF
            for (i in ringPhases.indices) {
                val phase = ringPhases[i]
                val r = discRadius + phase * maxRippleExpand * (0.45f + vis * 0.55f)
                val alpha = ((1f - phase) * vis * 130f).toInt().coerceIn(0, 140)
                if (alpha > 0) {
                    ripplePaint.color = (alpha shl 24) or accentRgb
                    ripplePaint.strokeWidth = (1.5f + vis * 1.5f) * density
                    canvas.drawCircle(cx, cy, r, ripplePaint)
                }
            }
        }

        // 2. Rotating Vinyl Platter + Grooves + 1:1 Center Label
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(discAngleDeg)

        canvas.drawCircle(0f, 0f, discRadius, vinylBasePaint)
        for (i in 0 until GROOVE_COUNT) {
            vinylGroovePaint.color = if (i % 3 == 0) 0x16FFFFFF else 0x0BFFFFFF
            canvas.drawCircle(0f, 0f, grooveRadii[i], vinylGroovePaint)
        }
        canvas.drawCircle(0f, 0f, discRadius - vinylRimPaint.strokeWidth * 0.5f, vinylRimPaint)

        val labelRadius = cachedLabelRadius
        if (discShader != null) {
            canvas.drawCircle(0f, 0f, labelRadius, artPaint)
        } else if (defaultCoverShader != null) {
            canvas.drawCircle(0f, 0f, labelRadius, defaultArtPaint)
        } else {
            labelFallbackPaint.color = (0xFF shl 24) or (palette.glassTint and 0x00FFFFFF)
            canvas.drawCircle(0f, 0f, labelRadius, labelFallbackPaint)
        }
        canvas.drawCircle(0f, 0f, labelRadius, labelRimPaint)

        canvas.drawCircle(0f, 0f, cachedSpindleRadius, centerHolePaint)
        canvas.drawCircle(0f, 0f, cachedSpindleRadius, spindleRingPaint)
        canvas.restore()

        // 3. Stationary Diagonal Light Sheen across spinning vinyl surface
        if (sheenPaint.shader != null) {
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(-35f)
            canvas.drawCircle(0f, 0f, discRadius, sheenPaint)
            canvas.restore()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        val nowMs = SystemClock.elapsedRealtime()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                SpringAnimatorHelper.cancelSpring(this, CHANNEL_VINYL_SWIPE)
                swipeController.onDown(event.rawX, event.rawY, nowMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val prevState = swipeController.state
                val collapse = swipeController.onMove(
                    x = event.rawX,
                    y = event.rawY,
                    nowMs = nowMs,
                    touchSlopPx = touchSlop.toFloat(),
                    swipeThresholdPx = swipeThresholdPx,
                    maxOffsetPx = maxSwipeOffsetPx
                )
                if (collapse) {
                    translationX = 0f
                    onCollapse?.invoke()
                    return true
                }
                if (swipeController.state == NowPlayingView.HorizontalSwipeCommitController.GestureState.HORIZONTAL_DRAG) {
                    if (prevState != NowPlayingView.HorizontalSwipeCommitController.GestureState.HORIZONTAL_DRAG) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    translationX = swipeController.translationX
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasHorizontal = swipeController.state == NowPlayingView.HorizontalSwipeCommitController.GestureState.HORIZONTAL_DRAG
                val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                val triggerDir = swipeController.onRelease(
                    x = event.rawX,
                    isCancel = isCancel,
                    swipeThresholdPx = swipeThresholdPx
                )
                if (wasHorizontal) {
                    val profile = if (SpringAnimatorHelper.isReducedMotionEnabled(context)) {
                        SpringAnimatorHelper.SPRING_REDUCED_MOTION
                    } else {
                        SpringAnimatorHelper.SPRING_BOUNCY
                    }
                    SpringAnimatorHelper.animateSpring(
                        key = this,
                        start = translationX,
                        target = 0f,
                        stiffness = profile.stiffness,
                        dampingRatio = profile.dampingRatio,
                        valueThreshold = 0.25f,
                        channel = CHANNEL_VINYL_SWIPE,
                        onUpdate = { tx -> translationX = tx }
                    )
                } else {
                    translationX = 0f
                }
                if (triggerDir > 0) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onNext?.invoke()
                } else if (triggerDir < 0) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onPrevious?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        internal const val TARGET_DEG_PER_SEC = 24f // 360 deg / 15s
        private const val INERTIA_UP_SECONDS = 0.45f
        private const val INERTIA_DOWN_SECONDS = 0.65f
        private const val GROOVE_COUNT = 16
        private const val CHANNEL_VINYL_SWIPE = 26

        private val SHEEN_COLORS = intArrayOf(
            0x00FFFFFF,
            0x20FFFFFF,
            0x00FFFFFF,
            0x00FFFFFF,
            0x1CFFFFFF,
            0x00FFFFFF
        )
        private val SHEEN_POSITIONS = floatArrayOf(0.0f, 0.12f, 0.24f, 0.50f, 0.62f, 0.74f)
    }
}
