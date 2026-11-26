package com.laguku.player.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laguku.player.R
import com.laguku.player.audio.AudioEngine
import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.lyrics.LrcParser
import com.laguku.player.model.AudioQualityInfo
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.LosslessAuditStatus
import com.laguku.player.model.LyricLine
import com.laguku.player.model.LyricSource
import com.laguku.player.model.ParsedLyrics
import com.laguku.player.model.RepeatMode
import com.laguku.player.model.Track
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.InteractiveGlassScrubView
import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.motion.SpringAnimatorHelper
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Full Now Playing screen following the Bottom-Half Thumb-Zone Layout & Progressive Disclosure:
 * - Top 50% (Display-Only): passive album title, quality + Lossless Auditor badge, active output route,
 *   and 3 display modes (Sampul 1:1 / Disk Vinyl + PCM Ring Visualizer / Lirik Multi-Baris Adaptif).
 * - Bottom 50% (Control Dock):
 *   1) Title, artist, Favorit & Antrean (>= 48dp)
 *   2) SeekCapsuleView (glass waveform, AB-loop indicator, chapter ticks, tabular timestamps)
 *   3) 5 Transport Controls
 *   4) Interactive 3-Mode Glass Scrub Pill (Sampul / Disk / Lirik) + 3 Utility Pills (Audio Lab, Timer, AMOLED).
 */
