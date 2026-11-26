package com.laguku.player.ui.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.lyrics.LrcParser
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.LyricLine
import com.laguku.player.model.ParsedLyrics
import com.laguku.player.model.WordToken
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.motion.SpringAnimatorHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Calibrated Apple Music-style physics and timing constants for lyrics motion.
 */
object LyricMotionSpec {
    const val SPRING_STIFFNESS = 120f
    const val SPRING_DAMPING_RATIO = 0.78f
    const val FOCUS_ANCHOR_FRACTION = 0.36f
    const val STAGGER_STEP_MS = 40L
    const val STAGGER_MAX_MS = 90L
    const val INACTIVE_SCALE = 0.94f
    const val MAX_DEPTH_BLUR_PX = 3.5f
    const val WORD_SWEEP_ENABLED = true
    const val MANUAL_SCROLL_PAUSE_MS = 2_500L
}

/**
 * Synced & static 1-3 layer lyrics adapter with:
 * - Unclipped multi-line & 3-layer support (original + romanization + translation)
 * - Continuous Choreographer spring scroll driver targeting [highlightFraction] (default 36%)
 * - Staggered downward wave [microLagY] on rows below the active line
 * - Multi-line word-by-word sweep highlight ([SweepLyricTextView])
 * - Per-item in-place focus interpolation (scale 0.94 -> 1.0, opacity 0.38 -> 1.0, RenderEffect <= 3.5px)
 * - Zero object allocations inside [onBindViewHolder], [onDraw], and [Choreographer.FrameCallback]
 */
