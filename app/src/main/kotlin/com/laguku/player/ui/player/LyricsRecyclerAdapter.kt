package com.laguku.player.ui.player

import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.model.LyricLine
import com.laguku.player.model.ParsedLyrics
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.motion.SpringAnimatorHelper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Synced & static 1-3 layer lyrics adapter with:
 * - Unclipped multi-line & 3-layer support (original + romanization + translation)
 * - Continuous Choreographer spring scroll driver targeting [highlightFraction] (default 36%)
 * - Per-item spring focusProgress (0..1) interpolating scale (0.94 -> 1.0), opacity (0.42 -> 1.0),
 *   and pre-allocated RenderEffect blur pool (0f, 1.5f, 3f, 5f, 7f on API 31+)
 * - Zero object allocations inside [onBindViewHolder] and per-frame callbacks
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
        val BLUR_RADII = floatArrayOf(0f, 1.5f, 3f, 5f, 7f)

        fun computeFocusScale(focusProgress: Float): Float =
            0.94f + 0.06f * focusProgress.coerceIn(0f, 1f)

        fun computeFocusAlpha(focusProgress: Float): Float =
            0.42f + 0.58f * focusProgress.coerceIn(0f, 1f)

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

    private var lines: List<LyricLine> = emptyList()
    private var synced: Boolean = false
    private var activeIndex: Int = -1
    private var highlightFraction: Float = PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL
    private var topSafeMarginPx: Int = 0
    private var bottomSafeMarginPx: Int = 0
    private var defaultFadePx: Int = 0

    private var accentTranslationFocusedColor: Int = computeSoftAccentColor(
        CoverPaletteExtractor.defaultPalette().accentGlow,
        focused = true
    )
    private var accentTranslationMutedColor: Int = computeSoftAccentColor(
        CoverPaletteExtractor.defaultPalette().accentGlow,
        focused = false
    )

    private var appliedSpringScrollPx: Int = 0

    private val selectedIndices = LinkedHashSet<Int>()
    private var selectionMode: Boolean = false

    fun setAccentColor(accentGlow: Int) {
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
        appliedSpringScrollPx = 0
        selectedIndices.clear()
        selectionMode = false
        onSelectionChanged?.invoke(emptyList())
        notifyDataSetChanged()
    }

    fun clear() {
        lines = emptyList()
        synced = false
        activeIndex = -1
        appliedSpringScrollPx = 0
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
        appliedSpringScrollPx = 0
    }

    fun setActiveIndex(
        newIndex: Int,
        recyclerView: RecyclerView?,
        autoScroll: Boolean
    ) {
        if (!synced || lines.isEmpty()) return
        val clamped = if (newIndex in lines.indices) newIndex else -1
        if (clamped == activeIndex) return

        val oldIndex = activeIndex
        activeIndex = clamped

        val minIdx = when {
            oldIndex in lines.indices && clamped in lines.indices -> minOf(oldIndex, clamped)
            oldIndex in lines.indices -> oldIndex
            else -> clamped
        }
        val maxIdx = when {
            oldIndex in lines.indices && clamped in lines.indices -> maxOf(oldIndex, clamped)
            oldIndex in lines.indices -> oldIndex
            else -> clamped
        }
        val rangeStart = (minIdx - 2).coerceAtLeast(0)
        val rangeEnd = (maxIdx + 2).coerceAtMost(lines.lastIndex)
        if (rangeStart <= rangeEnd) {
            notifyItemRangeChanged(rangeStart, rangeEnd - rangeStart + 1, PAYLOAD_STATE)
        }

        if (autoScroll && clamped in lines.indices && recyclerView != null) {
            scrollToActiveLine(recyclerView, clamped, animated = true)
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

        val targetTopOffset = LyricWindowMath.computeScrollTopOffset(
            viewportHeight = viewportHeight,
            itemHeight = itemHeight,
            anchorFraction = highlightFraction,
            topSafeMargin = topSafeMarginPx,
            bottomSafeMargin = bottomSafeMarginPx
        )

        if (!animated || SpringAnimatorHelper.isReducedMotionEnabled(recyclerView.context)) {
            cancelScrollAnimation(recyclerView)
            layoutManager.scrollToPositionWithOffset(position, targetTopOffset)
            return
        }

        if (activeView == null || viewportHeight <= 0) {
            cancelScrollAnimation(recyclerView)
            layoutManager.scrollToPositionWithOffset(position, targetTopOffset)
            return
        }

        val remainingDy = activeView.top - targetTopOffset
        if (abs(remainingDy) <= 1) {
            cancelScrollAnimation(recyclerView)
            return
        }

        val currentSpringVal = SpringAnimatorHelper.getSpringValue(recyclerView, CHANNEL_SCROLL)
        val startVal = currentSpringVal ?: 0f
        if (currentSpringVal == null) {
            appliedSpringScrollPx = 0
        }
        val targetVal = startVal + remainingDy.toFloat()

        SpringAnimatorHelper.animateSpring(
            key = recyclerView,
            start = startVal,
            target = targetVal,
            stiffness = 220f,
            dampingRatio = 0.92f,
            valueThreshold = 0.5f,
            channel = CHANNEL_SCROLL,
            onUpdate = { v ->
                val nextPx = v.roundToInt()
                val delta = nextPx - appliedSpringScrollPx
                if (delta != 0) {
                    recyclerView.scrollBy(0, delta)
                    appliedSpringScrollPx = nextPx
                }
            },
            onEnd = {
                appliedSpringScrollPx = 0
            }
        )
    }

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val density = parent.resources.displayMetrics.density
        val hPad = (24f * density).toInt()
        val vPad = (10f * density).toInt()

        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            pivotX = hPad.toFloat()
        }

        val mainTv = TextView(parent.context).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setLineSpacing(2f * density, 1.14f)
        }
        val romanizedTv = TextView(parent.context).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_ROMANIZED_SP)
            typeface = TYPEFACE_MEDIUM
            setPadding(0, (3f * density).toInt(), 0, 0)
            visibility = android.view.View.GONE
        }
        val subTv = TextView(parent.context).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_TRANSLATION_SP)
            typeface = TYPEFACE_MEDIUM
            setPadding(0, (3f * density).toInt(), 0, 0)
            visibility = android.view.View.GONE
        }
        row.addView(mainTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(romanizedTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(subTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val holder = LyricViewHolder(row, mainTv, romanizedTv, subTv)
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

        val rom = line.romanizedText
        if (rom.isNullOrBlank()) {
            holder.romanizedText.visibility = android.view.View.GONE
        } else {
            holder.romanizedText.text = rom
            holder.romanizedText.visibility = android.view.View.VISIBLE
        }

        val tr = line.translatedText
        if (tr.isNullOrBlank()) {
            holder.subText.visibility = android.view.View.GONE
        } else {
            holder.subText.text = tr
            holder.subText.visibility = android.view.View.VISIBLE
        }
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
        SpringAnimatorHelper.cancelSpring(holder.rowView, CHANNEL_FOCUS)
        super.onViewRecycled(holder)
    }

    private fun bindVisualState(holder: LyricViewHolder, position: Int, animateFocus: Boolean) {
        val row = holder.rowView
        val tv = holder.mainText
        val rom = holder.romanizedText
        val sub = holder.subText

        val isSelected = selectedIndices.contains(position)
        val isFocused: Boolean
        val targetColor: Int
        val romColor: Int
        val subColor: Int
        val targetSizeSp: Float
        val targetTypeface: Typeface

        if (selectionMode) {
            isFocused = isSelected
            targetColor = if (isSelected) COLOR_ACTIVE else COLOR_PAST
            romColor = if (isSelected) COLOR_ROM_ACTIVE else COLOR_ROM_INACTIVE
            subColor = if (isSelected) accentTranslationFocusedColor else accentTranslationMutedColor
            targetSizeSp = if (isSelected) SIZE_ACTIVE_SP else SIZE_INACTIVE_SP
            targetTypeface = if (isSelected) TYPEFACE_BOLD else TYPEFACE_MEDIUM
            row.setBackgroundColor(if (isSelected) COLOR_SELECTED_BG else 0)
        } else if (!synced) {
            isFocused = true
            targetColor = COLOR_STATIC
            romColor = COLOR_ROM_ACTIVE
            subColor = accentTranslationFocusedColor
            targetSizeSp = SIZE_INACTIVE_SP
            targetTypeface = TYPEFACE_MEDIUM
            row.setBackgroundColor(0)
        } else if (position == activeIndex) {
            isFocused = true
            targetColor = COLOR_ACTIVE
            romColor = COLOR_ROM_ACTIVE
            subColor = accentTranslationFocusedColor
            targetSizeSp = SIZE_ACTIVE_SP
            targetTypeface = TYPEFACE_BOLD
            row.setBackgroundColor(0)
        } else if (activeIndex >= 0 && position < activeIndex) {
            isFocused = false
            targetColor = COLOR_PAST
            romColor = COLOR_ROM_INACTIVE
            subColor = accentTranslationMutedColor
            targetSizeSp = SIZE_INACTIVE_SP
            targetTypeface = TYPEFACE_MEDIUM
            row.setBackgroundColor(0)
        } else {
            isFocused = false
            targetColor = COLOR_UPCOMING
            romColor = COLOR_ROM_INACTIVE
            subColor = accentTranslationMutedColor
            targetSizeSp = SIZE_INACTIVE_SP
            targetTypeface = TYPEFACE_MEDIUM
            row.setBackgroundColor(0)
        }

        if (tv.currentTextColor != targetColor) tv.setTextColor(targetColor)
        if (rom.currentTextColor != romColor) rom.setTextColor(romColor)
        if (sub.currentTextColor != subColor) sub.setTextColor(subColor)
        if (tv.typeface !== targetTypeface) tv.typeface = targetTypeface
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSizeSp)

        val targetFocus = if (isFocused) 1f else 0f
        if (animateFocus && !SpringAnimatorHelper.isReducedMotionEnabled(row.context)) {
            SpringAnimatorHelper.animateSpring(
                key = row,
                start = holder.focusProgress,
                target = targetFocus,
                stiffness = 340f,
                dampingRatio = 0.86f,
                channel = CHANNEL_FOCUS,
                onUpdate = { p ->
                    applyFocusVisuals(holder, position, p)
                }
            )
        } else {
            SpringAnimatorHelper.cancelSpring(row, CHANNEL_FOCUS)
            applyFocusVisuals(holder, position, targetFocus)
        }
    }

    private fun applyFocusVisuals(holder: LyricViewHolder, position: Int, focusProgress: Float) {
        val row = holder.rowView
        holder.focusProgress = focusProgress
        val scale = LyricMotionMath.computeFocusScale(focusProgress)
        row.scaleX = scale
        row.scaleY = scale
        row.alpha = LyricMotionMath.computeFocusAlpha(focusProgress)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dist = if (activeIndex >= 0) abs(position - activeIndex) else -1
            val blurIdx = LyricMotionMath.selectBlurPoolIndex(focusProgress, dist, synced, selectionMode)
            if (holder.appliedBlurIndex != blurIdx) {
                holder.appliedBlurIndex = blurIdx
                row.setRenderEffect(BLUR_POOL[blurIdx])
            }
        }
    }

    class LyricViewHolder(
        val rowView: LinearLayout,
        val mainText: TextView,
        val romanizedText: TextView,
        val subText: TextView
    ) : RecyclerView.ViewHolder(rowView) {
        var appliedBlurIndex: Int = -1
        var focusProgress: Float = 0f
    }

    internal companion object {
        private const val PAYLOAD_STATE = 1
        private const val MAX_SELECTED_QUOTE_LINES = 4
        private const val CHANNEL_SCROLL = 10
        private const val CHANNEL_FOCUS = 11

        internal const val SCROLL_DURATION_MS = SpringAnimatorHelper.LYRIC_SCROLL_DURATION_MS
        internal const val SCALE_ANIM_MS = SpringAnimatorHelper.LYRIC_SCALE_DURATION_MS

        private const val SIZE_ACTIVE_SP = 22f
        private const val SIZE_INACTIVE_SP = 18f
        private const val SIZE_ROMANIZED_SP = 14.5f
        private const val SIZE_TRANSLATION_SP = 14f

        private const val COLOR_ACTIVE = 0xFFFFFFFF.toInt()
        private const val COLOR_UPCOMING = 0xCCFFFFFF.toInt()
        private const val COLOR_PAST = 0x99FFFFFF.toInt()
        private const val COLOR_STATIC = 0xE6FFFFFF.toInt()
        private const val COLOR_ROM_ACTIVE = 0xCCFFFFFF.toInt()
        private const val COLOR_ROM_INACTIVE = 0x8AFFFFFF.toInt()
        private const val COLOR_SELECTED_BG = 0x22FFFFFF

        private val TYPEFACE_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        private val TYPEFACE_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

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
