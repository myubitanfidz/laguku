package com.laguku.player.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.LruCache
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.laguku.player.model.ChapterMarker
import com.laguku.player.model.CoverPalette
import com.laguku.player.ui.glass.CoverPaletteExtractor
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Custom glass capsule seekbar with 1:1 finger touch tracking and tabular duration numerals.
 * Zero object allocations occur inside [onDraw].
 */
class SeekCapsuleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val labelTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        12f,
        resources.displayMetrics
    )

    private val trackHeightIdlePx = 7f * density
    private val trackHeightScrubPx = 11f * density
    private val rimWidthPx = (1.1f * density).coerceAtLeast(1f)
    private val horizontalPadPx = 4f * density
    private val labelTopGapPx = 10f * density
    private val thumbWidthPx = 4f * density
    private val thumbExtraHeightPx = 6f * density

    private var accentColor: Int = CoverPaletteExtractor.defaultPalette().accentGlow
    private var glassTintColor: Int = CoverPaletteExtractor.defaultPalette().glassTint

    private var durationMs: Long = 0L
    private var positionMs: Long = 0L
    private var scrubPositionMs: Long = 0L
    private var isScrubbing: Boolean = false

    private val waveformBars = FloatArray(WAVEFORM_BAR_COUNT)
    private var hasWaveform: Boolean = false
    private var abStartMs: Long? = null
    private var abEndMs: Long? = null
    private var chapters: List<ChapterMarker> = emptyList()

    private var onSeekListener: ((Long) -> Unit)? = null
    private var onScrubPreviewListener: ((Long, Boolean) -> Unit)? = null

    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x38FFFFFF
    }
    private val trackTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }
    private val waveUnplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x45FFFFFF
    }
    private val wavePlayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xAAFFFFFF.toInt()
    }
    private val abBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val chapterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCCFFFFFF.toInt()
    }
    private val trackRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rimWidthPx
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val elapsedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = labelTextSizePx
        fontFeatureSettings = "tnum"
        textAlign = Paint.Align.LEFT
    }
    private val remainingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8FFFFFFF.toInt()
        textSize = labelTextSizePx
        fontFeatureSettings = "tnum"
        textAlign = Paint.Align.RIGHT
    }

    private val trackRect = RectF()
    private val fillRect = RectF()
    private val rimRect = RectF()
    private val thumbRect = RectF()

    // Pre-allocated char buffers for zero-allocation tabular time drawing
    private val elapsedChars = CharArray(12)
    private var elapsedLen = 0
    private var lastElapsedSec = Long.MIN_VALUE

    private val remainingChars = CharArray(12)
    private var remainingLen = 0
    private var lastRemainingSec = Long.MIN_VALUE

    init {
        isClickable = true
        applyTintColor()
        updateTimeBuffers(force = true)
    }

    fun setPalette(palette: CoverPalette) {
        accentColor = palette.accentGlow
        glassTintColor = palette.glassTint
        trackFillPaint.color = accentColor
        applyTintColor()
        invalidate()
    }

    fun setAccentColor(color: Int) {
        if (accentColor == color) return
        accentColor = color
        trackFillPaint.color = accentColor
        invalidate()
    }

    fun setPosition(posMs: Long, durMs: Long) {
        val safeDur = durMs.coerceAtLeast(0L)
        val safePos = if (safeDur > 0L) posMs.coerceIn(0L, safeDur) else posMs.coerceAtLeast(0L)
        durationMs = safeDur
        positionMs = safePos
        if (!isScrubbing) {
            updateTimeBuffers(force = false)
            invalidate()
        }
    }

    fun setOnSeekListener(listener: ((Long) -> Unit)?) {
        onSeekListener = listener
    }

    fun setOnScrubPreviewListener(listener: ((Long, Boolean) -> Unit)?) {
        onScrubPreviewListener = listener
    }

    fun setWaveform(bars: FloatArray?) {
        if (bars == null || bars.isEmpty()) {
            hasWaveform = false
        } else {
            val n = minOf(WAVEFORM_BAR_COUNT, bars.size)
            for (i in 0 until WAVEFORM_BAR_COUNT) {
                waveformBars[i] = if (i < n) bars[i].coerceIn(0.12f, 1f) else 0.15f
            }
            hasWaveform = true
        }
        invalidate()
    }

    fun setAbLoop(startMs: Long?, endMs: Long?) {
        abStartMs = startMs
        abEndMs = endMs
        invalidate()
    }

    fun setChapters(items: List<ChapterMarker>) {
        chapters = items
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = suggestedMinimumWidth + paddingLeft + paddingRight
        val textMetrics = elapsedPaint.fontMetrics
        val textHeight = (textMetrics.descent - textMetrics.ascent)
        val desiredHeight = (paddingTop + paddingBottom +
            trackHeightScrubPx + thumbExtraHeightPx + labelTopGapPx + textHeight + 4f * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val left = paddingLeft + horizontalPadPx
        val right = (w - paddingRight - horizontalPadPx).coerceAtLeast(left + 1f)
        trackRimPaint.shader = LinearGradient(
            left,
            0f,
            right,
            trackHeightScrubPx,
            0x57FFFFFF,
            0x12FFFFFF,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + horizontalPadPx
        val right = (width - paddingRight - horizontalPadPx).coerceAtLeast(left + 1f)
        val trackWidth = right - left

        val activeTrackH = if (isScrubbing) trackHeightScrubPx else trackHeightIdlePx
        val trackCenterY = paddingTop + (trackHeightScrubPx + thumbExtraHeightPx) * 0.5f
        val top = trackCenterY - activeTrackH * 0.5f
        val bottom = trackCenterY + activeTrackH * 0.5f
        val cornerRadius = activeTrackH * 0.5f

        trackRect.set(left, top, right, bottom)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackBgPaint)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackTintPaint)

        val shownPos = if (isScrubbing) scrubPositionMs else positionMs
        val fraction = if (durationMs > 0L) {
            (shownPos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        val fillRight = (left + trackWidth * fraction).coerceIn(left, right)
        if (fillRight > left + 0.5f) {
            fillRect.set(left, top, fillRight, bottom)
            canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, trackFillPaint)
        }

        // Subtle glass waveform silhouette inside capsule (zero allocations)
        if (hasWaveform && trackWidth > 20f) {
            val step = trackWidth / WAVEFORM_BAR_COUNT
            val barW = (step * 0.48f).coerceAtLeast(1.5f)
            val maxBarH = activeTrackH * 0.85f
            for (i in 0 until WAVEFORM_BAR_COUNT) {
                val bx = left + i * step + (step - barW) * 0.5f
                val bh = (maxBarH * waveformBars[i]).coerceAtLeast(1.5f)
                val byTop = trackCenterY - bh * 0.5f
                val paint = if (bx <= fillRight) wavePlayedPaint else waveUnplayedPaint
                thumbRect.set(bx, byTop, bx + barW, byTop + bh)
                canvas.drawRoundRect(thumbRect, barW * 0.5f, barW * 0.5f, paint)
            }
        }

        // Chapter tick dots (only when chapters exist)
        if (durationMs > 0L && chapters.isNotEmpty()) {
            val dotR = 1.6f * density
            for (i in chapters.indices) {
                val cf = (chapters[i].startMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val cx = left + trackWidth * cf
                canvas.drawCircle(cx, trackCenterY, dotR, chapterDotPaint)
            }
        }

        // AB-Loop thin indicator bracket (only when A or B is set)
        val aMs = abStartMs
        if (durationMs > 0L && aMs != null) {
            val ax = left + trackWidth * (aMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val bMs = abEndMs
            if (bMs != null && bMs > aMs) {
                val bx = left + trackWidth * (bMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                abBracketPaint.color = accentColor
                canvas.drawLine(ax, top - 2f * density, bx, top - 2f * density, abBracketPaint)
                canvas.drawLine(ax, top - 3f * density, ax, bottom + 2f * density, abBracketPaint)
                canvas.drawLine(bx, top - 3f * density, bx, bottom + 2f * density, abBracketPaint)
            } else {
                abBracketPaint.color = accentColor
                canvas.drawLine(ax, top - 3f * density, ax, bottom + 2f * density, abBracketPaint)
            }
        }

        val halfRim = rimWidthPx * 0.5f
        rimRect.set(left + halfRim, top + halfRim, right - halfRim, bottom - halfRim)
        canvas.drawRoundRect(
            rimRect,
            (cornerRadius - halfRim).coerceAtLeast(0f),
            (cornerRadius - halfRim).coerceAtLeast(0f),
            trackRimPaint
        )

        if (isScrubbing || durationMs > 0L) {
            val thumbCenterX = fillRight.coerceIn(left + thumbWidthPx * 0.5f, right - thumbWidthPx * 0.5f)
            val thumbH = activeTrackH + (if (isScrubbing) thumbExtraHeightPx else 2f * density)
            val thumbHalfW = (if (isScrubbing) thumbWidthPx * 1.15f else thumbWidthPx) * 0.5f
            thumbRect.set(
                thumbCenterX - thumbHalfW,
                trackCenterY - thumbH * 0.5f,
                thumbCenterX + thumbHalfW,
                trackCenterY + thumbH * 0.5f
            )
            canvas.drawRoundRect(thumbRect, thumbHalfW, thumbHalfW, thumbPaint)
        }

        val baselineY = paddingTop + trackHeightScrubPx + thumbExtraHeightPx +
            labelTopGapPx - elapsedPaint.fontMetrics.ascent
        if (elapsedLen > 0) {
            canvas.drawText(elapsedChars, 0, elapsedLen, left, baselineY, elapsedPaint)
        }
        if (remainingLen > 0) {
            canvas.drawText(remainingChars, 0, remainingLen, right, baselineY, remainingPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0L) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScrubbing = true
                scrubPositionMs = xToPositionMs(event.x)
                updateTimeBuffers(force = true)
                onScrubPreviewListener?.invoke(scrubPositionMs, true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScrubbing) {
                    scrubPositionMs = xToPositionMs(event.x)
                    updateTimeBuffers(force = false)
                    onScrubPreviewListener?.invoke(scrubPositionMs, true)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isScrubbing) {
                    scrubPositionMs = xToPositionMs(event.x)
                    positionMs = scrubPositionMs
                    isScrubbing = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    updateTimeBuffers(force = true)
                    onScrubPreviewListener?.invoke(scrubPositionMs, false)
                    onSeekListener?.invoke(scrubPositionMs)
                    performClick()
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isScrubbing) {
                    isScrubbing = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    updateTimeBuffers(force = true)
                    onScrubPreviewListener?.invoke(positionMs, false)
                    invalidate()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    private fun xToPositionMs(x: Float): Long {
        val left = paddingLeft + horizontalPadPx
        val right = (width - paddingRight - horizontalPadPx).coerceAtLeast(left + 1f)
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return (fraction * durationMs.toFloat()).roundToLong().coerceIn(0L, durationMs)
    }

    private fun applyTintColor() {
        val rgb = glassTintColor and 0x00FFFFFF
        trackTintPaint.color = (0x24 shl 24) or rgb
    }

    private fun updateTimeBuffers(force: Boolean) {
        val shownMs = if (isScrubbing) scrubPositionMs else positionMs
        val elapsedSec = (shownMs / 1000L).coerceAtLeast(0L)
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val remainingSec = (totalSec - elapsedSec).coerceAtLeast(0L)

        if (force || elapsedSec != lastElapsedSec) {
            lastElapsedSec = elapsedSec
            elapsedLen = formatDurationInto(elapsedSec, negativePrefix = false, out = elapsedChars)
        }
        if (force || remainingSec != lastRemainingSec) {
            lastRemainingSec = remainingSec
            remainingLen = formatDurationInto(remainingSec, negativePrefix = true, out = remainingChars)
        }
    }

    private fun formatDurationInto(totalSeconds: Long, negativePrefix: Boolean, out: CharArray): Int {
        var idx = 0
        if (negativePrefix) {
            out[idx++] = '-'
        }
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        if (hours > 0L) {
            idx = writeInt(hours, pad2 = false, out = out, startIdx = idx)
            out[idx++] = ':'
            idx = writeInt(minutes, pad2 = true, out = out, startIdx = idx)
        } else {
            idx = writeInt(minutes, pad2 = false, out = out, startIdx = idx)
        }
        out[idx++] = ':'
        idx = writeInt(seconds, pad2 = true, out = out, startIdx = idx)
        return idx
    }

    private fun writeInt(value: Long, pad2: Boolean, out: CharArray, startIdx: Int): Int {
        var idx = startIdx
        val v = value.coerceAtLeast(0L)
        if (pad2 && v < 10L) {
            out[idx++] = '0'
            out[idx++] = ('0'.code + v.toInt()).toChar()
            return idx
        }
        if (v == 0L) {
            out[idx++] = '0'
            return idx
        }
        var divisor = 1L
        while (v / divisor >= 10L) {
            divisor *= 10L
        }
        while (divisor > 0L && idx < out.size) {
            val digit = ((v / divisor) % 10L).toInt()
            out[idx++] = ('0'.code + digit).toChar()
            divisor /= 10L
        }
        return idx
    }

    companion object {
        const val WAVEFORM_BAR_COUNT = 48
        private val waveformCache = LruCache<String, FloatArray>(32)

        /**
         * Computes a lightweight normalized 48-bar energy silhouette off the main thread
         * with an in-memory 32-entry LRU cache.
         */
        fun computeWaveformBars(file: File, barCount: Int = WAVEFORM_BAR_COUNT): FloatArray? {
            if (!file.isFile || !file.canRead()) return null
            val key = "${file.absolutePath}|${file.length()}"
            waveformCache.get(key)?.let { return it }

            val len = file.length()
            if (len < 1024L) return null
            val out = FloatArray(barCount)
            val chunk = ByteArray(1024)
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val dataStart = minOf(4096L, len / 10L)
                    val usable = (len - dataStart).coerceAtLeast(1024L)
                    var maxVal = 0.01f
                    for (i in 0 until barCount) {
                        val pos = dataStart + (usable * i) / barCount
                        raf.seek(pos.coerceIn(0L, (len - 1024L).coerceAtLeast(0L)))
                        val read = raf.read(chunk)
                        if (read > 1) {
                            var acc = 0f
                            var j = 0
                            while (j + 1 < read) {
                                val sample = ((chunk[j + 1].toInt() shl 8) or (chunk[j].toInt() and 0xFF)).toFloat()
                                acc += abs(sample)
                                j += 4
                            }
                            val avg = acc / (read / 4f).coerceAtLeast(1f)
                            out[i] = avg
                            if (avg > maxVal) maxVal = avg
                        }
                    }
                    for (i in 0 until barCount) {
                        out[i] = (0.18f + 0.82f * (out[i] / maxVal)).coerceIn(0.18f, 1f)
                    }
                }
            }.onFailure { return null }

            waveformCache.put(key, out)
            return out
        }
    }
}