@SuppressLint("ViewConstructor")
class NowPlayingView(
    context: Context,
    private val onCollapse: () -> Unit
) : FrameLayout(context) {

    enum class DisplayMode { FULL_COVER, VINYL_DISC, LYRICS_FOCUS }

    object PlayDragMath {
        const val DRAG_THRESHOLD_DP = 44f
        const val MAX_OFFSET_DP = 58f

        fun computeRubberBandOffset(rawDxPx: Float, thresholdPx: Float, maxOffsetPx: Float): Float {
            if (thresholdPx <= 0f || maxOffsetPx <= 0f) return 0f
            val sign = if (rawDxPx >= 0f) 1f else -1f
            val absDx = abs(rawDxPx)
            val baseAtThreshold = thresholdPx * 0.85f
            if (absDx <= thresholdPx) {
                return rawDxPx * 0.85f
            }
            val extra = absDx - thresholdPx
            val headroom = (maxOffsetPx - baseAtThreshold).coerceAtLeast(1f)
            val dampedExtra = headroom * (extra / (extra + headroom * 1.4f))
            return sign * (baseAtThreshold + dampedExtra)
        }

        fun resolveDragDirection(rawDxPx: Float, thresholdPx: Float): Int {
            return when {
                rawDxPx >= thresholdPx -> 1
                rawDxPx <= -thresholdPx -> -1
                else -> 0
            }
        }
    }

    class PlayLoupeController(val dwellDelayMs: Long = DWELL_DELAY_MS) {
        companion object {
            const val DWELL_DELAY_MS = 280L
        }

        data class DragUpdate(
            val loupeOffsetX: Float,
            val direction: Int,
            val thresholdCrossed: Boolean,
            val scheduleDwell: Boolean,
            val cancelDwell: Boolean
        )

        val iconTranslationX: Float = 0f
        var loupeTranslationX: Float = 0f
            private set
        var activeDirection: Int = 0
            private set
        var dwellTriggered: Boolean = false
            private set
        private var enteredZoneAtMs: Long = 0L

        fun onTouchDown() {
            loupeTranslationX = 0f
            activeDirection = 0
            dwellTriggered = false
            enteredZoneAtMs = 0L
        }

        fun onDragMove(
            rawDxPx: Float,
            thresholdPx: Float,
            maxOffsetPx: Float,
            nowMs: Long
        ): DragUpdate {
            val offset = PlayDragMath.computeRubberBandOffset(rawDxPx, thresholdPx, maxOffsetPx)
            val newDir = PlayDragMath.resolveDragDirection(rawDxPx, thresholdPx)
            val prevDir = activeDirection
            loupeTranslationX = offset
            activeDirection = newDir

            val thresholdCrossed = newDir != 0 && newDir != prevDir
            val cancelDwell = newDir == 0 && prevDir != 0
            val scheduleDwell = newDir != 0 && newDir != prevDir
            if (scheduleDwell) {
                enteredZoneAtMs = nowMs
                dwellTriggered = false
            } else if (newDir == 0) {
                enteredZoneAtMs = 0L
            }
            return DragUpdate(offset, newDir, thresholdCrossed, scheduleDwell, cancelDwell)
        }

        fun onDwellTimeout(nowMs: Long): Int {
            if (activeDirection == 0 || dwellTriggered) return 0
            if (nowMs - enteredZoneAtMs < dwellDelayMs) return 0
            dwellTriggered = true
            return activeDirection
        }

        fun onTouchUp(wasDragging: Boolean, isCancel: Boolean): Int {
            val triggerDir = if (!isCancel && wasDragging && activeDirection != 0 && !dwellTriggered) {
                activeDirection
            } else {
                0
            }
            onTouchDown()
            return triggerDir
        }
    }

    class LyricTunerState(initialExpanded: Boolean = false) {
        var isExpanded: Boolean = initialExpanded
            private set

        fun toggle(): Boolean {
            isExpanded = !isExpanded
            return isExpanded
        }

        fun expand() {
            isExpanded = true
        }

        fun collapse() {
            isExpanded = false
        }

        fun onSavePressed() {
            isExpanded = false
        }

        fun onEnterLyricsMode() {
            isExpanded = false
        }
    }

    var onOpenAudioLab: (() -> Unit)? = null
    var onToggleAmoled: (() -> Unit)? = null
    var onSaveLyricOffset: ((Track, Long) -> Unit)? = null
    var onLiveLyricOffsetChanged: ((Track, Long) -> Unit)? = null
    var onLyricHighlightChanged: ((Float) -> Unit)? = null
    var onSaveLyricSettings: ((Track, Long, Float) -> Unit)? = null
    var onSearchOnlineLyrics: ((Track) -> Unit)? = null
    var onOpenTapToSync: ((Track, String?) -> Unit)? = null
    var onShareLyricQuote: ((Track, List<LyricLine>) -> Unit)? = null
    var onVisualizerDemandChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val favoritePaths = HashSet(prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty())

    private var audioEngine: AudioEngine? = null
    private var currentQueue: List<Track> = emptyList()
    private var palette: CoverPalette = CoverPaletteExtractor.defaultPalette()
    private var displayMode: DisplayMode = DisplayMode.FULL_COVER
    private var parsedLyrics: ParsedLyrics = ParsedLyrics(emptyList(), isSynced = false)
    private var manualScrollPaused: Boolean = false
    private var sleepTimerEndElapsedMs: Long = 0L

    private var userLyricOffsetMs: Long = 0L
    private var btLyricOffsetMs: Long = 0L
    private var lyricHighlightFraction: Float = PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL
    private val lyricTunerState = LyricTunerState()
    private var pendingInitialLyricSnap: Boolean = false
    private var plainLyricsFallback: String? = null

    private var currentAuditStatus: LosslessAuditStatus = LosslessAuditStatus.NOT_APPLICABLE
    private var currentQualityInfo: AudioQualityInfo = AudioQualityInfo()
    private var amoledEnabled: Boolean = false
    private var amoledProgress: Float = 0f
    private var zenModeActive: Boolean = false
    private var hostActive: Boolean = true
    private var pendingTrackDirection: Int = 0
    private var lastAnimatedTrackPath: String? = null

    private var playDragDownRawX: Float = 0f
    private var playDragDownRawY: Float = 0f
    private var lastPlayDragRawX: Float = 0f
    private var lastPlayDragTimeMs: Long = 0L
    private var isPlayDragging: Boolean = false
    private val playLoupeController = PlayLoupeController()
    private val playDragThresholdPx: Float = PlayDragMath.DRAG_THRESHOLD_DP * density
    private val playDragMaxOffsetPx: Float = PlayDragMath.MAX_OFFSET_DP * density

    private var topInsetPx: Int = 0
    private var bottomInsetPx: Int = 0

    private val contentColumn = LinearLayout(context)

    // Top 50% Display-Only Area
    private val topDisplayZone = LinearLayout(context)
    private val passiveHeader = LinearLayout(context)
    private val albumTitleView = TextView(context)
    private val qualityBadgeView = TextView(context)
    private val qualityBadgeGlass = LiquidGlassDrawable(context, 14f)
    private val routeBadgeView = TextView(context)

    private val stageFrame = FrameLayout(context)
    private val coverContainer = FrameLayout(context)
    private val coverCard = FrameLayout(context)
    private val coverPlaceholder = ImageView(context)
    private val coverImage = ImageView(context)

    private val vinylDiscView = VinylDiscVisualizerView(context)

    private val lyricsContainer = FrameLayout(context)
    private val lyricsRecycler = RecyclerView(context)
    private val lyricsEmptyContainer = LinearLayout(context)
    private val lyricsEmptyView = TextView(context)
    private val lyricsFetchStatusView = TextView(context)
    private val searchOnlineBtn = TextView(context)
    private val searchOnlineGlass = LiquidGlassDrawable(context, 16f)
    private val tapToSyncEmptyBtn = TextView(context)
    private val tapToSyncEmptyGlass = LiquidGlassDrawable(context, 16f)
    private val returnToActivePill = TextView(context)
    private val returnPillGlass = LiquidGlassDrawable(context, 20f)

    // Collapsible Lyric Tuner (compact glass icon button -> spring-expanded offset + anchor panel)
    private val lyricTunerToggleBtn = ImageButton(context)
    private val lyricTunerToggleGlass = LiquidGlassDrawable(context, 24f)
    private val lyricTunerPanel = LinearLayout(context)
    private val lyricTunerPanelGlass = LiquidGlassDrawable(context, 18f)
    private val lyricSourceBadgeTv = TextView(context)
    private val offsetRow = LinearLayout(context)
    private val offsetMinusBtn = TextView(context)
    private val offsetValueTv = TextView(context)
    private val offsetPlusBtn = TextView(context)
    private val offsetSaveBtn = TextView(context)
    private val offsetSaveBg = GradientDrawable().apply { cornerRadius = 14f * resources.displayMetrics.density }
    private val anchorRow = LinearLayout(context)
    private val anchorTopBtn = TextView(context)
    private val anchorOptimalBtn = TextView(context)
    private val anchorCenterBtn = TextView(context)
    private val anchorTopBg = GradientDrawable().apply { cornerRadius = 12f * resources.displayMetrics.density }
    private val anchorOptimalBg = GradientDrawable().apply { cornerRadius = 12f * resources.displayMetrics.density }
    private val anchorCenterBg = GradientDrawable().apply { cornerRadius = 12f * resources.displayMetrics.density }
    private val tunerEditBtn = TextView(context)
    private val tunerEditBg = GradientDrawable().apply { cornerRadius = 12f * resources.displayMetrics.density }

    private val lyricQuoteBar = LinearLayout(context)
    private val lyricQuoteGlass = LiquidGlassDrawable(context, 18f)
    private val shareQuoteBtn = TextView(context)
    private val cancelQuoteBtn = TextView(context)

    private lateinit var lyricsAdapter: LyricsRecyclerAdapter

    // Bottom 50% Control Dock
    private val bottomControlDock = LinearLayout(context)

    // Dock Row 1: Track info + Favorit + Antrean
    private val trackTitleView = TextView(context)
    private val trackArtistView = TextView(context)
    private val favoriteButton = ImageButton(context)
    private val queueButton = ImageButton(context)
    private val dockIconGlassFav = LiquidGlassDrawable(context, 16f)
    private val dockIconGlassQueue = LiquidGlassDrawable(context, 16f)

    // Dock Row 2: WaveformPillSeekBarView
    private val seekWaveformView = WaveformPillSeekBarView(context)

    // Dock Row 3: 5 Transport Controls + Detached Play Glass Loupe
    private val transportHost = FrameLayout(context)
    private val playLoupeView = View(context)
    private val shuffleButton = ImageButton(context)
    private val prevButton = ImageButton(context)
    private val playPauseButton = ImageButton(context)
    private val playPauseGlass = LiquidGlassDrawable(context, 32f)
    private val nextButton = ImageButton(context)
    private val repeatButton = ImageButton(context)

    // Dock Row 4: Interactive 3-Mode Scrub Pill (Sampul / Disk / Lirik)
    private val modeScrubPill = InteractiveGlassScrubView(context, 20f)
    private val modeSlotViews = ArrayList<ModeSlotView>(3)

    // Dock Row 5: 3 Utility Glass Pills (Audio Lab, Timer, AMOLED)
    private val secondaryDockRow = LinearLayout(context)
    private val labPill = LinearLayout(context)
    private val labPillIcon = ImageView(context)
    private val labPillText = TextView(context)
    private val labPillGlass = LiquidGlassDrawable(context, 18f)

    private val timerPill = LinearLayout(context)
    private val timerPillIcon = ImageView(context)
    private val timerPillText = TextView(context)
    private val timerPillGlass = LiquidGlassDrawable(context, 18f)

    private val amoledPill = LinearLayout(context)
    private val amoledPillIcon = ImageView(context)
    private val amoledPillText = TextView(context)
    private val amoledPillGlass = LiquidGlassDrawable(context, 18f)

    // Modal Overlays (Sleep Timer, Antrean/Queue)
    private val modalBackdrop = FrameLayout(context)
    private val modalCard = LinearLayout(context)
    private val modalCardGlass = LiquidGlassDrawable(context, 22f)
    private val modalTitleView = TextView(context)
    private val modalBody = LinearLayout(context)
    private val modalActionsRow = LinearLayout(context)

    private val autoResumeRunnable = Runnable {
        resumeLyricsAutoScroll(scrollNow = true)
    }

    private val playDwellRunnable = Runnable {
        val dir = playLoupeController.onDwellTimeout(SystemClock.elapsedRealtime())
        if (dir != 0) {
            playPauseGlass.setSpecularIntensity(1f)
            val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            playLoupeView.performHapticFeedback(haptic)
            triggerTrackNavigation(dir)
        }
    }

    private val sleepDisplayTicker = object : Runnable {
        override fun run() {
            if (sleepTimerEndElapsedMs > 0L) {
                val rem = sleepTimerEndElapsedMs - SystemClock.elapsedRealtime()
                if (rem <= 0L) {
                    sleepTimerEndElapsedMs = 0L
                    updateSecondaryPillStates()
                } else {
                    updateSecondaryPillStates()
                    mainHandler.postDelayed(this, 10_000L)
                }
            }
        }
    }

    init {
        isClickable = true
        isFocusable = true
        buildUi()
        applyPalette(palette)
        setDisplayMode(DisplayMode.FULL_COVER, animate = false, syncScrub = true)
    }

    fun applySystemInsets(topPx: Int, bottomPx: Int) {
        topInsetPx = topPx
        bottomInsetPx = bottomPx
        contentColumn.setPadding(
            dp(20),
            topInsetPx + dp(10),
            dp(20),
            bottomInsetPx + dp(14)
        )
    }

    fun attachEngine(engine: AudioEngine?) {
        audioEngine = engine
        if (engine != null) {
            onTrackChanged(engine.getCurrentTrack())
            onQualityChanged(engine.getCurrentQuality())
            setLosslessAuditStatus(engine.getLosslessAuditStatus())
            setAbLoop(engine.getAbLoop().first, engine.getAbLoop().second)
            onPlaybackStateChanged(engine.isPlaying())
            onPositionUpdated(engine.getCurrentPositionMs(), engine.getDurationMs())
            updateShuffleAndRepeatState()
            updateSecondaryPillStates()
        }
    }

    fun getAudioLabAnchorView(): View = labPill

    fun getTapToSyncAnchorView(): View = when {
        lyricTunerPanel.visibility == View.VISIBLE -> lyricTunerPanel
        lyricTunerToggleBtn.visibility == View.VISIBLE -> lyricTunerToggleBtn
        else -> tapToSyncEmptyBtn
    }

    fun setHostActive(active: Boolean) {
        hostActive = active
        vinylDiscView.setHostActive(active)
        if (!active) {
            mainHandler.removeCallbacks(playDwellRunnable)
            modeScrubPill.cancelActiveInteractions()
            SpringAnimatorHelper.cancelSpring(playLoupeView)
            playLoupeView.translationX = 0f
            playLoupeView.scaleX = 1f
            playLoupeView.scaleY = 1f
            playPauseGlass.setMotionState(0f, touchActive = false)
            prevButton.scaleX = 1f
            prevButton.scaleY = 1f
            nextButton.scaleX = 1f
            nextButton.scaleY = 1f
        }
        onVisualizerDemandChanged?.invoke(isVisualizerDemanded())
    }

    fun isVisualizerDemanded(): Boolean = hostActive && displayMode == DisplayMode.VINYL_DISC

    fun setVisualizerLevel(level: Float) {
        if (displayMode == DisplayMode.VINYL_DISC && hostActive) {
            vinylDiscView.setVisualizerLevel(level)
        }
    }

    fun setOutputRouteLabel(label: String) {
        if (label.isBlank()) {
            routeBadgeView.visibility = View.GONE
        } else {
            routeBadgeView.text = label
            routeBadgeView.visibility = View.VISIBLE
        }
    }

    fun setLosslessAuditStatus(status: LosslessAuditStatus) {
        currentAuditStatus = status
        renderQualityBadge()
    }

    fun setWaveform(bars: FloatArray?) {
        seekWaveformView.setWaveform(bars)
    }

    fun setAbLoop(startMs: Long?, endMs: Long?) {
        seekWaveformView.setAbLoop(startMs, endMs)
    }

    fun setLyricOffsets(userOffsetMs: Long, btOffsetMs: Long) {
        userLyricOffsetMs = userOffsetMs
        btLyricOffsetMs = btOffsetMs
        updateLyricOffsetDisplay()
        val pos = audioEngine?.getCurrentPositionMs() ?: 0L
        updateActiveLyricForPosition(pos, forceScroll = false)
    }

    fun setLyricHighlightFraction(fraction: Float) {
        lyricHighlightFraction = fraction.coerceIn(0.20f, 0.60f)
        lyricsAdapter.setHighlightFraction(lyricHighlightFraction)
        updateHighlightAnchorButtons()
        applyLyricsViewportPadding(lyricsRecycler.height)
        val active = lyricsAdapter.getActiveIndex()
        if (displayMode == DisplayMode.LYRICS_FOCUS && !manualScrollPaused && lyricsAdapter.getLines().isNotEmpty()) {
            if (active >= 0) {
                lyricsAdapter.scrollToActiveLine(lyricsRecycler, active, animated = false)
            } else {
                lyricsAdapter.snapToInitialAnchor(lyricsRecycler)
            }
        }
    }

    fun setAmoledMode(enabled: Boolean, animate: Boolean = true) {
        amoledEnabled = enabled
        val target = if (enabled) 1f else 0f
        if (!animate || SpringAnimatorHelper.isReducedMotionEnabled(context)) {
            SpringAnimatorHelper.cancelSpring(this, CHANNEL_AMOLED)
            applyAmoledProgressToGlass(target)
        } else {
            SpringAnimatorHelper.animateSpring(
                key = this,
                start = amoledProgress,
                target = target,
                stiffness = SpringAnimatorHelper.AmoledTransitionMath.AMOLED_STIFFNESS,
                dampingRatio = SpringAnimatorHelper.AmoledTransitionMath.AMOLED_DAMPING_RATIO,
                channel = CHANNEL_AMOLED,
                onUpdate = { p -> applyAmoledProgressToGlass(p) }
            )
        }
        updateSecondaryPillStates()
    }

    private fun applyAmoledProgressToGlass(progress: Float) {
        amoledProgress = progress.coerceIn(0f, 1f)
        qualityBadgeGlass.setAmoledProgress(amoledProgress)
        returnPillGlass.setAmoledProgress(amoledProgress)
        lyricTunerToggleGlass.setAmoledProgress(amoledProgress)
        lyricTunerPanelGlass.setAmoledProgress(amoledProgress)
        searchOnlineGlass.setAmoledProgress(amoledProgress)
        tapToSyncEmptyGlass.setAmoledProgress(amoledProgress)
        lyricQuoteGlass.setAmoledProgress(amoledProgress)
        dockIconGlassFav.setAmoledProgress(amoledProgress)
        dockIconGlassQueue.setAmoledProgress(amoledProgress)
        playPauseGlass.setAmoledProgress(amoledProgress)
        labPillGlass.setAmoledProgress(amoledProgress)
        timerPillGlass.setAmoledProgress(amoledProgress)
        amoledPillGlass.setAmoledProgress(amoledProgress)
        modalCardGlass.setAmoledProgress(amoledProgress)
        modeScrubPill.invalidate()
    }

    fun isZenMode(): Boolean = zenModeActive

    fun exitZenMode() {
        if (zenModeActive) setZenMode(false)
    }

    fun setQueueSnapshot(tracks: List<Track>) {
        currentQueue = tracks
    }

    fun setPalette(newPalette: CoverPalette) {
        palette = newPalette
        applyPalette(newPalette)
    }

    fun setCoverArtwork(bitmap: Bitmap?) {
        vinylDiscView.setCoverArtwork(bitmap)
        if (bitmap != null && !bitmap.isRecycled) {
            coverImage.setImageBitmap(bitmap)
            coverImage.visibility = View.VISIBLE
            coverPlaceholder.visibility = View.GONE
        } else {
            coverImage.setImageBitmap(null)
            coverImage.visibility = View.GONE
            coverPlaceholder.visibility = View.VISIBLE
        }
    }

    fun setLyrics(parsed: ParsedLyrics) {
        parsedLyrics = parsed
        lyricsAdapter.cancelScrollAnimation(lyricsRecycler)
        lyricsAdapter.submitLyrics(parsed)
        manualScrollPaused = false
        pendingInitialLyricSnap = parsed.lines.isNotEmpty()
        mainHandler.removeCallbacks(autoResumeRunnable)
        returnToActivePill.visibility = View.GONE
        lyricsEmptyContainer.visibility = if (parsed.lines.isEmpty()) View.VISIBLE else View.GONE
        lyricTunerState.onEnterLyricsMode()
        updateLyricSourceBadge(parsed.source)
        updateLyricTunerVisibility(animate = false)
        lyricQuoteBar.visibility = View.GONE
        updateLyricOffsetDisplay()
        updateHighlightAnchorButtons()

        val currentPos = audioEngine?.getCurrentPositionMs() ?: 0L
        updateActiveLyricForPosition(currentPos, forceScroll = true)

        if (parsed.lines.isNotEmpty() && lyricsRecycler.height > 0) {
            applyLyricsViewportPadding(lyricsRecycler.height)
            pendingInitialLyricSnap = false
            lyricsRecycler.post {
                lyricsAdapter.snapToInitialAnchor(lyricsRecycler)
            }
        }
    }

    private fun applyLyricsViewportPadding(viewportHeight: Int) {
        if (viewportHeight <= 0) return
        val (padTop, padBottom) = LyricsRecyclerAdapter.LyricWindowMath.computeAnchorPadding(
            viewportHeight = viewportHeight,
            anchorFraction = lyricHighlightFraction
        )
        if (lyricsRecycler.paddingTop != padTop || lyricsRecycler.paddingBottom != padBottom) {
            lyricsRecycler.setPadding(0, padTop, 0, padBottom)
        }
    }

    private fun updateLyricSourceBadge(source: LyricSource) {
        if (source == LyricSource.NONE || source.label.isBlank()) {
            lyricSourceBadgeTv.visibility = View.GONE
        } else {
            lyricSourceBadgeTv.text = context.getString(R.string.lyric_source_fmt, source.label)
            lyricSourceBadgeTv.visibility = View.VISIBLE
        }
    }

    fun setPlainLyricsFallback(text: String?) {
        plainLyricsFallback = text?.takeIf { it.isNotBlank() }
    }

    fun setLyricFetchStatus(message: String?, isLoading: Boolean = false) {
        searchOnlineBtn.isEnabled = !isLoading
        searchOnlineBtn.alpha = if (isLoading) 0.55f else 1f
        if (message.isNullOrBlank()) {
            lyricsFetchStatusView.text = ""
            lyricsFetchStatusView.visibility = View.GONE
        } else {
            lyricsFetchStatusView.text = message
            lyricsFetchStatusView.visibility = View.VISIBLE
        }
    }

    fun onTrackChanged(track: Track?) {
        if (track == null) {
            pendingTrackDirection = 0
            lastAnimatedTrackPath = null
            plainLyricsFallback = null
            setLyricFetchStatus(null, isLoading = false)
            albumTitleView.text = context.getString(R.string.app_name)
            trackTitleView.text = context.getString(R.string.unknown_title)
            trackArtistView.text = context.getString(R.string.unknown_artist)
            currentQualityInfo = AudioQualityInfo()
            currentAuditStatus = LosslessAuditStatus.NOT_APPLICABLE
            qualityBadgeView.visibility = View.GONE
            seekWaveformView.setChapters(emptyList())
            seekWaveformView.setWaveform(null)
            seekWaveformView.setAbLoop(null, null)
            setCoverArtwork(null)
            setLyrics(ParsedLyrics(emptyList(), isSynced = false))
            updateFavoriteButton(null)
            return
        }
        val dir = pendingTrackDirection
        pendingTrackDirection = 0
        val trackChanged = track.path != lastAnimatedTrackPath
        lastAnimatedTrackPath = track.path

        plainLyricsFallback = null
        setLyricFetchStatus(null, isLoading = false)
        albumTitleView.text = track.album.ifBlank { context.getString(R.string.unknown_album) }
        trackTitleView.text = track.title.ifBlank { context.getString(R.string.unknown_title) }
        trackArtistView.text = track.artist.ifBlank { context.getString(R.string.unknown_artist) }
        seekWaveformView.setChapters(track.chapters)
        seekWaveformView.setTrackKey(track.contentUri.ifBlank { track.path })
        onQualityChanged(track.quality)
        updateFavoriteButton(track)
        updateShuffleAndRepeatState()

        if (trackChanged && dir != 0) {
            animateDirectionalTrackChange(dir)
        }
    }

    private fun triggerTrackNavigation(direction: Int) {
        pendingTrackDirection = direction.coerceIn(-1, 1)
        if (pendingTrackDirection > 0) {
            audioEngine?.next()
        } else if (pendingTrackDirection < 0) {
            audioEngine?.previous()
        }
    }

    private fun animateDirectionalTrackChange(direction: Int) {
        if (SpringAnimatorHelper.isReducedMotionEnabled(context)) return
        val dir = direction.coerceIn(-1, 1)
        if (displayMode == DisplayMode.FULL_COVER) {
            SpringAnimatorHelper.springReveal(
                coverCard,
                fromTranslationX = dir * dp(32).toFloat(),
                fromScale = 0.94f
            )
        } else if (displayMode == DisplayMode.VINYL_DISC) {
            vinylDiscView.animateTrackTransition(dir)
        }

        SpringAnimatorHelper.springReveal(
            albumTitleView,
            fromTranslationX = dir * dp(18).toFloat(),
            fromScale = 1f
        )
        SpringAnimatorHelper.springReveal(
            trackTitleView,
            fromTranslationX = dir * dp(26).toFloat(),
            fromScale = 1f
        )

        val artistStartX = dir * dp(38).toFloat()
        SpringAnimatorHelper.cancelSpring(trackArtistView, CHANNEL_ARTIST_SLIDE)
        trackArtistView.alpha = 0f
        trackArtistView.translationX = artistStartX
        SpringAnimatorHelper.animateSpring(
            key = trackArtistView,
            start = 0f,
            target = 1f,
            stiffness = 320f,
            dampingRatio = 0.84f,
            channel = CHANNEL_ARTIST_SLIDE,
            onUpdate = { p ->
                trackArtistView.alpha = p.coerceIn(0f, 1f)
                trackArtistView.translationX = artistStartX * (1f - p)
            }
        )
    }

    fun onPlaybackStateChanged(isPlaying: Boolean) {
        playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        vinylDiscView.setPlaying(isPlaying)
    }

    fun onPositionUpdated(positionMs: Long, durationMs: Long) {
        seekWaveformView.setPosition(positionMs, durationMs)
        updateActiveLyricForPosition(positionMs, forceScroll = false)
    }

    fun onQualityChanged(quality: AudioQualityInfo) {
        currentQualityInfo = quality
        renderQualityBadge()
    }

    private fun renderQualityBadge() {
        val base = currentQualityInfo.formatBadge()
        if (base.isBlank()) {
            qualityBadgeView.visibility = View.GONE
            return
        }
        val suffix = when (currentAuditStatus) {
            LosslessAuditStatus.GENUINE_LOSSLESS -> " \u2022 Asli"
            LosslessAuditStatus.SUSPECTED_UPSCALE -> " \u2022 Upscale?"
            else -> ""
        }
        qualityBadgeView.text = base + suffix
        qualityBadgeView.visibility = View.VISIBLE
    }

    fun isModalOpen(): Boolean = modalBackdrop.visibility == View.VISIBLE

    fun closeModal() {
        if (modalBackdrop.visibility != View.VISIBLE) return
        SpringAnimatorHelper.springDismiss(modalCard, toTranslationY = dp(24).toFloat(), toScale = 0.96f)
        SpringAnimatorHelper.springDismiss(modalBackdrop, toScale = 1f) {
            modalBody.removeAllViews()
            modalActionsRow.removeAllViews()
        }
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(autoResumeRunnable)
        mainHandler.removeCallbacks(playDwellRunnable)
        mainHandler.removeCallbacks(sleepDisplayTicker)
        lyricsAdapter.cancelScrollAnimation(lyricsRecycler)
        SpringAnimatorHelper.cancelSpring(this, CHANNEL_AMOLED)
        SpringAnimatorHelper.cancelSpring(playLoupeView)
        SpringAnimatorHelper.cancelSpring(playPauseButton, CHANNEL_PLAY_DRAG)
        SpringAnimatorHelper.cancelSpring(trackArtistView, CHANNEL_ARTIST_SLIDE)
        vinylDiscView.setHostActive(false)
        super.onDetachedFromWindow()
    }

    private fun buildUi() {
        contentColumn.orientation = LinearLayout.VERTICAL
        contentColumn.clipChildren = false
        contentColumn.clipToPadding = false
        contentColumn.setPadding(dp(20), dp(12), dp(20), dp(16))
        addView(contentColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        buildTopDisplayZone()
        contentColumn.addView(
            topDisplayZone,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        buildBottomControlDock()
        contentColumn.addView(
            bottomControlDock,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        buildModalOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildTopDisplayZone() {
        topDisplayZone.orientation = LinearLayout.VERTICAL
        topDisplayZone.gravity = Gravity.CENTER_HORIZONTAL

        passiveHeader.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }
        albumTitleView.apply {
            setTextColor(0xE6FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        qualityBadgeView.apply {
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = qualityBadgeGlass
            setPadding(dp(12), dp(4), dp(12), dp(4))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        routeBadgeView.apply {
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        passiveHeader.addView(albumTitleView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        passiveHeader.addView(
            qualityBadgeView,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        )
        passiveHeader.addView(
            routeBadgeView,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )
        topDisplayZone.addView(passiveHeader, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        buildCenterStage()
        topDisplayZone.addView(
            stageFrame,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
            }
        )

        var downY = 0f
        var downX = 0f
        var coverSwipeHandled = false
        passiveHeader.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - downY
                    val dx = event.x - downX
                    if (dy > touchSlop * 5 && dy > abs(dx) * 1.4f) {
                        onCollapse()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
        coverContainer.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    coverSwipeHandled = false
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!coverSwipeHandled) {
                        val dy = event.y - downY
                        val dx = event.x - downX
                        if (dy > touchSlop * 5 && dy > abs(dx) * 1.4f) {
                            coverSwipeHandled = true
                            onCollapse()
                            return@setOnTouchListener true
                        }
                        if (abs(dx) > touchSlop * 4 && abs(dx) > abs(dy) * 1.4f) {
                            coverSwipeHandled = true
                            triggerTrackNavigation(if (dx < 0f) 1 else -1)
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildCenterStage() {
        val coverOutlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 20f * density)
            }
        }
        stageFrame.apply {
            clipChildren = false
            clipToPadding = false
        }
        coverContainer.apply {
            background = null
            elevation = 0f
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        coverCard.apply {
            background = null
            elevation = 0f
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
            clipToOutline = false
        }
        coverPlaceholder.apply {
            background = null
            setImageResource(R.drawable.ic_default_album_cover)
            imageTintList = null
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(0, 0, 0, 0)
            outlineProvider = coverOutlineProvider
            clipToOutline = true
            elevation = 10f * density
        }
        coverImage.apply {
            background = null
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = coverOutlineProvider
            clipToOutline = true
            elevation = 10f * density
            visibility = View.GONE
        }
        coverCard.addView(coverPlaceholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        coverCard.addView(coverImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        coverContainer.addView(coverCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        coverContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val avail = minOf(v.width, v.height)
            if (avail > 0 && (coverCard.layoutParams.width != avail || coverCard.layoutParams.height != avail)) {
                coverCard.layoutParams = (coverCard.layoutParams as LayoutParams).apply {
                    width = avail
                    height = avail
                    gravity = Gravity.CENTER
                }
            }
        }

        vinylDiscView.apply {
            visibility = View.GONE
            onNext = { triggerTrackNavigation(1) }
            onPrevious = { triggerTrackNavigation(-1) }
            onSeekDeltaMs = { delta ->
                val eng = audioEngine
                if (eng != null) {
                    val target = (eng.getCurrentPositionMs() + delta).coerceIn(0L, eng.getDurationMs().coerceAtLeast(0L))
                    eng.seekTo(target)
                }
            }
            onCollapse = { this@NowPlayingView.onCollapse() }
        }

        lyricsAdapter = LyricsRecyclerAdapter(
            onLineTap = { line, _ ->
                if (zenModeActive) {
                    setZenMode(false)
                } else if (parsedLyrics.isSynced) {
                    audioEngine?.seekTo(line.timestampMs)
                    resumeLyricsAutoScroll(scrollNow = true)
                }
            },
            onSelectionChanged = { selected ->
                if (selected.isEmpty()) {
                    SpringAnimatorHelper.springDismiss(lyricQuoteBar, toTranslationY = dp(8).toFloat(), toScale = 0.96f)
                } else {
                    shareQuoteBtn.text = context.getString(R.string.share_card_fmt, selected.size)
                    if (lyricQuoteBar.visibility != View.VISIBLE) {
                        SpringAnimatorHelper.springReveal(lyricQuoteBar, fromTranslationY = dp(10).toFloat(), fromScale = 0.96f)
                    }
                }
            }
        )

        val zenGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (displayMode == DisplayMode.LYRICS_FOCUS && !lyricsAdapter.isSelectionActive()) {
                    setZenMode(!zenModeActive)
                    return true
                }
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (zenModeActive) {
                    setZenMode(false)
                    return true
                }
                return false
            }
        })

        val defaultFadePx = dp(44)
        lyricsRecycler.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = lyricsAdapter
            itemAnimator = null
            clipToPadding = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(defaultFadePx)
            addOnLayoutChangeListener { rv, _, top, _, bottom, _, _, _, _ ->
                val h = bottom - top
                if (h > 0) {
                    applyLyricsViewportPadding(h)
                    lyricsAdapter.setViewportMargins(
                        topPx = (h * 0.14f).toInt(),
                        bottomPx = dp(64),
                        defaultFadeLengthPx = defaultFadePx
                    )
                    val recycler = rv as RecyclerView
                    if (pendingInitialLyricSnap && lyricsAdapter.getLines().isNotEmpty()) {
                        pendingInitialLyricSnap = false
                        rv.post {
                            lyricsAdapter.snapToInitialAnchor(recycler)
                        }
                    }
                }
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING && parsedLyrics.isSynced && parsedLyrics.lines.isNotEmpty()) {
                        lyricsAdapter.cancelScrollAnimation(recyclerView)
                        pauseLyricsAutoScroll()
                    } else if (newState == RecyclerView.SCROLL_STATE_IDLE && manualScrollPaused) {
                        scheduleAutoResume()
                    }
                }
            })
            setOnTouchListener { _, ev ->
                zenGestureDetector.onTouchEvent(ev)
                false
            }
        }

        lyricsEmptyContainer.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(16), dp(20), dp(16))
            visibility = View.VISIBLE
            setOnTouchListener { _, ev ->
                zenGestureDetector.onTouchEvent(ev)
                true
            }
        }
        lyricsEmptyView.apply {
            text = context.getString(R.string.no_lyrics_available)
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            gravity = Gravity.CENTER
        }
        lyricsFetchStatusView.apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val emptyActionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        searchOnlineBtn.apply {
            text = context.getString(R.string.lyrics_search_online)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = searchOnlineGlass
            minimumHeight = dp(48)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener {
                val t = audioEngine?.getCurrentTrack() ?: return@setOnClickListener
                onSearchOnlineLyrics?.invoke(t)
            }
        }
        tapToSyncEmptyBtn.apply {
            text = context.getString(R.string.lyrics_create_offline)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = tapToSyncEmptyGlass
            minimumHeight = dp(48)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener {
                val t = audioEngine?.getCurrentTrack() ?: return@setOnClickListener
                onOpenTapToSync?.invoke(t, plainLyricsFallback)
            }
        }
        emptyActionsRow.addView(
            searchOnlineBtn,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(10) }
        )
        emptyActionsRow.addView(
            tapToSyncEmptyBtn,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48))
        )
        lyricsEmptyContainer.addView(lyricsEmptyView)
        lyricsEmptyContainer.addView(lyricsFetchStatusView)
        lyricsEmptyContainer.addView(
            emptyActionsRow,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        )

        returnToActivePill.apply {
            text = context.getString(R.string.return_to_active_lyric)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = returnPillGlass
            setPadding(dp(18), dp(10), dp(18), dp(10))
            elevation = 8f * density
            visibility = View.GONE
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { resumeLyricsAutoScroll(scrollNow = true) }
        }

        // Compact Lyric Tuner Toggle Icon Button (collapsed state)
        lyricTunerToggleBtn.apply {
            setImageResource(R.drawable.ic_tune)
            imageTintList = ColorStateList.valueOf(0xE6FFFFFF.toInt())
            background = lyricTunerToggleGlass
            contentDescription = context.getString(R.string.cd_lyric_tuner)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(13), dp(13), dp(13), dp(13))
            elevation = 6f * density
            visibility = View.GONE
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener {
                lyricTunerState.expand()
                updateLyricTunerVisibility(animate = true)
            }
        }

        // Expandable Lyric Tuner Panel (2 neat rows: Offset+Source, Anchor+Ubah+Simpan)
        lyricTunerPanel.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = lyricTunerPanelGlass
            setPadding(dp(10), dp(8), dp(10), dp(8))
            elevation = 10f * density
            visibility = View.GONE
        }

        lyricSourceBadgeTv.apply {
            setTextColor(0xB8FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(2), 0)
            visibility = View.GONE
        }

        offsetRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        configureCompactPillBtn(offsetMinusBtn, "-0.1s") { adjustLyricOffset(-100L) }
        offsetValueTv.apply {
            setTextColor(0xE6FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            fontFeatureSettings = "tnum"
            maxLines = 1
            setPadding(dp(6), 0, dp(6), 0)
        }
        configureCompactPillBtn(offsetPlusBtn, "+0.1s") { adjustLyricOffset(100L) }
        offsetRow.addView(offsetMinusBtn)
        offsetRow.addView(offsetValueTv)
        offsetRow.addView(offsetPlusBtn)
        offsetRow.addView(lyricSourceBadgeTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        anchorRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        configureAnchorPillBtn(anchorTopBtn, anchorTopBg, "28%") {
            setLyricHighlightFraction(PlaybackStateStore.LYRIC_ANCHOR_TOP)
            onLyricHighlightChanged?.invoke(lyricHighlightFraction)
        }
        configureAnchorPillBtn(anchorOptimalBtn, anchorOptimalBg, "36%") {
            setLyricHighlightFraction(PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL)
            onLyricHighlightChanged?.invoke(lyricHighlightFraction)
        }
        configureAnchorPillBtn(anchorCenterBtn, anchorCenterBg, "48%") {
            setLyricHighlightFraction(PlaybackStateStore.LYRIC_ANCHOR_CENTER)
            onLyricHighlightChanged?.invoke(lyricHighlightFraction)
        }
        configureAnchorPillBtn(tunerEditBtn, tunerEditBg, "Ubah") {
            val t = audioEngine?.getCurrentTrack() ?: return@configureAnchorPillBtn
            val options = arrayOf(
                context.getString(R.string.lyrics_replace_online),
                context.getString(R.string.lyrics_resync_offline)
            )
            android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setItems(options) { _, which ->
                    lyricTunerState.collapse()
                    updateLyricTunerVisibility(animate = true)
                    if (which == 0) {
                        onSearchOnlineLyrics?.invoke(t)
                    } else {
                        val currentText = parsedLyrics.lines.joinToString("\n") { it.text }
                        onOpenTapToSync?.invoke(t, currentText.ifBlank { plainLyricsFallback })
                    }
                }
                .show()
        }
        offsetSaveBtn.apply {
            text = context.getString(R.string.lyric_offset_save)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = offsetSaveBg
            minimumHeight = dp(32)
            minWidth = dp(68)
            maxLines = 1
            ellipsize = null
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener {
                val t = audioEngine?.getCurrentTrack()
                if (t != null) {
                    onSaveLyricOffset?.invoke(t, userLyricOffsetMs)
                    onSaveLyricSettings?.invoke(t, userLyricOffsetMs, lyricHighlightFraction)
                }
                onLyricHighlightChanged?.invoke(lyricHighlightFraction)
                lyricTunerState.onSavePressed()
                updateLyricTunerVisibility(animate = true)
            }
        }
        val anchorSpacing = dp(5)
        anchorRow.addView(anchorTopBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = anchorSpacing })
        anchorRow.addView(anchorOptimalBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = anchorSpacing })
        anchorRow.addView(anchorCenterBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = anchorSpacing })
        anchorRow.addView(tunerEditBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = anchorSpacing })
        anchorRow.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        anchorRow.addView(offsetSaveBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        lyricTunerPanel.addView(offsetRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        lyricTunerPanel.addView(
            anchorRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )

        // Lyric Quote Share Bar
        lyricQuoteBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = lyricQuoteGlass
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }
        configureCompactPillBtn(shareQuoteBtn, context.getString(R.string.share_card_fmt, 1)) {
            val t = audioEngine?.getCurrentTrack() ?: return@configureCompactPillBtn
            val selected = lyricsAdapter.getSelectedLines()
            if (selected.isNotEmpty()) {
                onShareLyricQuote?.invoke(t, selected)
                lyricsAdapter.clearSelection()
            }
        }
        configureCompactPillBtn(cancelQuoteBtn, context.getString(R.string.cancel_label)) {
            lyricsAdapter.clearSelection()
        }
        lyricQuoteBar.addView(shareQuoteBtn)
        lyricQuoteBar.addView(cancelQuoteBtn)

        val bottomLyricOverlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        bottomLyricOverlay.addView(
            returnToActivePill,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        )
        bottomLyricOverlay.addView(
            lyricQuoteBar,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        )
        bottomLyricOverlay.addView(
            lyricTunerPanel,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        lyricsContainer.addView(lyricsRecycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        lyricsContainer.addView(lyricsEmptyContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        lyricsContainer.addView(
            bottomLyricOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
                bottomMargin = dp(6)
            }
        )
        lyricsContainer.addView(
            lyricTunerToggleBtn,
            LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(4)
                bottomMargin = dp(6)
            }
        )

        stageFrame.addView(coverContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stageFrame.addView(vinylDiscView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stageFrame.addView(lyricsContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun configureCompactPillBtn(tv: TextView, label: String, onClick: () -> Unit) {
        tv.apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minimumHeight = dp(36)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onClick() }
        }
    }

    private fun configureAnchorPillBtn(
        tv: TextView,
        bg: GradientDrawable,
        label: String,
        onClick: () -> Unit
    ) {
        tv.apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = bg
            minimumHeight = dp(32)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onClick() }
        }
    }

    private fun updateHighlightAnchorButtons() {
        val activeTint = (0x59 shl 24) or (palette.accentGlow and 0x00FFFFFF)
        val inactiveTint = 0x1EFFFFFF
        fun styleAnchor(tv: TextView, bg: GradientDrawable, active: Boolean) {
            bg.setColor(if (active) activeTint else inactiveTint)
            tv.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
        }
        styleAnchor(anchorTopBtn, anchorTopBg, abs(lyricHighlightFraction - PlaybackStateStore.LYRIC_ANCHOR_TOP) < 0.03f)
        styleAnchor(anchorOptimalBtn, anchorOptimalBg, abs(lyricHighlightFraction - PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL) < 0.03f)
        styleAnchor(anchorCenterBtn, anchorCenterBg, abs(lyricHighlightFraction - PlaybackStateStore.LYRIC_ANCHOR_CENTER) < 0.03f)
    }

    private fun updateLyricTunerVisibility(animate: Boolean) {
        val hasLines = parsedLyrics.lines.isNotEmpty()
        if (!hasLines || zenModeActive) {
            if (animate) {
                SpringAnimatorHelper.springDismiss(lyricTunerPanel, toTranslationY = dp(10).toFloat(), toScale = 0.94f)
                SpringAnimatorHelper.springDismiss(lyricTunerToggleBtn, toScale = 0.90f)
            } else {
                SpringAnimatorHelper.cancelSpring(lyricTunerPanel)
                SpringAnimatorHelper.cancelSpring(lyricTunerToggleBtn)
                lyricTunerPanel.visibility = View.GONE
                lyricTunerToggleBtn.visibility = View.GONE
            }
            return
        }

        val syncVisibility = if (parsedLyrics.isSynced) View.VISIBLE else View.GONE
        offsetMinusBtn.visibility = syncVisibility
        offsetValueTv.visibility = syncVisibility
        offsetPlusBtn.visibility = syncVisibility
        offsetRow.visibility = if (parsedLyrics.isSynced || lyricSourceBadgeTv.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        anchorTopBtn.visibility = syncVisibility
        anchorOptimalBtn.visibility = syncVisibility
        anchorCenterBtn.visibility = syncVisibility
        anchorRow.visibility = View.VISIBLE

        if (lyricTunerState.isExpanded) {
            if (animate) {
                SpringAnimatorHelper.springDismiss(lyricTunerToggleBtn, toScale = 0.88f)
                SpringAnimatorHelper.springReveal(lyricTunerPanel, fromTranslationY = dp(14).toFloat(), fromScale = 0.94f)
            } else {
                lyricTunerToggleBtn.visibility = View.GONE
                lyricTunerPanel.visibility = View.VISIBLE
                lyricTunerPanel.alpha = 1f
                lyricTunerPanel.scaleX = 1f
                lyricTunerPanel.scaleY = 1f
                lyricTunerPanel.translationY = 0f
            }
        } else {
            if (animate) {
                SpringAnimatorHelper.springDismiss(lyricTunerPanel, toTranslationY = dp(14).toFloat(), toScale = 0.94f)
                SpringAnimatorHelper.springReveal(lyricTunerToggleBtn, fromTranslationY = dp(6).toFloat(), fromScale = 0.90f)
            } else {
                lyricTunerPanel.visibility = View.GONE
                lyricTunerToggleBtn.visibility = View.VISIBLE
                lyricTunerToggleBtn.alpha = 1f
                lyricTunerToggleBtn.scaleX = 1f
                lyricTunerToggleBtn.scaleY = 1f
                lyricTunerToggleBtn.translationY = 0f
            }
        }
    }

    private fun adjustLyricOffset(deltaMs: Long) {
        userLyricOffsetMs = (userLyricOffsetMs + deltaMs).coerceIn(-30_000L, 30_000L)
        updateLyricOffsetDisplay()
        val track = audioEngine?.getCurrentTrack()
        if (track != null) {
            onLiveLyricOffsetChanged?.invoke(track, userLyricOffsetMs)
        }
        val pos = audioEngine?.getCurrentPositionMs() ?: 0L
        updateActiveLyricForPosition(pos, forceScroll = true)
    }

    private fun updateLyricOffsetDisplay() {
        val sec = userLyricOffsetMs / 1000.0
        val sign = if (userLyricOffsetMs > 0L) "+" else ""
        val userLabel = String.format(java.util.Locale.US, "%s%.1fs", sign, sec)
        offsetValueTv.text = if (btLyricOffsetMs != 0L) "$userLabel (BT ${btLyricOffsetMs}ms)" else userLabel
    }

    private fun setZenMode(enabled: Boolean) {
        zenModeActive = enabled
        if (enabled) {
            SpringAnimatorHelper.springDismiss(passiveHeader, toTranslationY = -dp(12).toFloat(), toScale = 0.98f)
            SpringAnimatorHelper.springDismiss(bottomControlDock, toTranslationY = dp(18).toFloat(), toScale = 0.98f)
        } else {
            SpringAnimatorHelper.springReveal(passiveHeader, fromTranslationY = -dp(12).toFloat(), fromScale = 0.98f)
            SpringAnimatorHelper.springReveal(bottomControlDock, fromTranslationY = dp(18).toFloat(), fromScale = 0.98f)
        }
        updateLyricTunerVisibility(animate = true)
    }

    private fun buildBottomControlDock() {
        bottomControlDock.orientation = LinearLayout.VERTICAL
        bottomControlDock.clipChildren = false
        bottomControlDock.clipToPadding = false
        bottomControlDock.setPadding(0, dp(4), 0, 0)

        // Dock 1: Track Title & Artist + quick Favorit & Antrean (>= 48dp touch targets)
        val trackRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        trackTitleView.apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        trackArtistView.apply {
            setTextColor(0xA3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(trackTitleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        titleCol.addView(
            trackArtistView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
            }
        )

        favoriteButton.apply {
            setImageResource(R.drawable.ic_favorite_border)
            background = dockIconGlassFav
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = context.getString(R.string.cd_favorite)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { toggleCurrentFavorite() }
        }
        queueButton.apply {
            setImageResource(R.drawable.ic_queue)
            background = dockIconGlassQueue
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = context.getString(R.string.cd_queue)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { showQueueOverlay() }
        }

        trackRow.addView(titleCol, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        trackRow.addView(favoriteButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(8)
        })
        trackRow.addView(queueButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        bottomControlDock.addView(
            trackRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        )

        // Dock 2: WaveformPillSeekBarView
        seekWaveformView.setOnSeekListener { posMs ->
            audioEngine?.seekTo(posMs)
        }
        bottomControlDock.addView(
            seekWaveformView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        )

        // Dock 3: Main Transport Controls (>= 48dp touch targets) with detached sliding Play glass loupe
        transportHost.apply {
            clipChildren = false
            clipToPadding = false
        }
        playLoupeView.apply {
            background = playPauseGlass
        }
        transportHost.addView(playLoupeView, LayoutParams(dp(64), dp(64), Gravity.CENTER))

        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        shuffleButton.apply {
            setImageResource(R.drawable.ic_shuffle)
            background = null
            contentDescription = context.getString(R.string.cd_shuffle)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener {
                val eng = audioEngine ?: return@setOnClickListener
                eng.setShuffle(!eng.isShuffleEnabled())
                updateShuffleAndRepeatState()
            }
        }
        prevButton.apply {
            setImageResource(R.drawable.ic_prev)
            background = null
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = context.getString(R.string.cd_previous)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { triggerTrackNavigation(-1) }
        }
        playPauseGlass.setTintProfile(baseFraction = 0.25f, accentFraction = 0.85f, preferAccentGlow = true)
        playPauseButton.apply {
            setImageResource(R.drawable.ic_play)
            background = null
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = context.getString(R.string.cd_play_pause)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setOnClickListener { audioEngine?.togglePlayPause() }
            setOnTouchListener { v, event ->
                val nowMs = SystemClock.elapsedRealtime()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        playDragDownRawX = event.rawX
                        playDragDownRawY = event.rawY
                        lastPlayDragRawX = event.rawX
                        lastPlayDragTimeMs = nowMs
                        isPlayDragging = false
                        playLoupeController.onTouchDown()
                        mainHandler.removeCallbacks(playDwellRunnable)
                        SpringAnimatorHelper.cancelSpring(playLoupeView, CHANNEL_PLAY_DRAG)
                        animatePlayLoupeScale(1.07f)
                        playPauseGlass.setMotionState(0f, touchActive = true)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - playDragDownRawX
                        val dy = event.rawY - playDragDownRawY
                        if (!isPlayDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            if (abs(dx) >= abs(dy)) {
                                isPlayDragging = true
                            } else {
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        if (isPlayDragging) {
                            val dtSec = ((nowMs - lastPlayDragTimeMs).coerceAtLeast(1L)) / 1000f
                            val vx = (event.rawX - lastPlayDragRawX) / dtSec
                            lastPlayDragRawX = event.rawX
                            lastPlayDragTimeMs = nowMs

                            val buttonDistance = if (dx >= 0f) {
                                ((nextButton.left + nextButton.right) * 0.5f - (v.left + v.right) * 0.5f)
                            } else {
                                ((v.left + v.right) * 0.5f - (prevButton.left + prevButton.right) * 0.5f)
                            }.coerceAtLeast(playDragMaxOffsetPx)

                            val update = playLoupeController.onDragMove(
                                rawDxPx = dx,
                                thresholdPx = playDragThresholdPx,
                                maxOffsetPx = buttonDistance,
                                nowMs = nowMs
                            )
                            // Keep Play icon locked at 0f; slide only the glass loupe
                            v.translationX = playLoupeController.iconTranslationX
                            playLoupeView.translationX = update.loupeOffsetX
                            playPauseGlass.setMotionState(vx, touchActive = true)
                            updateTransportTargetHighlight(update.direction)

                            if (update.thresholdCrossed) {
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            if (update.cancelDwell) {
                                mainHandler.removeCallbacks(playDwellRunnable)
                            }
                            if (update.scheduleDwell) {
                                mainHandler.removeCallbacks(playDwellRunnable)
                                mainHandler.postDelayed(playDwellRunnable, PlayLoupeController.DWELL_DELAY_MS)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        mainHandler.removeCallbacks(playDwellRunnable)
                        val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                        val wasDrag = isPlayDragging
                        isPlayDragging = false
                        updateTransportTargetHighlight(0)
                        playPauseGlass.setMotionState(0f, touchActive = false)
                        animatePlayLoupeScale(1f)

                        val triggerDir = playLoupeController.onTouchUp(wasDrag, isCancel)
                        v.translationX = playLoupeController.iconTranslationX

                        if (wasDrag) {
                            SpringAnimatorHelper.animateSpring(
                                key = playLoupeView,
                                start = playLoupeView.translationX,
                                target = 0f,
                                stiffness = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.stiffness,
                                dampingRatio = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.dampingRatio,
                                valueThreshold = 0.25f,
                                channel = CHANNEL_PLAY_DRAG,
                                onUpdate = { tx -> playLoupeView.translationX = tx }
                            )
                            if (triggerDir != 0) {
                                triggerTrackNavigation(triggerDir)
                            }
                        } else if (!isCancel) {
                            v.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        nextButton.apply {
            setImageResource(R.drawable.ic_next)
            background = null
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = context.getString(R.string.cd_next)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { triggerTrackNavigation(1) }
        }
        repeatButton.apply {
            setImageResource(R.drawable.ic_repeat)
            background = null
            contentDescription = context.getString(R.string.cd_repeat)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener {
                audioEngine?.cycleRepeatMode()
                updateShuffleAndRepeatState()
            }
        }

        controlsRow.addView(shuffleButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        controlsRow.addView(prevButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        controlsRow.addView(playPauseButton, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            marginStart = dp(10)
            marginEnd = dp(10)
        })
        controlsRow.addView(nextButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        controlsRow.addView(repeatButton, LinearLayout.LayoutParams(0, dp(52), 1f))

        transportHost.addView(controlsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        bottomControlDock.addView(
            transportHost,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        )

        // Dock 4: Interactive 3-Mode Glass Scrub Pill (Sampul / Disk / Lirik)
        val modes = listOf(
            Triple(DisplayMode.FULL_COVER, R.string.mode_cover_short, R.drawable.ic_music_note),
            Triple(DisplayMode.VINYL_DISC, R.string.mode_disc_short, R.drawable.ic_disc),
            Triple(DisplayMode.LYRICS_FOCUS, R.string.mode_lyrics_short, R.drawable.ic_lyrics)
        )
        modeSlotViews.clear()
        for ((mode, labelRes, iconRes) in modes) {
            modeSlotViews.add(ModeSlotView(context, mode, context.getString(labelRes), iconRes))
        }
        modeScrubPill.setSlots(modeSlotViews)
        modeScrubPill.onSlotPreviewChanged = { idx ->
            for (i in 0 until modeSlotViews.size) {
                modeSlotViews[i].setSelectedState(i == idx, palette)
            }
        }
        modeScrubPill.onSlotSelected = { idx ->
            val mode = modeSlotViews.getOrNull(idx)?.mode
            if (mode != null && mode != displayMode) {
                setDisplayMode(mode, animate = true, syncScrub = false)
            } else {
                for (slot in modeSlotViews) {
                    slot.setSelectedState(slot.mode == displayMode, palette)
                }
            }
        }
        bottomControlDock.addView(
            modeScrubPill,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply {
                bottomMargin = dp(8)
            }
        )

        // Dock 5: 3 Utility Glass Pills (Audio Lab, Timer, AMOLED)
        secondaryDockRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        configureSecondaryPill(
            container = labPill,
            iconView = labPillIcon,
            textView = labPillText,
            glass = labPillGlass,
            iconRes = R.drawable.ic_tune,
            label = context.getString(R.string.audio_lab_short)
        ) {
            onOpenAudioLab?.invoke()
        }
        configureSecondaryPill(
            container = timerPill,
            iconView = timerPillIcon,
            textView = timerPillText,
            glass = timerPillGlass,
            iconRes = R.drawable.ic_timer,
            label = context.getString(R.string.timer_short)
        ) {
            showSleepTimerOverlay()
        }
        configureSecondaryPill(
            container = amoledPill,
            iconView = amoledPillIcon,
            textView = amoledPillText,
            glass = amoledPillGlass,
            iconRes = R.drawable.ic_night,
            label = context.getString(R.string.amoled_short)
        ) {
            onToggleAmoled?.invoke()
        }

        secondaryDockRow.addView(labPill, LinearLayout.LayoutParams(0, dp(48), 1.15f).apply { marginEnd = dp(6) })
        secondaryDockRow.addView(timerPill, LinearLayout.LayoutParams(0, dp(48), 0.95f).apply { marginEnd = dp(6) })
        secondaryDockRow.addView(amoledPill, LinearLayout.LayoutParams(0, dp(48), 0.95f))

        bottomControlDock.addView(
            secondaryDockRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    private fun configureSecondaryPill(
        container: LinearLayout,
        iconView: ImageView,
        textView: TextView,
        glass: LiquidGlassDrawable,
        iconRes: Int,
        label: String,
        onClick: () -> Unit
    ) {
        container.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = glass
            minimumHeight = dp(48)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onClick() }
        }
        iconView.apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(0xE6FFFFFF.toInt())
        }
        textView.apply {
            text = label
            setTextColor(0xE6FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(5), 0, 0, 0)
        }
        container.addView(iconView, LinearLayout.LayoutParams(dp(16), dp(16)))
        container.addView(textView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildModalOverlay() {
        modalBackdrop.apply {
            setBackgroundColor(0x9905070B.toInt())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeModal() }
        }

        modalCard.apply {
            orientation = LinearLayout.VERTICAL
            background = modalCardGlass
            setPadding(dp(20), dp(20), dp(20), dp(16))
            elevation = 20f * density
            isClickable = true
        }

        modalTitleView.apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        modalBody.orientation = LinearLayout.VERTICAL
        modalActionsRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        modalCard.addView(modalTitleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        modalCard.addView(
            modalBody,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
                bottomMargin = dp(14)
            }
        )
        modalCard.addView(modalActionsRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        modalBackdrop.addView(
            modalCard,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                bottomMargin = dp(24)
            }
        )
        addView(modalBackdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun setDisplayMode(mode: DisplayMode, animate: Boolean, syncScrub: Boolean = true) {
        val prevMode = displayMode
        displayMode = mode
        if (syncScrub) {
            modeScrubPill.setSelectedIndex(mode.ordinal, animate = animate)
        }
        if (mode != DisplayMode.LYRICS_FOCUS && zenModeActive) {
            setZenMode(false)
        }

        val targetView = when (mode) {
            DisplayMode.FULL_COVER -> coverContainer
            DisplayMode.VINYL_DISC -> vinylDiscView
            DisplayMode.LYRICS_FOCUS -> lyricsContainer
        }
        val allViews = arrayOf(coverContainer, vinylDiscView, lyricsContainer)
        val direction = (mode.ordinal - prevMode.ordinal).coerceIn(-1, 1).toFloat()
        val shiftPx = direction * dp(22)

        if (animate) {
            for (v in allViews) {
                if (v === targetView) {
                    SpringAnimatorHelper.springReveal(
                        v,
                        fromTranslationX = shiftPx,
                        fromScale = 0.96f
                    )
                } else if (v.visibility == View.VISIBLE) {
                    SpringAnimatorHelper.springDismiss(
                        v,
                        toTranslationX = -shiftPx * 0.6f,
                        toScale = 0.96f
                    )
                }
            }
        } else {
            for (v in allViews) {
                SpringAnimatorHelper.cancelSpring(v)
                v.visibility = if (v === targetView) View.VISIBLE else View.GONE
                v.alpha = if (v === targetView) 1f else 0f
                v.scaleX = 1f
                v.scaleY = 1f
                v.translationX = 0f
            }
        }

        if (mode == DisplayMode.LYRICS_FOCUS) {
            lyricTunerState.onEnterLyricsMode()
            updateLyricTunerVisibility(animate = false)
            if (!manualScrollPaused && lyricsAdapter.getLines().isNotEmpty()) {
                lyricsRecycler.post {
                    applyLyricsViewportPadding(lyricsRecycler.height)
                    val active = lyricsAdapter.getActiveIndex()
                    if (active >= 0) {
                        lyricsAdapter.scrollToActiveLine(lyricsRecycler, active, animated = false)
                    } else {
                        lyricsAdapter.snapToInitialAnchor(lyricsRecycler)
                    }
                }
            }
        }
        onVisualizerDemandChanged?.invoke(isVisualizerDemanded())
        updateSecondaryPillStates()
    }

    private fun toggleCurrentFavorite() {
        val track = audioEngine?.getCurrentTrack() ?: return
        val key = track.path.ifBlank { "id:${track.id}" }
        if (favoritePaths.contains(key)) {
            favoritePaths.remove(key)
        } else {
            favoritePaths.add(key)
        }
        prefs.edit().putStringSet(KEY_FAVORITES, HashSet(favoritePaths)).apply()
        updateFavoriteButton(track)
    }

    private fun updateFavoriteButton(track: Track?) {
        val key = track?.let { it.path.ifBlank { "id:${it.id}" } }
        val isFav = key != null && favoritePaths.contains(key)
        favoriteButton.setImageResource(if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        favoriteButton.imageTintList = ColorStateList.valueOf(
            if (isFav) palette.accentGlow else 0xCCFFFFFF.toInt()
        )
    }

    private fun updateActiveLyricForPosition(positionMs: Long, forceScroll: Boolean) {
        if (!parsedLyrics.isSynced || parsedLyrics.lines.isEmpty()) return
        val newIndex = LrcParser.findActiveLineIndex(
            parsedLyrics.lines,
            positionMs,
            userOffsetMs = userLyricOffsetMs,
            bluetoothLatencyOffsetMs = btLyricOffsetMs
        )
        val shouldScroll = !manualScrollPaused && (displayMode == DisplayMode.LYRICS_FOCUS || forceScroll)
        lyricsAdapter.setActiveIndex(newIndex, lyricsRecycler, autoScroll = shouldScroll)
    }

    private fun pauseLyricsAutoScroll() {
        manualScrollPaused = true
        if (returnToActivePill.visibility != View.VISIBLE) {
            SpringAnimatorHelper.springReveal(returnToActivePill, fromTranslationY = dp(8).toFloat(), fromScale = 0.94f)
        }
        scheduleAutoResume()
    }

    private fun scheduleAutoResume() {
        mainHandler.removeCallbacks(autoResumeRunnable)
        mainHandler.postDelayed(autoResumeRunnable, AUTO_RESUME_DELAY_MS)
    }

    private fun resumeLyricsAutoScroll(scrollNow: Boolean) {
        mainHandler.removeCallbacks(autoResumeRunnable)
        manualScrollPaused = false
        SpringAnimatorHelper.springDismiss(returnToActivePill, toTranslationY = dp(8).toFloat(), toScale = 0.94f)
        if (scrollNow) {
            val idx = lyricsAdapter.getActiveIndex()
            if (idx >= 0) {
                lyricsAdapter.scrollToActiveLine(lyricsRecycler, idx, animated = true)
            }
        }
    }

    private fun updateShuffleAndRepeatState() {
        val eng = audioEngine
        val shuffled = eng?.isShuffleEnabled() == true
        val repeat = eng?.getRepeatMode() ?: RepeatMode.OFF

        shuffleButton.imageTintList = ColorStateList.valueOf(
            if (shuffled) palette.accentGlow else 0x80FFFFFF.toInt()
        )
        repeatButton.setImageResource(
            if (repeat == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat
        )
        repeatButton.imageTintList = ColorStateList.valueOf(
            if (repeat == RepeatMode.OFF) 0x80FFFFFF.toInt() else palette.accentGlow
        )
    }

    private fun updateSecondaryPillStates() {
        for (slot in modeSlotViews) {
            slot.setSelectedState(slot.mode == displayMode, palette)
        }

        val dspActive = audioEngine?.isTenBandEqEnabled() == true ||
            (audioEngine?.getDspConfig()?.crossfeedLevel ?: 0f) > 0.01f
        labPillGlass.setTintProfile(
            baseFraction = 0.56f,
            accentFraction = if (dspActive) 0.38f else 0.10f,
            preferAccentGlow = dspActive
        )
        labPillIcon.imageTintList = ColorStateList.valueOf(
            if (dspActive) palette.accentGlow else 0xE6FFFFFF.toInt()
        )
        labPillText.setTextColor(if (dspActive) palette.accentGlow else 0xE6FFFFFF.toInt())

        val remMs = sleepTimerEndElapsedMs - SystemClock.elapsedRealtime()
        val timerActive = remMs > 0L
        timerPillText.text = if (timerActive) {
            "${ceil(remMs / 60_000.0).toInt().coerceAtLeast(1)}m"
        } else {
            context.getString(R.string.timer_short)
        }
        timerPillGlass.setTintProfile(
            baseFraction = 0.56f,
            accentFraction = 0.10f,
            preferAccentGlow = false
        )
        timerPillIcon.imageTintList = ColorStateList.valueOf(
            if (timerActive) palette.accentGlow else 0xE6FFFFFF.toInt()
        )
        timerPillText.setTextColor(if (timerActive) palette.accentGlow else 0xE6FFFFFF.toInt())

        amoledPillGlass.setTintProfile(
            baseFraction = 0.56f,
            accentFraction = 0.10f,
            preferAccentGlow = false
        )
        amoledPillIcon.imageTintList = ColorStateList.valueOf(
            if (amoledEnabled) palette.accentGlow else 0xE6FFFFFF.toInt()
        )
        amoledPillText.setTextColor(if (amoledEnabled) palette.accentGlow else 0xE6FFFFFF.toInt())
    }

    private fun applyPalette(newPalette: CoverPalette) {
        qualityBadgeGlass.setPalette(newPalette)
        vinylDiscView.setPalette(newPalette)
        returnPillGlass.setPalette(newPalette)
        returnPillGlass.setTintProfile(baseFraction = 0.65f, accentFraction = 0.25f, preferAccentGlow = true)
        lyricTunerToggleGlass.setPalette(newPalette)
        lyricTunerToggleGlass.setTintProfile(baseFraction = 0.66f, accentFraction = 0.22f, preferAccentGlow = true)
        lyricTunerPanelGlass.setPalette(newPalette)
        lyricTunerPanelGlass.setTintProfile(baseFraction = 0.78f, accentFraction = 0.18f)
        searchOnlineGlass.setPalette(newPalette)
        searchOnlineGlass.setTintProfile(baseFraction = 0.35f, accentFraction = 0.48f, preferAccentGlow = true)
        tapToSyncEmptyGlass.setPalette(newPalette)
        tapToSyncEmptyGlass.setTintProfile(baseFraction = 0.56f, accentFraction = 0.18f)
        lyricQuoteGlass.setPalette(newPalette)
        lyricQuoteGlass.setTintProfile(baseFraction = 0.72f, accentFraction = 0.35f, preferAccentGlow = true)
        dockIconGlassFav.setPalette(newPalette)
        dockIconGlassQueue.setPalette(newPalette)
        playPauseGlass.setPalette(newPalette)
        modeScrubPill.setPalette(newPalette)
        labPillGlass.setPalette(newPalette)
        timerPillGlass.setPalette(newPalette)
        amoledPillGlass.setPalette(newPalette)
        lyricTunerPanelGlass.setSurfaceScale(LiquidGlassDrawable.GlassSurfaceScale.LARGE)
        modalCardGlass.setPalette(newPalette)
        modalCardGlass.setSurfaceScale(LiquidGlassDrawable.GlassSurfaceScale.LARGE)
        modalCardGlass.setTintProfile(baseFraction = 0.85f, accentFraction = 0.15f)
        seekWaveformView.setPalette(newPalette)
        lyricsAdapter.setPalette(newPalette)
        tunerEditBg.setColor(0x1EFFFFFF)
        tunerEditBtn.setTextColor(0xE6FFFFFF.toInt())
        offsetSaveBg.setColor((0x66 shl 24) or (newPalette.accentGlow and 0x00FFFFFF))
        updateHighlightAnchorButtons()
        updateFavoriteButton(audioEngine?.getCurrentTrack())
        updateShuffleAndRepeatState()
        updateSecondaryPillStates()
    }

    private fun revealModal() {
        SpringAnimatorHelper.springReveal(modalBackdrop, fromScale = 1f)
        SpringAnimatorHelper.springReveal(modalCard, fromTranslationY = dp(28).toFloat(), fromScale = 0.96f)
    }

    private fun showQueueOverlay() {
        modalBody.removeAllViews()
        modalActionsRow.removeAllViews()
        modalTitleView.text = context.getString(R.string.queue_title)

        val eng = audioEngine
        val liveQueue = eng?.getQueue()?.takeIf { it.isNotEmpty() } ?: currentQueue
        val activeIdx = eng?.getCurrentQueueIndex() ?: -1

        if (liveQueue.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = context.getString(R.string.queue_empty)
                setTextColor(0xA3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            modalBody.addView(emptyTv)
        } else {
            val queueRv = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                itemAnimator = null
            }
            lateinit var qAdapter: QueueOverlayAdapter
            qAdapter = QueueOverlayAdapter(
                items = liveQueue.toMutableList(),
                activeIndex = activeIdx,
                palette = palette,
                onSelect = { idx ->
                    eng?.playTrack(idx)
                    closeModal()
                },
                onMoveUp = { idx ->
                    if (idx > 0 && eng != null) {
                        eng.moveQueueItem(idx, idx - 1)
                        qAdapter.replaceItems(eng.getQueue(), eng.getCurrentQueueIndex())
                    }
                },
                onMoveDown = { idx ->
                    if (eng != null && idx < qAdapter.itemCount - 1) {
                        eng.moveQueueItem(idx, idx + 1)
                        qAdapter.replaceItems(eng.getQueue(), eng.getCurrentQueueIndex())
                    }
                },
                onRemove = { idx ->
                    if (eng != null) {
                        eng.removeQueueItem(idx)
                        qAdapter.replaceItems(eng.getQueue(), eng.getCurrentQueueIndex())
                    }
                }
            )
            queueRv.adapter = qAdapter
            val maxH = (resources.displayMetrics.heightPixels * 0.44f).toInt()
            modalBody.addView(queueRv, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, maxH))
        }

        modalActionsRow.addView(createModalActionButton(context.getString(R.string.eq_close)) { closeModal() })
        revealModal()
    }

    private fun showSleepTimerOverlay() {
        val eng = audioEngine ?: return
        modalBody.removeAllViews()
        modalActionsRow.removeAllViews()
        modalTitleView.text = context.getString(R.string.timer_title)

        val remainingMs = sleepTimerEndElapsedMs - SystemClock.elapsedRealtime()
        if (remainingMs > 0L) {
            val mins = ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)
            val statusTv = TextView(context).apply {
                text = context.getString(R.string.timer_active_fmt, mins)
                setTextColor(palette.accentGlow)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, 0, dp(10))
            }
            modalBody.addView(statusTv)
        }

        val options = listOf(
            0 to context.getString(R.string.timer_off),
            15 to context.getString(R.string.timer_15_min),
            30 to context.getString(R.string.timer_30_min),
            45 to context.getString(R.string.timer_45_min),
            60 to context.getString(R.string.timer_60_min)
        )
        for ((minutes, label) in options) {
            val pill = TextView(context).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minimumHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(LiquidGlassDrawable.LayerTokens.nestedCardColor(isDarkMode = true))
                    cornerRadius = 16f * density
                }
                setPadding(dp(16), dp(12), dp(16), dp(12))
                SpringAnimatorHelper.attachPressFeedback(this)
                setOnClickListener {
                    mainHandler.removeCallbacks(sleepDisplayTicker)
                    if (minutes <= 0) {
                        sleepTimerEndElapsedMs = 0L
                        eng.cancelSleepTimer()
                    } else {
                        val durationMs = minutes * 60_000L
                        sleepTimerEndElapsedMs = SystemClock.elapsedRealtime() + durationMs
                        eng.startSleepTimer(durationMs)
                        mainHandler.post(sleepDisplayTicker)
                    }
                    updateSecondaryPillStates()
                    closeModal()
                }
            }
            modalBody.addView(
                pill,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            )
        }

        modalActionsRow.addView(createModalActionButton(context.getString(R.string.eq_close)) { closeModal() })
        revealModal()
    }

    private fun createModalActionButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0x26FFFFFF)
                cornerRadius = 14f * density
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
    }

    private fun animatePlayLoupeScale(targetScale: Float) {
        SpringAnimatorHelper.animateSpring(
            key = playLoupeView,
            start = playLoupeView.scaleX,
            target = targetScale,
            stiffness = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.stiffness,
            dampingRatio = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.dampingRatio,
            channel = 0,
            onUpdate = { s ->
                playLoupeView.scaleX = s
                playLoupeView.scaleY = s
            }
        )
    }

    private fun updateTransportTargetHighlight(direction: Int) {
        val prevScale = if (direction < 0) 1.08f else 1f
        val nextScale = if (direction > 0) 1.08f else 1f
        if (prevButton.scaleX != prevScale) {
            SpringAnimatorHelper.springScale(prevButton, prevScale)
        }
        if (nextButton.scaleX != nextScale) {
            SpringAnimatorHelper.springScale(nextButton, nextScale)
        }
        prevButton.imageTintList = ColorStateList.valueOf(
            if (direction < 0) palette.accentGlow else 0xFFFFFFFF.toInt()
        )
        nextButton.imageTintList = ColorStateList.valueOf(
            if (direction > 0) palette.accentGlow else 0xFFFFFFFF.toInt()
        )
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private class ModeSlotView(
        context: Context,
        val mode: DisplayMode,
        label: String,
        iconRes: Int
    ) : LinearLayout(context) {

        private val density = resources.displayMetrics.density
        private val iconView = ImageView(context)
        private val labelView = TextView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = (36 * density).toInt()
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())

            iconView.setImageResource(iconRes)
            labelView.apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding((5 * density).toInt(), 0, 0, 0)
            }

            addView(iconView, LayoutParams((15 * density).toInt(), (15 * density).toInt()))
            addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        fun setSelectedState(selected: Boolean, palette: CoverPalette) {
            if (selected) {
                iconView.imageTintList = ColorStateList.valueOf(palette.accentGlow)
                labelView.setTextColor(0xFFFFFFFF.toInt())
            } else {
                iconView.imageTintList = ColorStateList.valueOf(0x99FFFFFF.toInt())
                labelView.setTextColor(0x99FFFFFF.toInt())
            }
        }
    }

    private class QueueOverlayAdapter(
        private var items: MutableList<Track>,
        private var activeIndex: Int,
        private val palette: CoverPalette,
        private val onSelect: (Int) -> Unit,
        private val onMoveUp: (Int) -> Unit,
        private val onMoveDown: (Int) -> Unit,
        private val onRemove: (Int) -> Unit
    ) : RecyclerView.Adapter<QueueOverlayAdapter.VH>() {

        fun replaceItems(newItems: List<Track>, newActiveIndex: Int) {
            items = newItems.toMutableList()
            activeIndex = newActiveIndex
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val dp = { v: Int -> (v * density).toInt() }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    setColor(LiquidGlassDrawable.LayerTokens.nestedCardColor(isDarkMode = true))
                    cornerRadius = 14f * density
                }
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = TYPEFACE_MEDIUM
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val artist = TextView(ctx).apply {
                setTextColor(0xA3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(title)
            textCol.addView(artist)

            fun makeMiniBtn(label: String): TextView = TextView(ctx).apply {
                text = label
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                minimumWidth = dp(34)
                minimumHeight = dp(36)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            val upBtn = makeMiniBtn("\u2191")
            val downBtn = makeMiniBtn("\u2193")
            val delBtn = makeMiniBtn("\u2715")

            row.addView(textCol, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(upBtn)
            row.addView(downBtn)
            row.addView(delBtn)

            val holder = VH(row, title, artist, upBtn, downBtn, delBtn)
            textCol.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in items.indices) onSelect(pos)
            }
            upBtn.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in items.indices) onMoveUp(pos)
            }
            downBtn.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in items.indices) onMoveDown(pos)
            }
            delBtn.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in items.indices) onRemove(pos)
            }
            return holder
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val track = items[position]
            val isCurrent = position == activeIndex
            holder.title.text = track.title
            holder.title.setTextColor(if (isCurrent) palette.accentGlow else 0xFFFFFFFF.toInt())
            holder.artist.text = track.artist
        }

        class VH(
            itemView: View,
            val title: TextView,
            val artist: TextView,
            val upBtn: TextView,
            val downBtn: TextView,
            val delBtn: TextView
        ) : RecyclerView.ViewHolder(itemView)

        private companion object {
            val TYPEFACE_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    private companion object {
        const val AUTO_RESUME_DELAY_MS = 3_000L
        const val PREFS_NAME = "laguku_prefs"
        const val KEY_FAVORITES = "favorite_paths"
        const val CHANNEL_PLAY_DRAG = 21
        const val CHANNEL_AMOLED = 22
        const val CHANNEL_ARTIST_SLIDE = 23
    }
}
