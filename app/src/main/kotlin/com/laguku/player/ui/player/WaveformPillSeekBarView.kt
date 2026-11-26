package com.laguku.player.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * Pill-shaped audio waveform seekbar with 1:1 finger scrubbing, cached per-track energy
 * profile (with instant deterministic fallback), chapter dots, AB-loop brackets,
 * and zero allocations inside [onDraw] / [onTouchEvent].
 */
class WaveformPillSeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    object WaveformMath {

        fun normalizeAmplitudes(raw: FloatArray, minHeight: Float = 0.16f): FloatArray {
            if (raw.isEmpty()) return FloatArray(0)
            val floor = minHeight.coerceIn(0.05f, 0.9f)
            var peak = 0.0001f
            for (v in raw) {
                val av = abs(v)
                if (av > peak) peak = av
            }
            val out = FloatArray(raw.size)
            val span = 1f - floor
            for (i in raw.indices) {
                val norm = (abs(raw[i]) / peak).coerceIn(0f, 1f)
                out[i] = (floor + span * norm).coerceIn(floor, 1f)
            }
            return out
        }

        fun generateDeterministicProfile(seedKey: String, barCount: Int = WAVEFORM_BAR_COUNT): FloatArray {
            val count = barCount.coerceAtLeast(1)
            val out = FloatArray(count)
            var state = seedKey.hashCode().toLong() xor 0x5DEECE66DL
            if (state == 0L) state = 0x13579BDF2468ACEL
            for (i in 0 until count) {
                state = (state * 6364136223846793005L + 1442695040888963407L)
                val unit = ((state ushr 33) and 0xFFFFL).toFloat() / 65535f
                val contour = 0.30f + 0.45f * kotlin.math.sin((i.toFloat() / count) * Math.PI).toFloat()
                out[i] = (0.20f + 0.80f * (contour * 0.55f + unit * 0.45f)).coerceIn(0.18f, 1f)
            }
            return out
        }
    }

    private val density = resources.displayMetrics.density
    private val labelTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        12f,
        resources.displayMetrics
    )

    private val waveformZoneHeightPx = 30f * density
    private val horizontalPadPx = 4f * density
    private val labelTopGapPx = 6f * density
    private val playheadWidthPx = (3.2f * density).coerceAtLeast(2f)

    private var accentColor: Int = CoverPaletteExtractor.defaultPalette().accentGlow

    private var durationMs: Long = 0L
    private var positionMs: Long = 0L
    private var scrubPositionMs: Long = 0L
    private var isScrubbing: Boolean = false

    private val waveformBars = FloatArray(WAVEFORM_BAR_COUNT) { 0.32f }
    private var abStartMs: Long? = null
    private var abEndMs: Long? = null
    private var chapters: List<ChapterMarker> = emptyList()

    private var onSeekListener: ((Long) -> Unit)? = null
    private var onScrubPreviewListener: ((Long, Boolean) -> Unit)? = null

    private val unplayedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x44FFFFFF
    }
    private val playedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val abBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = accentColor
    }
    private val chapterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xCCFFFFFF.toInt()
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

    private val barRect = RectF()
    private val playheadRect = RectF()

    private val elapsedChars = CharArray(12)
    private var elapsedLen = 0
    private var lastElapsedSec = Long.MIN_VALUE

    private val remainingChars = CharArray(12)
    private var remainingLen = 0
    private var lastRemainingSec = Long.MIN_VALUE

    init {
        isClickable = true
        updateTimeBuffers(force = true)
    }

    fun setPalette(palette: CoverPalette) {
        accentColor = palette.accentGlow
        playedBarPaint.color = accentColor
        abBracketPaint.color = accentColor
        invalidate()
    }

    fun setTrackKey(trackKey: String) {
        val cached = waveformCache.get(trackKey)
        if (cached != null) {
            setWaveform(cached)
        } else {
            val fallback = WaveformMath.generateDeterministicProfile(trackKey, WAVEFORM_BAR_COUNT)
            setWaveform(fallback)
        }
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
            val fallback = WaveformMath.generateDeterministicProfile("laguku_default", WAVEFORM_BAR_COUNT)
            for (i in 0 until WAVEFORM_BAR_COUNT) {
                waveformBars[i] = fallback[i]
            }
        } else {
            val n = minOf(WAVEFORM_BAR_COUNT, bars.size)
            for (i in 0 until WAVEFORM_BAR_COUNT) {
                waveformBars[i] = if (i < n) bars[i].coerceIn(0.16f, 1f) else 0.2f
            }
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
        val textHeight = textMetrics.descent - textMetrics.ascent
        val desiredHeight = (paddingTop + paddingBottom +
            waveformZoneHeightPx + labelTopGapPx + textHeight + 2f * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + horizontalPadPx
        val right = (width - paddingRight - horizontalPadPx).coerceAtLeast(left + 1f)
        val trackWidth = right - left

        val zoneTop = paddingTop.toFloat()
        val zoneBottom = zoneTop + waveformZoneHeightPx
        val centerY = (zoneTop + zoneBottom) * 0.5f

        val shownPos = if (isScrubbing) scrubPositionMs else positionMs
        val fraction = if (durationMs > 0L) {
            (shownPos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val playheadX = (left + trackWidth * fraction).coerceIn(left, right)

        // Pill waveform bars
        if (trackWidth > 16f) {
            val step = trackWidth / WAVEFORM_BAR_COUNT
            val barW = (step * 0.56f).coerceIn(2f * density, 5.5f * density)
            val radius = barW * 0.5f
            val maxBarH = if (isScrubbing) waveformZoneHeightPx * 0.94f else waveformZoneHeightPx * 0.84f
            for (i in 0 until WAVEFORM_BAR_COUNT) {
                val bx = left + i * step + (step - barW) * 0.5f
                val barCenterX = bx + barW * 0.5f
                val bh = (maxBarH * waveformBars[i]).coerceAtLeast(barW)
                val byTop = centerY - bh * 0.5f
                barRect.set(bx, byTop, bx + barW, byTop + bh)
                val paint = if (barCenterX <= playheadX) playedBarPaint else unplayedBarPaint
                canvas.drawRoundRect(barRect, radius, radius, paint)
            }
        }

        // Chapter dots
        if (durationMs > 0L && chapters.isNotEmpty()) {
            val dotR = 1.8f * density
            for (i in chapters.indices) {
                val cf = (chapters[i].startMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                val cx = left + trackWidth * cf
                canvas.drawCircle(cx, zoneBottom - dotR, dotR, chapterDotPaint)
            }
        }

        // AB-Loop bracket
        val aMs = abStartMs
        if (durationMs > 0L && aMs != null) {
            val ax = left + trackWidth * (aMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val bMs = abEndMs
            if (bMs != null && bMs > aMs) {
                val bx = left + trackWidth * (bMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                canvas.drawLine(ax, zoneTop + 1f, bx, zoneTop + 1f, abBracketPaint)
                canvas.drawLine(ax, zoneTop + 1f, ax, zoneBottom - 1f, abBracketPaint)
                canvas.drawLine(bx, zoneTop + 1f, bx, zoneBottom - 1f, abBracketPaint)
            } else {
                canvas.drawLine(ax, zoneTop + 1f, ax, zoneBottom - 1f, abBracketPaint)
            }
        }

        // Playhead vertical pill
        if (isScrubbing || durationMs > 0L) {
            val halfW = (if (isScrubbing) playheadWidthPx * 1.2f else playheadWidthPx) * 0.5f
            val cx = playheadX.coerceIn(left + halfW, right - halfW)
            val phH = if (isScrubbing) waveformZoneHeightPx else waveformZoneHeightPx * 0.88f
            playheadRect.set(cx - halfW, centerY - phH * 0.5f, cx + halfW, centerY + phH * 0.5f)
            canvas.drawRoundRect(playheadRect, halfW, halfW, playheadPaint)
        }

        val baselineY = zoneBottom + labelTopGapPx - elapsedPaint.fontMetrics.ascent
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

    override fun performClick(): Boolean = super.performClick()

    private fun xToPositionMs(x: Float): Long {
        val left = paddingLeft + horizontalPadPx
        val right = (width - paddingRight - horizontalPadPx).coerceAtLeast(left + 1f)
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        return (fraction * durationMs.toFloat()).roundToLong().coerceIn(0L, durationMs)
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
        if (negativePrefix) out[idx++] = '-'
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
        while (v / divisor >= 10L) divisor *= 10L
        while (divisor > 0L && idx < out.size) {
            val digit = ((v / divisor) % 10L).toInt()
            out[idx++] = ('0'.code + digit).toChar()
            divisor /= 10L
        }
        return idx
    }

    companion object {
        const val WAVEFORM_BAR_COUNT = 48
        private val waveformCache = LruCache<String, FloatArray>(64)

        /**
         * Extracts normalized pill waveform bars off the main thread and caches per URI/path.
         * Falls back cleanly to a deterministic profile if file reading fails.
         */
        fun extractWaveformBars(
            file: File,
            cacheKey: String = file.absolutePath,
            barCount: Int = WAVEFORM_BAR_COUNT
        ): FloatArray {
            val key = "$cacheKey|${file.length()}"
            waveformCache.get(key)?.let { return it }

            val len = if (file.isFile && file.canRead()) file.length() else 0L
            if (len < 1024L) {
                return WaveformMath.generateDeterministicProfile(cacheKey, barCount).also {
                    waveformCache.put(key, it)
                }
            }

            val raw = FloatArray(barCount)
            val chunk = ByteArray(1024)
            var extracted = false
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val dataStart = minOf(4096L, len / 10L)
                    val usable = (len - dataStart).coerceAtLeast(1024L)
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
                            raw[i] = acc / (read / 4f).coerceAtLeast(1f)
                        }
                    }
                    extracted = true
                }
            }

            val result = if (extracted) {
                WaveformMath.normalizeAmplitudes(raw, minHeight = 0.16f)
            } else {
                WaveformMath.generateDeterministicProfile(cacheKey, barCount)
            }
            waveformCache.put(key, result)
            return result
        }
    }
}
