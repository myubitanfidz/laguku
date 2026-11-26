package com.laguku.player.ui.player

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.model.LyricLine
import com.laguku.player.model.ParsedLyrics
import com.laguku.player.ui.motion.SpringAnimatorHelper
import kotlin.math.abs

/**
 * Synced & static lyrics adapter with:
 * - Unclipped multi-line support (2-3+ wrapped lines + bilingual sub-line)
 * - Adaptive scroll window & edge fade based on measured active block height
 * - Whole-block Depth Focus Blur (RenderEffect API 31+ / graduated opacity API 26-30)
 * - Long-press 1..4 line quote selection
 * - Zero object allocations inside [onBindViewHolder]
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

    private var lines: List<LyricLine> = emptyList()
    private var synced: Boolean = false
    private var activeIndex: Int = -1
    private var highlightFraction: Float = PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL
    private var topSafeMarginPx: Int = 0
    private var bottomSafeMarginPx: Int = 0
    private var defaultFadePx: Int = 0

    private val selectedIndices = LinkedHashSet<Int>()
    private var selectionMode: Boolean = false

    fun submitLyrics(parsed: ParsedLyrics) {
        lines = parsed.lines
        synced = parsed.isSynced
        activeIndex = -1
        selectedIndices.clear()
        selectionMode = false
        onSelectionChanged?.invoke(emptyList())
        notifyDataSetChanged()
    }

    fun clear() {
        lines = emptyList()
        synced = false
        activeIndex = -1
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
        val itemHeight = activeView?.height ?: (recyclerView.resources.displayMetrics.density * 52f).toInt()
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
            val offset = LyricWindowMath.computeScrollTopOffset(
                viewportHeight = viewportHeight,
                itemHeight = itemHeight,
                anchorFraction = highlightFraction,
                topSafeMargin = topSafeMarginPx,
                bottomSafeMargin = bottomSafeMarginPx
            )
            layoutManager.scrollToPositionWithOffset(position, offset)
            return
        }

        val scroller = AdaptiveLyricScroller(
            context = recyclerView.context,
            anchorFraction = highlightFraction,
            topSafeMargin = topSafeMarginPx,
            bottomSafeMargin = bottomSafeMarginPx
        )
        scroller.targetPosition = position
        layoutManager.startSmoothScroll(scroller)
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
        val subTv = TextView(parent.context).apply {
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SIZE_TRANSLATION_SP)
            typeface = TYPEFACE_MEDIUM
            setPadding(0, (3f * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        row.addView(mainTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(subTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val holder = LyricViewHolder(row, mainTv, subTv)
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
        holder.mainText.text = line.text.ifBlank { "\u266A" }
        val tr = line.translation
        if (tr.isNullOrBlank()) {
            holder.subText.visibility = View.GONE
        } else {
            holder.subText.text = tr
            holder.subText.visibility = View.VISIBLE
        }
        bindVisualState(holder, position, animateScale = false)
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
        bindVisualState(holder, position, animateScale = true)
    }

    private fun bindVisualState(holder: LyricViewHolder, position: Int, animateScale: Boolean) {
        val row = holder.rowView
        val tv = holder.mainText
        val sub = holder.subText

        val isSelected = selectedIndices.contains(position)
        val isActive: Boolean
        val targetColor: Int
        val subColor: Int
        val targetSizeSp: Float
        val targetTypeface: Typeface

        if (selectionMode) {
            isActive = isSelected
            targetColor = if (isSelected) COLOR_ACTIVE else COLOR_PAST
            subColor = if (isSelected) COLOR_SUB_ACTIVE else COLOR_SUB_INACTIVE
            targetSizeSp = if (isSelected) SIZE_ACTIVE_SP else SIZE_INACTIVE_SP
            targetTypeface = if (isSelected) TYPEFACE_BOLD else TYPEFACE_MEDIUM
            row.setBackgroundColor(if (isSelected) COLOR_SELECTED_BG else 0)
        } else if (!synced) {
            isActive = false
            targetColor = COLOR_STATIC
            subColor = COLOR_SUB_ACTIVE
            targetSizeSp = SIZE_INACTIVE_SP
            targetTypeface = TYPEFACE_MEDIUM
            row.setBackgroundColor(0)
        } else if (position == activeIndex) {
            isActive = true
            targetColor = COLOR_ACTIVE
            subColor = COLOR_SUB_ACTIVE
            targetSizeSp = SIZE_ACTIVE_SP
            targetTypeface = TYPEFACE_BOLD
            row.setBackgroundColor(0)
        } else if (activeIndex >= 0 && position < activeIndex) {
            isActive = false
            targetColor = COLOR_PAST
            subColor = COLOR_SUB_INACTIVE
            targetSizeSp = SIZE_INACTIVE_SP
            targetTypeface = TYPEFACE_MEDIUM
            row.setBackgroundColor(0)
        } else {
            isActive = false
            targetColor = COLOR_UPCOMING
            subColor = COLOR_SUB_INACTIVE
            targetSizeSp = SIZE_INACTIVE_SP
            targetTypeface = TYPEFACE_MEDIUM
            row.setBackgroundColor(0)
        }

        if (tv.currentTextColor != targetColor) tv.setTextColor(targetColor)
        if (sub.currentTextColor != subColor) sub.setTextColor(subColor)
        if (tv.typeface !== targetTypeface) tv.typeface = targetTypeface
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSizeSp)

        val tier = LyricWindowMath.computeBlurTier(position, activeIndex, synced, selectionMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (holder.appliedBlurTier != tier) {
                holder.appliedBlurTier = tier
                row.setRenderEffect(
                    when (tier) {
                        1 -> BLUR_NEAR
                        2 -> BLUR_FAR
                        else -> null
                    }
                )
            }
            row.alpha = 1.0f
        } else {
            row.alpha = when (tier) {
                0 -> 1.0f
                1 -> 0.62f
                else -> 0.34f
            }
        }

        val targetScale = if (isActive) SCALE_ACTIVE else 1.0f
        if (animateScale && !SpringAnimatorHelper.isReducedMotionEnabled(row.context)) {
            SpringAnimatorHelper.springScale(row, targetScale)
        } else {
            SpringAnimatorHelper.cancelSpring(row)
            row.scaleX = targetScale
            row.scaleY = targetScale
        }
    }

    class LyricViewHolder(
        val rowView: LinearLayout,
        val mainText: TextView,
        val subText: TextView
    ) : RecyclerView.ViewHolder(rowView) {
        var appliedBlurTier: Int = -1
    }

    private class AdaptiveLyricScroller(
        context: Context,
        private val anchorFraction: Float,
        private val topSafeMargin: Int,
        private val bottomSafeMargin: Int
    ) : LinearSmoothScroller(context) {

        override fun calculateDtToFit(
            viewStart: Int,
            viewEnd: Int,
            boxStart: Int,
            boxEnd: Int,
            snapPreference: Int
        ): Int {
            val viewportH = boxEnd - boxStart
            val itemH = viewEnd - viewStart
            val targetTop = boxStart + LyricWindowMath.computeScrollTopOffset(
                viewportHeight = viewportH,
                itemHeight = itemH,
                anchorFraction = anchorFraction,
                topSafeMargin = topSafeMargin,
                bottomSafeMargin = bottomSafeMargin
            )
            return targetTop - viewStart
        }

        override fun onTargetFound(
            targetView: View,
            state: RecyclerView.State,
            action: Action
        ) {
            val dy = calculateDyToMakeVisible(targetView, SNAP_TO_ANY)
            if (dy != 0) {
                action.update(0, -dy, SCROLL_DURATION_MS, SpringAnimatorHelper.LIQUID_EASE)
            }
        }
    }

    internal companion object {
        private const val PAYLOAD_STATE = 1
        private const val MAX_SELECTED_QUOTE_LINES = 4

        internal const val SCROLL_DURATION_MS = SpringAnimatorHelper.LYRIC_SCROLL_DURATION_MS
        internal const val SCALE_ANIM_MS = SpringAnimatorHelper.LYRIC_SCALE_DURATION_MS

        private const val SIZE_ACTIVE_SP = 22f
        private const val SIZE_INACTIVE_SP = 18f
        private const val SIZE_TRANSLATION_SP = 15f
        private const val SCALE_ACTIVE = 1.03f

        private const val COLOR_ACTIVE = 0xFFFFFFFF.toInt()
        private const val COLOR_UPCOMING = 0xA3FFFFFF.toInt()
        private const val COLOR_PAST = 0x57FFFFFF.toInt()
        private const val COLOR_STATIC = 0xD9FFFFFF.toInt()
        private const val COLOR_SUB_ACTIVE = 0x9EFFFFFF.toInt()
        private const val COLOR_SUB_INACTIVE = 0x61FFFFFF
        private const val COLOR_SELECTED_BG = 0x22FFFFFF

        private val TYPEFACE_BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
        private val TYPEFACE_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        private val BLUR_NEAR: RenderEffect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect.createBlurEffect(2.2f, 2.2f, Shader.TileMode.CLAMP)
        } else null

        private val BLUR_FAR: RenderEffect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderEffect.createBlurEffect(4.8f, 4.8f, Shader.TileMode.CLAMP)
        } else null
    }
}
