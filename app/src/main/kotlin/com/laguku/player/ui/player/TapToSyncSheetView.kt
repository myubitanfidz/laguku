package com.laguku.player.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.laguku.player.R
import com.laguku.player.audio.AudioEngine
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.Track
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.motion.SpringAnimatorHelper

/**
 * Offline Tap-to-Sync LRC builder bottom sheet anchored in the thumb zone.
 */
@SuppressLint("ViewConstructor")
class TapToSyncSheetView(
    context: Context,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    class TapToSyncSession {
        private var rawLines: List<String> = emptyList()
        private val stampedLines = ArrayList<String>()

        fun loadRawLyrics(rawText: String) {
            stampedLines.clear()
            rawLines = rawText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split('\n')
                .map { TIMESTAMP_PREFIX_REGEX.replace(it.trim(), "").trim() }
                .filter { it.isNotEmpty() }
        }

        fun stampNextLine(positionMs: Long): String? {
            if (stampedLines.size >= rawLines.size) return null
            val text = rawLines[stampedLines.size]
            val formatted = "${formatLrcTimestamp(positionMs)} $text"
            stampedLines.add(formatted)
            return formatted
        }

        fun undoLastStamp(): Boolean {
            if (stampedLines.isEmpty()) return false
            stampedLines.removeAt(stampedLines.lastIndex)
            return true
        }

        fun getCurrentLinePreview(): String? = rawLines.getOrNull(stampedLines.size)

        fun getNextLinePreview(): String? = rawLines.getOrNull(stampedLines.size + 1)

        fun getLastStampedPreview(): String? = stampedLines.lastOrNull()

        fun getStampedCount(): Int = stampedLines.size

        fun getTotalCount(): Int = rawLines.size

        fun isComplete(): Boolean = rawLines.isNotEmpty() && stampedLines.size >= rawLines.size

        fun buildLrcContent(): String = stampedLines.joinToString("\n")

        companion object {
            private val TIMESTAMP_PREFIX_REGEX = Regex("""^(\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?])+\s*""")

            fun formatLrcTimestamp(positionMs: Long): String {
                val safeMs = positionMs.coerceAtLeast(0L)
                val minutes = (safeMs / 60_000L).toInt()
                val seconds = ((safeMs % 60_000L) / 1_000L).toInt()
                val centis = ((safeMs % 1_000L) / 10L).toInt()
                return String.format(java.util.Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centis)
            }
        }
    }

    var onSaveLrc: ((Track, String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var palette: CoverPalette = CoverPaletteExtractor.defaultPalette()
    private var currentTrack: Track? = null
    private var audioEngine: AudioEngine? = null
    private val session = TapToSyncSession()

    private val sheetCard = LinearLayout(context)
    private val sheetGlass = LiquidGlassDrawable(context, 24f)

    private val trackHeaderTv = TextView(context)
    private val pasteContainer = LinearLayout(context)
    private val rawLyricsEdit = EditText(context)
    private val rawLyricsBg = GradientDrawable().apply {
        setColor(LiquidGlassDrawable.LayerTokens.nestedCardColor(isDarkMode = true))
        cornerRadius = 16f * resources.displayMetrics.density
    }
    private val startSyncBtn = TextView(context)
    private val startSyncBg = GradientDrawable().apply {
        cornerRadius = 16f * resources.displayMetrics.density
    }

    private val syncContainer = LinearLayout(context)
    private val progressTv = TextView(context)
    private val lastStampedTv = TextView(context)
    private val activePreviewTv = TextView(context)
    private val nextPreviewTv = TextView(context)
    private val tapStampBtn = TextView(context)
    private val tapStampBg = GradientDrawable().apply {
        cornerRadius = 20f * resources.displayMetrics.density
    }
    private val undoBtn = TextView(context)
    private val playPauseBtn = TextView(context)
    private val editRawBtn = TextView(context)
    private val saveLrcBtn = TextView(context)
    private val saveLrcBg = GradientDrawable().apply {
        cornerRadius = 16f * resources.displayMetrics.density
    }

    init {
        setBackgroundColor(0x9904060A.toInt())
        isClickable = true
        setOnClickListener { onDismiss() }
        buildUi()
        applyPalette(palette)
    }

    fun applyBottomInset(bottomPx: Int) {
        sheetCard.setPadding(dp(16), dp(16), dp(16), dp(16) + bottomPx)
    }

    fun setPalette(newPalette: CoverPalette) {
        palette = newPalette
        applyPalette(newPalette)
    }

    fun setGlassTransparency(level: Float) {
        sheetGlass.setContinuousTransparency(level)
    }

    fun setAccessibilityFlags(flags: LiquidGlassDrawable.AccessibilityFlags) {
        sheetGlass.setAccessibilityFlags(flags)
    }

    fun bindTrack(track: Track, engine: AudioEngine?, prefillLyrics: String? = null) {
        currentTrack = track
        audioEngine = engine
        trackHeaderTv.text = "${track.title} \u2022 ${track.artist}"
        rawLyricsEdit.setText(prefillLyrics.orEmpty())
        showPastePhase(animate = false)
    }

    private fun buildUi() {
        sheetCard.apply {
            orientation = LinearLayout.VERTICAL
            background = sheetGlass
            setPadding(dp(16), dp(10), dp(16), dp(18))
            elevation = 20f * density
            isClickable = true
        }
        val grabberHandle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(0x55FFFFFF)
                cornerRadius = 2f * density
            }
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = context.getString(R.string.tap_sync_title)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val closeBtn = createActionChip(context.getString(R.string.eq_close)) { onDismiss() }
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(closeBtn)

        trackHeaderTv.apply {
            setTextColor(0xA3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(10))
        }

        // Phase 1: Paste raw lyrics
        pasteContainer.orientation = LinearLayout.VERTICAL
        rawLyricsEdit.apply {
            hint = context.getString(R.string.tap_sync_paste_hint)
            setHintTextColor(0x70FFFFFF)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            background = rawLyricsBg
            gravity = Gravity.TOP or Gravity.START
            minLines = 6
            maxLines = 9
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        startSyncBtn.apply {
            text = context.getString(R.string.tap_sync_start)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minimumHeight = dp(50)
            background = startSyncBg
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener {
                session.loadRawLyrics(rawLyricsEdit.text?.toString().orEmpty())
                if (session.getTotalCount() > 0) {
                    showSyncPhase(animate = true)
                    if (audioEngine?.isPlaying() == false) {
                        audioEngine?.play()
                    }
                }
            }
        }
        pasteContainer.addView(
            rawLyricsEdit,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(180)).apply { bottomMargin = dp(10) }
        )
        pasteContainer.addView(
            startSyncBtn,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(50))
        )

        // Phase 2: Tap-to-Sync live controller
        syncContainer.apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        progressTv.apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            fontFeatureSettings = "tnum"
        }
        lastStampedTv.apply {
            setTextColor(0x75FFFFFF)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, dp(8))
        }
        activePreviewTv.apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 3
            setPadding(0, dp(4), 0, dp(6))
        }
        nextPreviewTv.apply {
            setTextColor(0x88FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            maxLines = 2
            setPadding(0, 0, 0, dp(12))
        }

        tapStampBtn.apply {
            text = context.getString(R.string.tap_sync_stamp_btn)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            minimumHeight = dp(58)
            background = tapStampBg
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { view ->
                val posMs = audioEngine?.getCurrentPositionMs() ?: 0L
                if (session.stampNextLine(posMs) != null) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    refreshSyncUi()
                }
            }
        }

        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        playPauseBtn.apply {
            configureSecondaryBtn(this, context.getString(R.string.cd_play_pause)) {
                audioEngine?.togglePlayPause()
                refreshPlayButtonLabel()
            }
        }
        undoBtn.apply {
            configureSecondaryBtn(this, context.getString(R.string.tap_sync_undo)) {
                if (session.undoLastStamp()) {
                    refreshSyncUi()
                }
            }
        }
        editRawBtn.apply {
            configureSecondaryBtn(this, context.getString(R.string.tap_sync_edit_raw)) {
                showPastePhase(animate = true)
            }
        }
        controlsRow.addView(playPauseBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        controlsRow.addView(undoBtn, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        controlsRow.addView(editRawBtn, LinearLayout.LayoutParams(0, dp(46), 1f))

        saveLrcBtn.apply {
            text = context.getString(R.string.tap_sync_save_lrc)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            background = saveLrcBg
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener {
                val track = currentTrack ?: return@setOnClickListener
                val lrc = session.buildLrcContent()
                if (lrc.isNotBlank()) {
                    onSaveLrc?.invoke(track, lrc)
                }
            }
        }

        syncContainer.addView(progressTv)
        syncContainer.addView(lastStampedTv)
        syncContainer.addView(activePreviewTv)
        syncContainer.addView(nextPreviewTv)
        syncContainer.addView(
            tapStampBtn,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(8) }
        )
        syncContainer.addView(
            controlsRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        )
        syncContainer.addView(
            saveLrcBtn,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
        )

        sheetCard.addView(grabberHandle, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(10)
        })
        sheetCard.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sheetCard.addView(trackHeaderTv, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sheetCard.addView(pasteContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sheetCard.addView(syncContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        SpringAnimatorHelper.attachSwipeToDismiss(grabberHandle, sheetCard, onDismiss = onDismiss)
        SpringAnimatorHelper.attachSwipeToDismiss(headerRow, sheetCard, onDismiss = onDismiss)
        addView(sheetCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun configureSecondaryBtn(tv: TextView, label: String, onClick: () -> Unit) {
        tv.apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minimumHeight = dp(46)
            background = GradientDrawable().apply {
                setColor(0x1EFFFFFF)
                cornerRadius = 14f * density
            }
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onClick() }
        }
    }

    private fun createActionChip(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minimumHeight = dp(44)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x24FFFFFF)
                cornerRadius = 14f * density
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onClick() }
        }
    }

    private fun showPastePhase(animate: Boolean) {
        if (animate) {
            SpringAnimatorHelper.springDismiss(syncContainer, toTranslationY = dp(8).toFloat(), toScale = 0.97f)
            SpringAnimatorHelper.springReveal(pasteContainer, fromTranslationY = dp(8).toFloat(), fromScale = 0.97f)
        } else {
            syncContainer.visibility = View.GONE
            pasteContainer.visibility = View.VISIBLE
        }
    }

    private fun showSyncPhase(animate: Boolean) {
        refreshSyncUi()
        refreshPlayButtonLabel()
        if (animate) {
            SpringAnimatorHelper.springDismiss(pasteContainer, toTranslationY = dp(8).toFloat(), toScale = 0.97f)
            SpringAnimatorHelper.springReveal(syncContainer, fromTranslationY = dp(8).toFloat(), fromScale = 0.97f)
        } else {
            pasteContainer.visibility = View.GONE
            syncContainer.visibility = View.VISIBLE
        }
    }

    private fun refreshPlayButtonLabel() {
        val playing = audioEngine?.isPlaying() == true
        playPauseBtn.text = if (playing) "Jeda" else "Putar"
    }

    private fun refreshSyncUi() {
        val stamped = session.getStampedCount()
        val total = session.getTotalCount()
        progressTv.text = context.getString(R.string.tap_sync_progress_fmt, stamped, total)
        lastStampedTv.text = session.getLastStampedPreview() ?: ""
        val cur = session.getCurrentLinePreview()
        if (cur != null) {
            activePreviewTv.text = cur
            nextPreviewTv.text = session.getNextLinePreview().orEmpty()
            tapStampBtn.isEnabled = true
            tapStampBtn.alpha = 1f
        } else {
            activePreviewTv.text = context.getString(R.string.tap_sync_done_preview)
            nextPreviewTv.text = ""
            tapStampBtn.isEnabled = false
            tapStampBtn.alpha = 0.5f
        }
        saveLrcBtn.isEnabled = stamped > 0
        saveLrcBtn.alpha = if (stamped > 0) 1f else 0.5f
    }

    private fun applyPalette(newPalette: CoverPalette) {
        sheetGlass.setPalette(newPalette)
        sheetGlass.setSurfaceScale(LiquidGlassDrawable.GlassSurfaceScale.LARGE)
        sheetGlass.setTintProfile(baseFraction = 0.88f, accentFraction = 0.14f)
        val accentTint = (0x66 shl 24) or (newPalette.accentGlow and 0x00FFFFFF)
        startSyncBg.setColor(accentTint)
        tapStampBg.setColor(accentTint)
        saveLrcBg.setColor(accentTint)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