class LyricsRecyclerAdapter(
    private val onLineTap: (LyricLine, Int) -> Unit,
    private val onSelectionChanged: ((List<LyricLine>) -> Unit)? = null
) : RecyclerView.Adapter<LyricsRecyclerAdapter.LyricViewHolder>() {

    object LyricWindowMath {

        fun computeScrollTopOffset(
            viewportHeight: Int,
            itemHeight: Int,
            anchorFraction: Float,
            topSafeMargin: Int,
            bottomSafeMargin: Int
        ): Int {
            if (viewportHeight <= 0) return 0
            val clampedTop = topSafeMargin.coerceAtLeast(0)
            val clampedBottom = bottomSafeMargin.coerceAtLeast(0)
            val safeItemH = itemHeight.coerceAtLeast(0)
            val maxTop = (viewportHeight - clampedBottom - safeItemH).coerceAtLeast(clampedTop)
            val idealTop = (viewportHeight * anchorFraction - safeItemH * 0.25f).toInt()
            return idealTop.coerceIn(clampedTop, maxTop)
        }

        fun computeAdaptiveFadeLength(
            viewportHeight: Int,
            activeItemHeight: Int,
            defaultFadePx: Int
        ): Int {
            if (viewportHeight <= 0 || defaultFadePx <= 0) return defaultFadePx.coerceAtLeast(0)
            val minFade = (defaultFadePx / 3).coerceAtLeast(1)
            val safeFade = ((viewportHeight - activeItemHeight.coerceAtLeast(0)) / 4).coerceAtLeast(minFade)
            return minOf(defaultFadePx, safeFade)
        }

        fun computeAnchorPadding(
            viewportHeight: Int,
            anchorFraction: Float
        ): Pair<Int, Int> {
            if (viewportHeight <= 0) return 0 to 0
            val clamped = anchorFraction.coerceIn(0.20f, 0.60f)
            val padTop = (viewportHeight * clamped).roundToInt()
            val padBottom = (viewportHeight * (1f - clamped)).roundToInt()
            return padTop to padBottom
        }

        fun resolveInitialAnchorIndex(lineCount: Int, activeIndex: Int): Int {
            if (lineCount <= 0) return -1
            return if (activeIndex in 0 until lineCount) activeIndex else 0
        }

        fun computeBlurTier(
            position: Int,
            activeIndex: Int,
            synced: Boolean,
            selectionMode: Boolean
        ): Int {
            if (!synced || activeIndex < 0 || selectionMode) return 0
            return when (abs(position - activeIndex)) {
                0 -> 0
                1 -> 1
                else -> 2
            }
        }
    }

    object LyricMotionMath {
        const val LYRIC_SCROLL_STIFFNESS = LyricMotionSpec.SPRING_STIFFNESS
        const val LYRIC_SCROLL_DAMPING_RATIO = LyricMotionSpec.SPRING_DAMPING_RATIO
        const val FOCUS_SCALE_MIN = LyricMotionSpec.INACTIVE_SCALE
        const val FOCUS_SCALE_MAX = 1.00f
        const val FOCUS_ALPHA_MIN = 0.38f
        const val FOCUS_ALPHA_MAX = 1.00f
        const val HAS_ITEM_BACKGROUND = false

        val BLUR_RADII = floatArrayOf(0f, 0.9f, 1.8f, 2.6f, LyricMotionSpec.MAX_DEPTH_BLUR_PX)

        fun computeFocusScale(focusProgress: Float): Float =
            FOCUS_SCALE_MIN + (FOCUS_SCALE_MAX - FOCUS_SCALE_MIN) * focusProgress.coerceIn(0f, 1f)

        fun computeFocusAlpha(focusProgress: Float): Float =
            FOCUS_ALPHA_MIN + (FOCUS_ALPHA_MAX - FOCUS_ALPHA_MIN) * focusProgress.coerceIn(0f, 1f)

        fun computeStaggerDelayMs(rowIndex: Int, activeIndex: Int): Long {
            if (activeIndex < 0 || rowIndex <= activeIndex) return 0L
            return ((rowIndex - activeIndex).toLong() * LyricMotionSpec.STAGGER_STEP_MS)
                .coerceAtMost(LyricMotionSpec.STAGGER_MAX_MS)
        }

        fun computeInitialMicroLagY(
            rowIndex: Int,
            activeIndex: Int,
            scrollDeltaPx: Float,
            density: Float,
            reducedMotion: Boolean = false
        ): Float {
            if (reducedMotion || activeIndex < 0 || rowIndex <= activeIndex || scrollDeltaPx <= 0f) return 0f
            val delayMs = computeStaggerDelayMs(rowIndex, activeIndex)
            val waveScale = 0.45f + 0.55f * (delayMs.toFloat() / LyricMotionSpec.STAGGER_MAX_MS.toFloat())
            val maxLagPx = 18f * density.coerceAtLeast(1f)
            return min(scrollDeltaPx * 0.28f, maxLagPx) * waveScale
        }

        fun computeSweepCharProgress(
            textLength: Int,
            words: List<WordToken>,
            lineStartMs: Long,
            lineEndMs: Long,
            positionMs: Long,
            sweepEnabled: Boolean = LyricMotionSpec.WORD_SWEEP_ENABLED
        ): Float {
            if (textLength <= 0) return 0f
            if (!sweepEnabled) return textLength.toFloat()
            if (positionMs <= lineStartMs) return 0f

            if (words.isNotEmpty()) {
                var charCursor = 0
                for (i in words.indices) {
                    val token = words[i]
                    val tokenLen = token.text.length.coerceAtLeast(1)
                    val tokenStart = token.startMs
                    val tokenEnd = token.endMs?.coerceAtLeast(tokenStart + 40L) ?: if (i + 1 < words.size) {
                        words[i + 1].startMs.coerceAtLeast(tokenStart + 120L)
                    } else {
                        val fallbackEnd = if (lineEndMs > tokenStart) lineEndMs else tokenStart + 420L
                        fallbackEnd.coerceIn(tokenStart + 180L, tokenStart + 2_000L)
                    }
                    if (positionMs < tokenStart) {
                        return charCursor.toFloat().coerceAtMost(textLength.toFloat())
                    }
                    if (positionMs < tokenEnd) {
                        val frac = ((positionMs - tokenStart).toFloat() / (tokenEnd - tokenStart).toFloat())
                            .coerceIn(0f, 1f)
                        return (charCursor + tokenLen * frac).coerceIn(0f, textLength.toFloat())
                    }
                    charCursor += token.text.length
                }
                return textLength.toFloat()
            }

            val rawDuration = if (lineEndMs > lineStartMs) lineEndMs - lineStartMs else 2_800L
            val sweepDuration = rawDuration.coerceIn(600L, 6_000L)
            val elapsed = (positionMs - lineStartMs).coerceAtLeast(0L)
            val fraction = (elapsed.toFloat() / sweepDuration.toFloat()).coerceIn(0f, 1f)
            return textLength * fraction
        }

        fun interpolateColor(fromColor: Int, toColor: Int, fraction: Float): Int {
            val t = fraction.coerceIn(0f, 1f)
            val fA = (fromColor ushr 24) and 0xFF
            val fR = (fromColor ushr 16) and 0xFF
            val fG = (fromColor ushr 8) and 0xFF
            val fB = fromColor and 0xFF

            val tA = (toColor ushr 24) and 0xFF
            val tR = (toColor ushr 16) and 0xFF
            val tG = (toColor ushr 8) and 0xFF
            val tB = toColor and 0xFF

            val a = (fA + (tA - fA) * t).roundToInt().coerceIn(0, 255)
            val r = (fR + (tR - fR) * t).roundToInt().coerceIn(0, 255)
            val g = (fG + (tG - fG) * t).roundToInt().coerceIn(0, 255)
            val b = (fB + (tB - fB) * t).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        fun selectBlurPoolIndex(
            focusProgress: Float,
            distanceFromActive: Int,
            synced: Boolean,
            selectionMode: Boolean
        ): Int {
            if (!synced || selectionMode || distanceFromActive <= 0) return 0
            val inv = 1f - focusProgress.coerceIn(0f, 1f)
            return if (distanceFromActive == 1) {
                (inv * 2f).roundToInt().coerceIn(0, 2)
            } else {
                (inv * 4f).roundToInt().coerceIn(0, 4)
            }
        }

        class SubPixelScrollAccumulator {
            var subPixelRemainder: Float = 0f

            var remainder: Float
                get() = subPixelRemainder
                set(value) {
                    subPixelRemainder = value
                }

            fun reset() {
                subPixelRemainder = 0f
            }

            fun accumulate(floatDelta: Float): Int {
                val total = floatDelta + subPixelRemainder
                val intDelta = total.toInt()
                subPixelRemainder = total - intDelta
                return intDelta
            }

            fun flushRemainder(): Int {
                val delta = subPixelRemainder.roundToInt()
                subPixelRemainder = 0f
                return delta
            }
        }

        fun computeScrollStep(
            solver: SpringAnimatorHelper.SpringSolver,
            lastAppliedPx: Float,
            dtSec: Float
        ): Pair<Int, Boolean> {
            val settled = solver.step(dtSec)
            val targetPx = if (settled) solver.target else solver.value
            val delta = targetPx.roundToInt() - lastAppliedPx.roundToInt()
            return delta to settled
        }
    }

    object InterludeDotsMath {
        const val INTERLUDE_THRESHOLD_MS = 4_000L
        const val DOT_COUNT = 3
        const val DOT_DIAMETER_DP = 8f
        const val DOT_GAP_DP = 10f

        data class InterludeState(
            val active: Boolean = false,
            val anchorRowIndex: Int = -1,
            val isIntro: Boolean = false,
            val progress: Float = 0f,
            val pulseScale: Float = 1f
        )

        val INACTIVE = InterludeState()

        fun estimateLineEndMs(line: LyricLine): Long {
            line.endMs?.let { explicitEnd ->
                if (explicitEnd > line.timestampMs) return explicitEnd
            }
            if (line.words.isNotEmpty()) {
                val lastWord = line.words.last()
                return lastWord.endMs?.coerceAtLeast(lastWord.startMs + 80L)
                    ?: (lastWord.startMs + 500L)
            }
            if (line.primaryText.isBlank()) return line.timestampMs
            val estimatedDuration = (line.primaryText.length * 85L).coerceIn(1_200L, 4_200L)
            return line.timestampMs + estimatedDuration
        }

        fun resolveInterludeState(
            lines: List<LyricLine>,
            positionMs: Long,
            pulsePhaseRad: Float = 0f
        ): InterludeState {
            if (lines.isEmpty() || positionMs < 0L) return INACTIVE
            val pulseScale = 0.92f + 0.12f * (0.5f + 0.5f * sin(pulsePhaseRad))
            val firstStart = lines[0].timestampMs
            if (firstStart >= INTERLUDE_THRESHOLD_MS && positionMs < firstStart) {
                val progress = (positionMs.toFloat() / firstStart.toFloat()).coerceIn(0f, 1f)
                return InterludeState(
                    active = true,
                    anchorRowIndex = 0,
                    isIntro = true,
                    progress = progress,
                    pulseScale = pulseScale
                )
            }
            val currentIdx = LrcParser.findActiveLineIndex(lines, positionMs)
            if (currentIdx in 0 until lines.lastIndex) {
                val lineEnd = estimateLineEndMs(lines[currentIdx])
                val nextStart = lines[currentIdx + 1].timestampMs
                val gapMs = nextStart - lineEnd
                if (gapMs >= INTERLUDE_THRESHOLD_MS && positionMs in lineEnd until nextStart) {
                    val progress = ((positionMs - lineEnd).toFloat() / gapMs.toFloat()).coerceIn(0f, 1f)
                    return InterludeState(
                        active = true,
                        anchorRowIndex = currentIdx,
                        isIntro = false,
                        progress = progress,
                        pulseScale = pulseScale
                    )
                }
            }
            return INACTIVE
        }

        fun computeDotFillAlpha(dotIndex: Int, overallProgress: Float): Float {
            if (dotIndex !in 0 until DOT_COUNT) return 0.22f
            val clamped = overallProgress.coerceIn(0f, 1f)
            val slice = 1f / DOT_COUNT.toFloat()
            val localStart = dotIndex * slice
            val fill = ((clamped - localStart) / slice).coerceIn(0f, 1f)
            return 0.22f + 0.78f * fill
        }
    }

    private var lines: List<LyricLine> = emptyList()
    private var synced: Boolean = false
    private var activeIndex: Int = -1
    private var highlightFraction: Float = LyricMotionSpec.FOCUS_ANCHOR_FRACTION
    private var topSafeMarginPx: Int = 0
    private var bottomSafeMarginPx: Int = 0
    private var defaultFadePx: Int = 0

    private var accentColorRaw: Int = CoverPaletteExtractor.defaultPalette().accentGlow
    private var accentTranslationFocusedColor: Int = computeSoftAccentColor(
        CoverPaletteExtractor.defaultPalette().accentGlow,
        focused = true
    )
    private var accentTranslationMutedColor: Int = computeSoftAccentColor(
        CoverPaletteExtractor.defaultPalette().accentGlow,
        focused = false
    )

    private var interludePhaseRad: Float = 0f
    private var interludeActive: Boolean = false
    private var interludeAnchorRow: Int = -1
    private var interludeIsIntro: Boolean = false
    private var interludeProgress: Float = 0f
    private var interludePulseScale: Float = 1f

    private val subPixelAccumulator = LyricMotionMath.SubPixelScrollAccumulator()
    var subPixelRemainder: Float = 0f
    private val scrollSpringSolver = SpringAnimatorHelper.SpringSolver(
        stiffness = LyricMotionSpec.SPRING_STIFFNESS,
        dampingRatio = LyricMotionSpec.SPRING_DAMPING_RATIO,
        valueThreshold = 0.05f,
        velocityThreshold = 0.5f
    )
    private var scrollSpringActive: Boolean = false
    private var lastScrollSolverValue: Float = 0f

    private var attachedRecyclerView: RecyclerView? = null
    private var hostActive: Boolean = true
    private var playbackActive: Boolean = false
    private var lyricsModeActive: Boolean = false
    private var lastPlaybackPositionMs: Long = 0L
    private var lastPlaybackAnchorNanos: Long = 0L
    private var lastFrameNanos: Long = 0L
    private var choreographerRunning: Boolean = false
    private var pendingScrollTargetPosition: Int = -1
    private var pendingScrollDeltaPx: Float = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val rv = attachedRecyclerView
            if (rv == null || !hostActive) {
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

            val scrollDelta = stepSpringScroll(dtSec)
            if (scrollDelta != 0) {
                rv.scrollBy(0, scrollDelta)
            }
            if (!scrollSpringActive && pendingScrollTargetPosition in lines.indices) {
                val lm = rv.layoutManager as? LinearLayoutManager
                val finalView = lm?.findViewByPosition(pendingScrollTargetPosition)
                if (finalView != null) {
                    val drift = finalView.top - rv.paddingTop
                    if (drift != 0) {
                        rv.scrollBy(0, drift)
                    }
                }
                pendingScrollTargetPosition = -1
            }

            val reducedMotion = SpringAnimatorHelper.isReducedMotionEnabled(rv.context)
            var needsMoreFrames = scrollSpringActive
            val sweepPosMs = computeInterpolatedPlaybackPosMs(frameTimeNanos)
            refreshInterludePrimitives(sweepPosMs, dtSec, advancePhase = playbackActive && !reducedMotion)

            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                val holder = rv.getChildViewHolder(child) as? LyricViewHolder ?: continue
                val pos = holder.bindingAdapterPosition

                if (holder.focusActive) {
                    holder.focusSolver.target = holder.targetFocus
                    holder.focusSolver.stiffness = LyricMotionSpec.SPRING_STIFFNESS
                    holder.focusSolver.dampingRatio = LyricMotionSpec.SPRING_DAMPING_RATIO
                    val settled = holder.focusSolver.step(dtSec)
                    applyFocusVisuals(holder, pos, holder.focusSolver.value)
                    if (settled) {
                        holder.focusActive = false
                    } else {
                        needsMoreFrames = true
                    }
                }

                if (holder.microLagDelaySec > 0f) {
                    holder.microLagDelaySec = (holder.microLagDelaySec - dtSec).coerceAtLeast(0f)
                    needsMoreFrames = true
                } else if (holder.microLagActive) {
                    val settledLag = holder.microLagSolver.step(dtSec)
                    holder.rowView.translationY = holder.microLagSolver.value
                    if (settledLag) {
                        holder.rowView.translationY = 0f
                        holder.microLagActive = false
                    } else {
                        needsMoreFrames = true
                    }
                }

                if (holder.rowView.tapGlowAlpha > 0f) {
                    val nextGlow = (holder.rowView.tapGlowAlpha - dtSec * 3.8f).coerceAtLeast(0f)
                    holder.rowView.updateTapGlow(nextGlow)
                    if (nextGlow > 0f) needsMoreFrames = true
                }

                if (synced && !selectionMode && pos == activeIndex && pos in lines.indices && LyricMotionSpec.WORD_SWEEP_ENABLED) {
                    val line = lines[pos]
                    val nextLineTs = lines.getOrNull(pos + 1)?.timestampMs ?: (line.timestampMs + 3_200L)
                    val charProgress = LyricMotionMath.computeSweepCharProgress(
                        textLength = holder.mainText.length(),
                        words = line.words,
                        lineStartMs = line.timestampMs,
                        lineEndMs = nextLineTs,
                        positionMs = sweepPosMs,
                        sweepEnabled = !reducedMotion
                    )
                    holder.mainText.setSweepProgress(active = true, charProgress = charProgress)
                } else {
                    holder.mainText.setSweepProgress(active = false, charProgress = 0f)
                }

                bindInterludeForHolder(holder, pos)
            }

            if (shouldKeepWordSweepRunning(reducedMotion) || (interludeActive && playbackActive && !reducedMotion)) {
                needsMoreFrames = true
            }

            if (needsMoreFrames && hostActive) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                choreographerRunning = false
                lastFrameNanos = 0L
            }
        }
    }

    private val selectedIndices = LinkedHashSet<Int>()
    private var selectionMode: Boolean = false

    fun setHostActive(active: Boolean) {
        hostActive = active
        if (!active) {
            stopChoreographer()
        } else {
            ensureChoreographerIfNeeded()
        }
    }

    fun setPlaybackActive(playing: Boolean) {
        playbackActive = playing
        if (playing) {
            lastPlaybackAnchorNanos = System.nanoTime()
            ensureChoreographerIfNeeded()
        } else {
            lastPlaybackAnchorNanos = 0L
        }
    }

    fun setLyricsModeActive(active: Boolean) {
        lyricsModeActive = active
        if (active) {
            lastPlaybackAnchorNanos = System.nanoTime()
            ensureChoreographerIfNeeded()
        }
    }

    fun updatePlaybackPosition(positionMs: Long) {
        lastPlaybackPositionMs = positionMs.coerceAtLeast(0L)
        lastPlaybackAnchorNanos = System.nanoTime()
        refreshInterludePrimitives(lastPlaybackPositionMs, dtSec = 0f, advancePhase = false)
        if (lyricsModeActive && synced) {
            val rv = attachedRecyclerView
            if (rv != null && !playbackActive) {
                for (i in 0 until rv.childCount) {
                    val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? LyricViewHolder ?: continue
                    val pos = holder.bindingAdapterPosition
                    bindInterludeForHolder(holder, pos)
                    if (!selectionMode && pos == activeIndex && pos in lines.indices) {
                        val line = lines[pos]
                        val nextTs = lines.getOrNull(pos + 1)?.timestampMs ?: (line.timestampMs + 3_200L)
                        val charProgress = LyricMotionMath.computeSweepCharProgress(
                            textLength = holder.mainText.length(),
                            words = line.words,
                            lineStartMs = line.timestampMs,
                            lineEndMs = nextTs,
                            positionMs = lastPlaybackPositionMs,
                            sweepEnabled = LyricMotionSpec.WORD_SWEEP_ENABLED
                        )
                        holder.mainText.setSweepProgress(active = true, charProgress = charProgress)
                    }
                }
            }
            ensureChoreographerIfNeeded()
        }
    }

    private fun refreshInterludePrimitives(positionMs: Long, dtSec: Float, advancePhase: Boolean) {
        if (!synced || selectionMode || lines.isEmpty() || positionMs < 0L) {
            interludeActive = false
            interludeAnchorRow = -1
            interludeProgress = 0f
            return
        }
        if (advancePhase && dtSec > 0f) {
            val twoPi = (2.0 * PI).toFloat()
            interludePhaseRad = (interludePhaseRad + dtSec * (twoPi / 1.8f)) % twoPi
        }
        val firstStart = lines[0].timestampMs
        if (firstStart >= InterludeDotsMath.INTERLUDE_THRESHOLD_MS && positionMs < firstStart) {
            interludeActive = true
            interludeAnchorRow = 0
            interludeIsIntro = true
            interludeProgress = (positionMs.toFloat() / firstStart.toFloat()).coerceIn(0f, 1f)
            interludePulseScale = 0.92f + 0.12f * (0.5f + 0.5f * sin(interludePhaseRad))
            return
        }
        val idx = LrcParser.findActiveLineIndex(lines, positionMs)
        if (idx in 0 until lines.lastIndex) {
            val lineEnd = InterludeDotsMath.estimateLineEndMs(lines[idx])
            val nextStart = lines[idx + 1].timestampMs
            val gap = nextStart - lineEnd
            if (gap >= InterludeDotsMath.INTERLUDE_THRESHOLD_MS && positionMs in lineEnd until nextStart) {
                interludeActive = true
                interludeAnchorRow = idx
                interludeIsIntro = false
                interludeProgress = ((positionMs - lineEnd).toFloat() / gap.toFloat()).coerceIn(0f, 1f)
                interludePulseScale = 0.92f + 0.12f * (0.5f + 0.5f * sin(interludePhaseRad))
                return
            }
        }
        interludeActive = false
        interludeAnchorRow = -1
        interludeProgress = 0f
    }

    private fun bindInterludeForHolder(holder: LyricViewHolder, pos: Int) {
        val showTop = interludeActive && interludeIsIntro && pos == interludeAnchorRow
        val showBottom = interludeActive && !interludeIsIntro && pos == interludeAnchorRow
        holder.topInterludeDots.setInterludeState(showTop, interludeProgress, interludePulseScale, accentColorRaw)
        holder.bottomInterludeDots.setInterludeState(showBottom, interludeProgress, interludePulseScale, accentColorRaw)
    }

    fun startSpringScrollTarget(deltaPx: Float) {
        if (deltaPx == 0f) {
            scrollSpringSolver.value = 0f
            scrollSpringSolver.target = 0f
            scrollSpringSolver.velocity = 0f
            lastScrollSolverValue = 0f
            subPixelRemainder = 0f
            subPixelAccumulator.reset()
            scrollSpringActive = false
            return
        }
        val start = if (scrollSpringActive) scrollSpringSolver.value else 0f
        if (!scrollSpringActive) {
            lastScrollSolverValue = 0f
            subPixelRemainder = 0f
            subPixelAccumulator.reset()
        }
        scrollSpringSolver.value = start
        scrollSpringSolver.target = lastScrollSolverValue - subPixelRemainder + deltaPx
        scrollSpringSolver.stiffness = LyricMotionSpec.SPRING_STIFFNESS
        scrollSpringSolver.dampingRatio = LyricMotionSpec.SPRING_DAMPING_RATIO
        scrollSpringSolver.valueThreshold = 0.05f
        scrollSpringSolver.velocityThreshold = 0.5f
        scrollSpringActive = true
    }

    fun stepSpringScroll(dtSec: Float): Int {
        if (!scrollSpringActive) return 0
        val settled = scrollSpringSolver.step(dtSec)
        val floatDelta = scrollSpringSolver.value - lastScrollSolverValue
        lastScrollSolverValue = scrollSpringSolver.value
        subPixelAccumulator.subPixelRemainder = subPixelRemainder
        var intDelta = subPixelAccumulator.accumulate(floatDelta)
        subPixelRemainder = subPixelAccumulator.subPixelRemainder
        if (settled) {
            intDelta += subPixelAccumulator.flushRemainder()
            subPixelRemainder = subPixelAccumulator.subPixelRemainder
            scrollSpringActive = false
            lastScrollSolverValue = 0f
            scrollSpringSolver.value = 0f
            scrollSpringSolver.target = 0f
            scrollSpringSolver.velocity = 0f
        }
        return intDelta
    }

    fun isScrollSpringActive(): Boolean = scrollSpringActive

    private fun computeInterpolatedPlaybackPosMs(frameTimeNanos: Long): Long {
        if (!playbackActive || lastPlaybackAnchorNanos <= 0L) return lastPlaybackPositionMs
        val deltaMs = ((frameTimeNanos - lastPlaybackAnchorNanos).coerceIn(0L, 450_000_000L)) / 1_000_000L
        return lastPlaybackPositionMs + deltaMs
    }

    private fun shouldKeepWordSweepRunning(reducedMotion: Boolean): Boolean {
        return !reducedMotion &&
            hostActive &&
            playbackActive &&
            lyricsModeActive &&
            synced &&
            !selectionMode &&
            LyricMotionSpec.WORD_SWEEP_ENABLED &&
            (activeIndex in lines.indices || interludeActive)
    }

    private fun ensureChoreographerIfNeeded() {
        val rv = attachedRecyclerView ?: return
        if (!hostActive || choreographerRunning) return
        val reducedMotion = SpringAnimatorHelper.isReducedMotionEnabled(rv.context)
        var anyActive = scrollSpringActive || shouldKeepWordSweepRunning(reducedMotion)
        if (!anyActive) {
            for (i in 0 until rv.childCount) {
                val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? LyricViewHolder ?: continue
                if (holder.focusActive || holder.microLagActive || holder.microLagDelaySec > 0f || holder.rowView.tapGlowAlpha > 0f) {
                    anyActive = true
                    break
                }
            }
        }
        if (anyActive) {
            choreographerRunning = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun stopChoreographer() {
        if (choreographerRunning) {
            runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
            choreographerRunning = false
            lastFrameNanos = 0L
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        ensureChoreographerIfNeeded()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        stopChoreographer()
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun setPalette(palette: CoverPalette) {
        setAccentColor(palette.accentGlow)
    }

    fun setAccentColor(accentGlow: Int) {
        accentColorRaw = accentGlow
        accentTranslationFocusedColor = computeSoftAccentColor(accentGlow, focused = true)
        accentTranslationMutedColor = computeSoftAccentColor(accentGlow, focused = false)
        if (lines.isNotEmpty()) {
            notifyItemRangeChanged(0, lines.size, PAYLOAD_STATE)
        }
    }

    fun submitLyrics(parsed: ParsedLyrics) {
        lines = parsed.lines
        synced = parsed.isSynced
        activeIndex = -1
        pendingScrollTargetPosition = -1
        pendingScrollDeltaPx = 0f
        subPixelAccumulator.reset()
        subPixelRemainder = 0f
        scrollSpringActive = false
        lastScrollSolverValue = 0f
        selectedIndices.clear()
        selectionMode = false
        onSelectionChanged?.invoke(emptyList())
        notifyDataSetChanged()
    }

    fun clear() {
        lines = emptyList()
        synced = false
        activeIndex = -1
        pendingScrollTargetPosition = -1
        pendingScrollDeltaPx = 0f
        subPixelAccumulator.reset()
        subPixelRemainder = 0f
        scrollSpringActive = false
        lastScrollSolverValue = 0f
        selectedIndices.clear()
        selectionMode = false
        onSelectionChanged?.invoke(emptyList())
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (!selectionMode && selectedIndices.isEmpty()) return
        selectionMode = false
        selectedIndices.clear()
        onSelectionChanged?.invoke(emptyList())
        notifyDataSetChanged()
    }

    fun isSelectionActive(): Boolean = selectionMode

    fun getSelectedLines(): List<LyricLine> = selectedIndices.sorted().mapNotNull { lines.getOrNull(it) }

    fun isSynced(): Boolean = synced

    fun getActiveIndex(): Int = activeIndex

    fun getLines(): List<LyricLine> = lines

    fun setHighlightFraction(fraction: Float) {
        highlightFraction = fraction.coerceIn(0.20f, 0.60f)
    }

    fun getHighlightFraction(): Float = highlightFraction

    fun setViewportMargins(topPx: Int, bottomPx: Int, defaultFadeLengthPx: Int) {
        topSafeMarginPx = topPx.coerceAtLeast(0)
        bottomSafeMarginPx = bottomPx.coerceAtLeast(0)
        defaultFadePx = defaultFadeLengthPx.coerceAtLeast(0)
    }

    fun cancelScrollAnimation(recyclerView: RecyclerView) {
        SpringAnimatorHelper.cancelSpring(recyclerView, CHANNEL_SCROLL)
        scrollSpringActive = false
        pendingScrollTargetPosition = -1
        pendingScrollDeltaPx = 0f
        lastScrollSolverValue = 0f
        scrollSpringSolver.value = 0f
        scrollSpringSolver.target = 0f
        scrollSpringSolver.velocity = 0f
        subPixelAccumulator.reset()
        subPixelRemainder = 0f
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? LyricViewHolder ?: continue
            holder.microLagActive = false
            holder.microLagDelaySec = 0f
            holder.rowView.translationY = 0f
        }
    }

    fun snapToInitialAnchor(recyclerView: RecyclerView) {
        cancelScrollAnimation(recyclerView)
        if (lines.isEmpty() || recyclerView.height <= 0) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val idx = LyricWindowMath.resolveInitialAnchorIndex(lines.size, activeIndex)
        if (idx in lines.indices) {
            layoutManager.scrollToPositionWithOffset(idx, 0)
        }
    }

    fun setActiveIndex(
        newIndex: Int,
        recyclerView: RecyclerView?,
        autoScroll: Boolean
    ) {
        if (!synced || lines.isEmpty()) return
        val clamped = if (newIndex in lines.indices) newIndex else -1
        if (clamped == activeIndex) return

        val prevIndex = activeIndex
        activeIndex = clamped

        if (recyclerView != null && autoScroll && clamped in lines.indices) {
            scrollToActiveLine(recyclerView, clamped, animated = true)
        }

        if (recyclerView != null) {
            updateVisibleHoldersInPlace(
                recyclerView = recyclerView,
                animateFocus = true,
                applyMicroLagWave = autoScroll && prevIndex >= 0 && clamped > prevIndex
            )
            ensureChoreographerIfNeeded()
        }
    }

    private fun updateVisibleHoldersInPlace(
        recyclerView: RecyclerView,
        animateFocus: Boolean,
        applyMicroLagWave: Boolean = false
    ) {
        val density = recyclerView.resources.displayMetrics.density
        val reducedMotion = SpringAnimatorHelper.isReducedMotionEnabled(recyclerView.context)
        val scrollDeltaForLag = pendingScrollDeltaPx
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val holder = recyclerView.getChildViewHolder(child) as? LyricViewHolder ?: continue
            val pos = holder.bindingAdapterPosition
            if (pos in lines.indices) {
                bindVisualState(holder, pos, animateFocus = animateFocus)
                if (applyMicroLagWave && !reducedMotion && pos > activeIndex && scrollDeltaForLag > 0f) {
                    val lagPx = LyricMotionMath.computeInitialMicroLagY(
                        rowIndex = pos,
                        activeIndex = activeIndex,
                        scrollDeltaPx = scrollDeltaForLag,
                        density = density,
                        reducedMotion = false
                    )
                    if (lagPx > 0.5f) {
                        holder.microLagDelaySec = LyricMotionMath.computeStaggerDelayMs(pos, activeIndex) / 1000f
                        holder.microLagSolver.value = lagPx
                        holder.microLagSolver.target = 0f
                        holder.microLagSolver.velocity = 0f
                        holder.microLagActive = true
                        holder.rowView.translationY = lagPx
                    }
                }
            }
        }
    }

    fun scrollToActiveLine(
        recyclerView: RecyclerView,
        position: Int,
        animated: Boolean = true
    ) {
        if (position !in lines.indices) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val activeView = layoutManager.findViewByPosition(position)
        val itemHeight = activeView?.height ?: (recyclerView.resources.displayMetrics.density * 64f).toInt()
        val viewportHeight = recyclerView.height

        if (defaultFadePx > 0 && viewportHeight > 0) {
            val adaptiveFade = LyricWindowMath.computeAdaptiveFadeLength(
                viewportHeight = viewportHeight,
                activeItemHeight = itemHeight,
                defaultFadePx = defaultFadePx
            )
            recyclerView.setFadingEdgeLength(adaptiveFade)
        }

        if (!animated || SpringAnimatorHelper.isReducedMotionEnabled(recyclerView.context)) {
            cancelScrollAnimation(recyclerView)
            layoutManager.scrollToPositionWithOffset(position, 0)
            return
        }

        if (activeView == null || viewportHeight <= 0) {
            cancelScrollAnimation(recyclerView)
            if (viewportHeight > 0) {
                layoutManager.scrollToPositionWithOffset(position, 0)
            }
            return
        }

        val remainingDy = activeView.top - recyclerView.paddingTop
        if (remainingDy == 0) {
            cancelScrollAnimation(recyclerView)
            return
        }

        pendingScrollTargetPosition = position
        pendingScrollDeltaPx = remainingDy.toFloat()
        startSpringScrollTarget(remainingDy.toFloat())
        ensureChoreographerIfNeeded()
    }

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val density = parent.resources.displayMetrics.density
        val hPad = (24f * density).toInt()
        val vPad = (10f * density).toInt()

        val row = LyricRowLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            background = null
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            pivotX = hPad.toFloat()
        }

        val topDots = InterludeDotsView(parent.context).apply {
            setPadding(0, 0, 0, (10f * density).toInt())
            visibility = View.GONE
        }
        val mainTv = SweepLyricTextView(parent.context).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_PRIMARY_SP)
            typeface = TYPEFACE_BOLD
            setLineSpacing(2f * density, 1.14f)
        }
        val romanizedTv = TextView(parent.context).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_ROMANIZED_SP)
            typeface = TYPEFACE_MEDIUM
            setPadding(0, (3f * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        val subTv = TextView(parent.context).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_TRANSLATION_SP)
            typeface = TYPEFACE_MEDIUM
            setPadding(0, (3f * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        val bottomDots = InterludeDotsView(parent.context).apply {
            setPadding(0, (10f * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        row.addView(topDots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(mainTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(romanizedTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(subTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(bottomDots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val holder = LyricViewHolder(row, topDots, mainTv, romanizedTv, subTv, bottomDots)
        row.setOnClickListener { view ->
            val pos = holder.bindingAdapterPosition
            if (pos !in lines.indices) return@setOnClickListener
            if (selectionMode) {
                toggleSelection(pos)
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            } else {
                if (synced) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (!SpringAnimatorHelper.isReducedMotionEnabled(view.context)) {
                    row.triggerTapIllumination()
                    ensureChoreographerIfNeeded()
                }
                onLineTap(lines[pos], pos)
            }
        }
        row.setOnLongClickListener { view ->
            val pos = holder.bindingAdapterPosition
            if (pos !in lines.indices) return@setOnLongClickListener false
            if (!selectionMode) {
                selectionMode = true
                selectedIndices.clear()
            }
            toggleSelection(pos)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            true
        }
        return holder
    }

    private fun toggleSelection(pos: Int) {
        if (selectedIndices.contains(pos)) {
            selectedIndices.remove(pos)
            if (selectedIndices.isEmpty()) selectionMode = false
        } else if (selectedIndices.size < MAX_SELECTED_QUOTE_LINES) {
            selectedIndices.add(pos)
        }
        onSelectionChanged?.invoke(getSelectedLines())
        notifyDataSetChanged()
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val line = lines[position]
        holder.mainText.text = line.primaryText.ifBlank { "\u266A" }
        holder.mainText.setSweepProgress(active = false, charProgress = 0f)
        holder.microLagActive = false
        holder.microLagDelaySec = 0f
        holder.rowView.translationY = 0f
        holder.rowView.updateTapGlow(0f)

        val rom = line.romanizedText
        if (rom.isNullOrBlank()) {
            holder.romanizedText.visibility = android.view.View.GONE
        } else {
            holder.romanizedText.text = rom
            holder.romanizedText.visibility = android.view.View.VISIBLE
        }

        val tr = line.translatedText
        if (tr.isNullOrBlank()) {
            holder.subText.visibility = View.GONE
        } else {
            holder.subText.text = tr
            holder.subText.visibility = View.VISIBLE
        }
        bindInterludeForHolder(holder, position)
        bindVisualState(holder, position, animateFocus = false)
    }

    override fun onBindViewHolder(
        holder: LyricViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        bindVisualState(holder, position, animateFocus = true)
    }

    override fun onViewRecycled(holder: LyricViewHolder) {
        holder.focusActive = false
        holder.microLagActive = false
        holder.microLagDelaySec = 0f
        holder.rowView.translationY = 0f
        holder.rowView.updateTapGlow(0f)
        holder.mainText.setSweepProgress(active = false, charProgress = 0f)
        super.onViewRecycled(holder)
    }

    private fun bindVisualState(holder: LyricViewHolder, position: Int, animateFocus: Boolean) {
        val row = holder.rowView
        if (row.background != null) row.background = null

        val isSelected = selectedIndices.contains(position)
        val isFocused: Boolean
        val unfocusedMainColor: Int
        val focusedMainColor: Int

        if (selectionMode) {
            isFocused = isSelected
            unfocusedMainColor = COLOR_PAST
            focusedMainColor = COLOR_ACTIVE
        } else if (!synced) {
            isFocused = true
            unfocusedMainColor = COLOR_STATIC
            focusedMainColor = COLOR_STATIC
        } else if (position == activeIndex) {
            isFocused = true
            unfocusedMainColor = COLOR_UPCOMING
            focusedMainColor = COLOR_ACTIVE
        } else if (activeIndex >= 0 && position < activeIndex) {
            isFocused = false
            unfocusedMainColor = COLOR_PAST
            focusedMainColor = COLOR_ACTIVE
        } else {
            isFocused = false
            unfocusedMainColor = COLOR_UPCOMING
            focusedMainColor = COLOR_ACTIVE
        }

        holder.unfocusedMainColor = unfocusedMainColor
        holder.focusedMainColor = focusedMainColor

        val targetFocus = if (isFocused) 1f else 0f
        holder.targetFocus = targetFocus
        if (animateFocus && !SpringAnimatorHelper.isReducedMotionEnabled(row.context)) {
            holder.focusSolver.value = holder.focusProgress
            holder.focusSolver.target = targetFocus
            holder.focusSolver.stiffness = LyricMotionSpec.SPRING_STIFFNESS
            holder.focusSolver.dampingRatio = LyricMotionSpec.SPRING_DAMPING_RATIO
            holder.focusActive = !holder.focusSolver.isSettled()
            if (!holder.focusActive) {
                applyFocusVisuals(holder, position, targetFocus)
            }
        } else {
            holder.focusActive = false
            holder.focusSolver.value = targetFocus
            holder.focusSolver.target = targetFocus
            holder.focusSolver.velocity = 0f
            applyFocusVisuals(holder, position, targetFocus)
        }
    }

    private fun applyFocusVisuals(holder: LyricViewHolder, position: Int, focusProgress: Float) {
        val row = holder.rowView
        val clamped = focusProgress.coerceIn(0f, 1f)
        holder.focusProgress = clamped
        row.pivotX = row.paddingLeft.toFloat()
        if (row.height > 0) {
            row.pivotY = row.height * 0.5f
        }
        val scale = LyricMotionMath.computeFocusScale(clamped)
        row.scaleX = scale
        row.scaleY = scale
        row.alpha = LyricMotionMath.computeFocusAlpha(clamped)

        val mainColor = LyricMotionMath.interpolateColor(holder.unfocusedMainColor, holder.focusedMainColor, clamped)
        val romColor = LyricMotionMath.interpolateColor(COLOR_ROM_INACTIVE, COLOR_ROM_ACTIVE, clamped)
        val subColor = LyricMotionMath.interpolateColor(accentTranslationMutedColor, accentTranslationFocusedColor, clamped)
        if (holder.mainText.currentTextColor != mainColor) holder.mainText.setTextColor(mainColor)
        if (holder.romanizedText.currentTextColor != romColor) holder.romanizedText.setTextColor(romColor)
        if (holder.subText.currentTextColor != subColor) holder.subText.setTextColor(subColor)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dist = if (activeIndex >= 0) abs(position - activeIndex) else -1
            val blurIdx = LyricMotionMath.selectBlurPoolIndex(clamped, dist, synced, selectionMode)
            if (holder.appliedBlurIndex != blurIdx) {
                holder.appliedBlurIndex = blurIdx
                row.setRenderEffect(BLUR_POOL[blurIdx])
            }
        }
    }

    class LyricRowLayout(context: Context) : LinearLayout(context) {
        var lastTouchX: Float = 0f
        var lastTouchY: Float = 0f
        var tapGlowAlpha: Float = 0f
            private set

        private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tapMatrix = Matrix()

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchX = ev.x
                lastTouchY = ev.y
            }
            return false
        }

        fun triggerTapIllumination() {
            if (lastTouchX <= 0f && lastTouchY <= 0f && width > 0) {
                lastTouchX = width * 0.25f
                lastTouchY = height * 0.5f
            }
            tapGlowAlpha = 1f
            invalidate()
        }

        fun updateTapGlow(alpha: Float) {
            val clamped = alpha.coerceIn(0f, 1f)
            if (tapGlowAlpha != clamped) {
                tapGlowAlpha = clamped
                invalidate()
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val a = tapGlowAlpha
            if (a <= 0.005f || width <= 0 || height <= 0) return
            val radius = (width * 0.42f).coerceAtLeast(120f)
            tapMatrix.setScale(radius, radius)
            tapMatrix.postTranslate(lastTouchX, lastTouchY)
            TAP_GLOW_SHADER.setLocalMatrix(tapMatrix)
            tapPaint.shader = TAP_GLOW_SHADER
            tapPaint.alpha = (a * 96f).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(lastTouchX, lastTouchY, radius, tapPaint)
        }
    }

    class SweepLyricTextView(context: Context) : TextView(context) {
        private var sweepActive: Boolean = false
        private var sweepCharProgress: Float = 0f
        private val featherWidthPx: Float = 28f * resources.displayMetrics.density
        private val sweepMatrix = Matrix()
        private val sweepShader = LinearGradient(
            0f,
            0f,
            1f,
            0f,
            intArrayOf(COLOR_ACTIVE, COLOR_ACTIVE, 0x00FFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        fun setSweepProgress(active: Boolean, charProgress: Float) {
            val normalized = charProgress.coerceAtLeast(0f)
            if (sweepActive == active && abs(sweepCharProgress - normalized) < 0.02f) return
            sweepActive = active
            sweepCharProgress = normalized
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val textLayout = layout
            val textLen = text?.length ?: 0
            if (!sweepActive || !LyricMotionSpec.WORD_SWEEP_ENABLED || textLayout == null || textLen <= 0) {
                if (paint.shader != null) {
                    paint.shader = null
                }
                super.onDraw(canvas)
                return
            }

            val tp = paint
            val prevColor = tp.color
            val prevShader = tp.shader
            val dx = compoundPaddingLeft.toFloat()
            val dy = extendedPaddingTop.toFloat()
            val contentWidth = (width - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1).toFloat()

            val rootSave = canvas.save()
            canvas.translate(dx, dy)

            tp.shader = null
            tp.color = COLOR_SWEEP_BASE
            textLayout.draw(canvas)

            val progress = sweepCharProgress.coerceIn(0f, textLen.toFloat())
            val lineCount = textLayout.lineCount
            for (lineIdx in 0 until lineCount) {
                val lineStart = textLayout.getLineStart(lineIdx)
                val lineEnd = textLayout.getLineVisibleEnd(lineIdx)
                if (progress <= lineStart.toFloat()) break

                val lineTop = textLayout.getLineTop(lineIdx).toFloat()
                val lineBottom = textLayout.getLineBottom(lineIdx).toFloat()
                val lineSave = canvas.save()
                canvas.clipRect(0f, lineTop, contentWidth, lineBottom)

                if (progress >= lineEnd.toFloat()) {
                    tp.shader = null
                    tp.color = COLOR_ACTIVE
                    textLayout.draw(canvas)
                } else {
                    val idxFloor = progress.toInt().coerceIn(lineStart, (lineEnd - 1).coerceAtLeast(lineStart))
                    val frac = (progress - idxFloor).coerceIn(0f, 1f)
                    val x0 = textLayout.getPrimaryHorizontal(idxFloor)
                    val x1 = if (idxFloor + 1 <= lineEnd) {
                        textLayout.getPrimaryHorizontal(idxFloor + 1)
                    } else {
                        textLayout.getLineRight(lineIdx)
                    }
                    val headX = (x0 + (x1 - x0) * frac).coerceAtLeast(0f)
                    sweepMatrix.setScale(featherWidthPx, 1f)
                    sweepMatrix.postTranslate(headX - featherWidthPx * 0.55f, 0f)
                    sweepShader.setLocalMatrix(sweepMatrix)
                    tp.color = COLOR_ACTIVE
                    tp.shader = sweepShader
                    textLayout.draw(canvas)
                }
                canvas.restoreToCount(lineSave)
            }

            tp.shader = prevShader
            tp.color = prevColor
            canvas.restoreToCount(rootSave)
        }
    }

    class InterludeDotsView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val dotRadiusPx = (InterludeDotsMath.DOT_DIAMETER_DP * 0.5f) * density
        private val centerStepPx = (InterludeDotsMath.DOT_DIAMETER_DP + InterludeDotsMath.DOT_GAP_DP) * density
        private val extraPadPx = 2f * density

        private var progress: Float = 0f
        private var pulseScale: Float = 1f
        private var accentColor: Int = CoverPaletteExtractor.defaultPalette().accentGlow

        private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x29FFFFFF
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xE6FFFFFF.toInt()
        }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (0.9f * density).coerceAtLeast(1f)
            color = 0x55FFFFFF
        }

        fun setInterludeState(
            visible: Boolean,
            progress: Float,
            pulseScale: Float,
            accentColor: Int
        ) {
            val targetVis = if (visible) VISIBLE else GONE
            if (visibility != targetVis) {
                visibility = targetVis
            }
            if (!visible) return
            val clampedP = progress.coerceIn(0f, 1f)
            val clampedScale = pulseScale.coerceIn(0.85f, 1.15f)
            if (abs(this.progress - clampedP) < 0.004f &&
                abs(this.pulseScale - clampedScale) < 0.003f &&
                this.accentColor == accentColor
            ) {
                return
            }
            this.progress = clampedP
            this.pulseScale = clampedScale
            this.accentColor = accentColor
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val contentW = (InterludeDotsMath.DOT_COUNT - 1) * centerStepPx + dotRadiusPx * 2f + extraPadPx * 2f
            val contentH = dotRadiusPx * 2f + extraPadPx * 2f
            val w = (paddingLeft + paddingRight + contentW).roundToInt()
            val h = (paddingTop + paddingBottom + contentH).roundToInt()
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            if (visibility != VISIBLE) return
            val baseX = paddingLeft + dotRadiusPx + extraPadPx
            val cy = paddingTop + (height - paddingTop - paddingBottom) * 0.5f
            val r = dotRadiusPx * pulseScale
            val accentRgb = accentColor and 0x00FFFFFF
            for (i in 0 until InterludeDotsMath.DOT_COUNT) {
                val cx = baseX + i * centerStepPx
                canvas.drawCircle(cx, cy, r, shellPaint)
                val fillAlpha = InterludeDotsMath.computeDotFillAlpha(i, progress)
                val alphaByte = (fillAlpha * 238f).roundToInt().coerceIn(0, 255)
                fillPaint.color = (alphaByte shl 24) or accentRgb
                val innerR = r * (0.68f + 0.32f * fillAlpha)
                canvas.drawCircle(cx, cy, innerR, fillPaint)
                canvas.drawCircle(cx, cy, r, rimPaint)
            }
        }
    }

    class LyricViewHolder(
        val rowView: LyricRowLayout,
        val topInterludeDots: InterludeDotsView,
        val mainText: SweepLyricTextView,
        val romanizedText: TextView,
        val subText: TextView,
        val bottomInterludeDots: InterludeDotsView
    ) : RecyclerView.ViewHolder(rowView) {
        var appliedBlurIndex: Int = -1
        var focusProgress: Float = 0f
        var targetFocus: Float = 0f
        var focusActive: Boolean = false
        val focusSolver = SpringAnimatorHelper.SpringSolver(
            stiffness = LyricMotionSpec.SPRING_STIFFNESS,
            dampingRatio = LyricMotionSpec.SPRING_DAMPING_RATIO
        )
        var microLagDelaySec: Float = 0f
        var microLagActive: Boolean = false
        val microLagSolver = SpringAnimatorHelper.SpringSolver(
            stiffness = LyricMotionSpec.SPRING_STIFFNESS,
            dampingRatio = LyricMotionSpec.SPRING_DAMPING_RATIO,
            valueThreshold = 0.2f,
            velocityThreshold = 2f
        )
        var unfocusedMainColor: Int = COLOR_UPCOMING
        var focusedMainColor: Int = COLOR_ACTIVE
    }

    internal companion object {
        private const val PAYLOAD_STATE = 1
        private const val MAX_SELECTED_QUOTE_LINES = 4
        private const val CHANNEL_SCROLL = 10

        internal const val SCROLL_DURATION_MS = SpringAnimatorHelper.LYRIC_SCROLL_DURATION_MS
        internal const val SCALE_ANIM_MS = SpringAnimatorHelper.LYRIC_SCALE_DURATION_MS

        private const val SIZE_PRIMARY_SP = 21f
        private const val SIZE_ROMANIZED_SP = 14.5f
        private const val SIZE_TRANSLATION_SP = 14f

        private const val COLOR_ACTIVE = 0xFFFFFFFF.toInt()
        private const val COLOR_SWEEP_BASE = 0x8CFFFFFF.toInt()
        private const val COLOR_UPCOMING = 0xCCFFFFFF.toInt()
        private const val COLOR_PAST = 0x99FFFFFF.toInt()
        private const val COLOR_STATIC = 0xE6FFFFFF.toInt()
        private const val COLOR_ROM_ACTIVE = 0xCCFFFFFF.toInt()
        private const val COLOR_ROM_INACTIVE = 0x8AFFFFFF.toInt()

        private val TYPEFACE_BOLD: Typeface? by lazy { Typeface.create("sans-serif", Typeface.BOLD) }
        private val TYPEFACE_MEDIUM: Typeface? by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

        private val TAP_GLOW_SHADER: RadialGradient by lazy {
            RadialGradient(
                0f,
                0f,
                1f,
                intArrayOf(0x55FFFFFF, 0x22FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        private val BLUR_POOL: Array<RenderEffect?> = Array(LyricMotionMath.BLUR_RADII.size) { idx ->
            val r = LyricMotionMath.BLUR_RADII[idx]
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && r > 0f) {
                RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            } else {
                null
            }
        }

        private fun computeSoftAccentColor(accentGlow: Int, focused: Boolean): Int {
            val a = if (focused) 0xEB else 0xA8
            val r = (((accentGlow ushr 16) and 0xFF) * 0.65f + 255f * 0.35f).roundToInt().coerceIn(0, 255)
            val g = (((accentGlow ushr 8) and 0xFF) * 0.65f + 255f * 0.35f).roundToInt().coerceIn(0, 255)
            val b = ((accentGlow and 0xFF) * 0.65f + 255f * 0.35f).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
