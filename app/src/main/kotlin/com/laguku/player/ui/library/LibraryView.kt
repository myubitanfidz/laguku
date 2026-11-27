package com.laguku.player.ui.library

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laguku.player.R
import com.laguku.player.data.LibraryScanner
import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.model.AlbumGroup
import com.laguku.player.model.ArtistGroup
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.FolderNode
import com.laguku.player.model.Playlist
import com.laguku.player.model.Track
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.InteractiveGlassScrubView
import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.motion.LiquidGlassPressAnimator
import com.laguku.player.ui.motion.PageMotionSpec
import com.laguku.player.ui.motion.SpringAnimatorHelper
import com.laguku.player.ui.player.NowPlayingView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Main library browser following the Bottom-Half Thumb-Zone Layout:
 * - Top-to-middle area: passive header + scrollable RecyclerView for Lagu, Folder, Album, Artis, Playlist.
 * - Bottom dock anchored to bottom with WindowInsets navigationBars safe padding:
 *   1) Floating Mini Player glass pill directly above the bottom action/tab area
 *   2) Collapsible inline search box + Quick action strip (Putar Acak Semua + Cari, plus .m3u8 actions on Playlist)
 *   3) 5 Glass Capsule Tabs (Lagu, Folder, Album, Artis, Playlist) anchored at the very bottom.
 */
@SuppressLint("ViewConstructor")
class LibraryView(
    context: Context,
    private val callbacks: Callbacks
) : FrameLayout(context) {

    interface Callbacks {
        fun onPlayTracks(tracks: List<Track>, startIndex: Int)
        fun onShufflePlayAll(tracks: List<Track>)
        fun onTogglePlayPause()
        fun onNextTrack()
        fun onPreviousTrack()
        fun onOpenNowPlaying()
        fun onRequestStoragePermission()
        fun onImportM3u8Clicked()
        fun onExportM3u8Clicked()
        fun onCreatePlaylistClicked()
        fun onOpenUtilitiesClicked()
        fun onEnqueueTrack(track: Track)
        fun onPlayNextTrack(track: Track)
        fun onEditTrackTags(track: Track)
        fun onTogglePinFolder(folderPath: String)
        fun onBlacklistFolder(folderPath: String)
        fun onRefreshFolder(folderPath: String)
    }

    enum class Tab { SONGS, FOLDERS, ALBUMS, ARTISTS, PLAYLISTS }

    object NavBarScrollMath {
        const val COMPACT_SCALE = 0.94f
        const val SCROLL_THRESHOLD_PX = 16

        fun resolveCompactState(
            currentlyCompact: Boolean,
            dy: Int,
            thresholdPx: Int = SCROLL_THRESHOLD_PX
        ): Boolean {
            if (dy > thresholdPx) return true
            if (dy < -thresholdPx) return false
            return currentlyCompact
        }
    }

    private sealed class ListEntry {
        data class TrackEntry(val track: Track, val queue: List<Track>, val indexInQueue: Int) : ListEntry()
        data class FolderEntry(val node: FolderNode) : ListEntry()
        data class AlbumEntry(val group: AlbumGroup) : ListEntry()
        data class ArtistEntry(val group: ArtistGroup) : ListEntry()
        data class PlaylistEntry(val playlist: Playlist) : ListEntry()
    }

    private val density = resources.displayMetrics.density
    private var palette: CoverPalette = CoverPaletteExtractor.defaultPalette()

    private var allTracks: List<Track> = emptyList()
    private var folders: List<FolderNode> = emptyList()
    private var albums: List<AlbumGroup> = emptyList()
    private var artists: List<ArtistGroup> = emptyList()
    private var playlists: List<Playlist> = emptyList()

    private var activeTab: Tab = Tab.SONGS
    private var searchQuery: String = ""
    private var drillDownTitle: String? = null
    private var drillDownTracks: List<Track>? = null
    private var pinnedFolderPaths: Set<String> = emptySet()

    private val mainColumn = LinearLayout(context)

    // Top Passive Header
    private val headerTitleView = TextView(context)
    private val headerSubtitleView = TextView(context)

    // Content list (top-to-middle)
    private val listContainer = FrameLayout(context)
    private val recyclerView = RecyclerView(context)
    private val emptyStateView = TextView(context)
    private val permissionCard = LinearLayout(context)
    private val permissionCardBg = GradientDrawable().apply {
        setColor(LiquidGlassDrawable.LayerTokens.contentCardColor(isDarkMode = true))
        cornerRadius = 20f * resources.displayMetrics.density
    }
    private val permissionGrantBtn = TextView(context)

    private val listAdapter = LibraryAdapter(
        onEntryClick = { entry -> handleEntryClick(entry) },
        onEntryLongClick = { entry -> handleEntryLongClick(entry) },
        onThumbDecoded = { scheduleBackdropSample() }
    )

    // Bottom Dock (Thumb-Zone)
    private val bottomDock = LinearLayout(context)

    // Floating Mini Player Pill (locked directly above quick action + bottom tabs)
    private val miniPlayerPill = FrameLayout(context)
    private val miniPlayerGlass = LiquidGlassDrawable(context, 22f)
    private val miniCoverCard = FrameLayout(context)
    private val miniCoverPlaceholder = ImageView(context)
    private val miniCoverImage = ImageView(context)
    private val miniTitleView = TextView(context)
    private val miniArtistView = TextView(context)
    private val miniPlayPauseBtn = ImageButton(context)
    private val miniProgressBar = MiniProgressBarView(context)

    // Inline Search Box (inside bottom dock for thumb reach)
    private val searchBox = EditText(context)
    private val searchGlass = LiquidGlassDrawable(context, 18f)

    // Quick Action Strip (Putar Acak Semua + Cari + Drill-down back / .m3u8 actions)
    private val quickActionRow = LinearLayout(context)
    private val drillBackBtn = TextView(context)
    private val shuffleAllPill = LinearLayout(context)
    private val shuffleAllIcon = ImageView(context)
    private val shuffleAllText = TextView(context)
    private val shuffleAllGlass = LiquidGlassDrawable(context, 24f)

    private val utilitiesPill = LinearLayout(context)
    private val utilitiesIcon = ImageView(context)
    private val utilitiesGlass = LiquidGlassDrawable(context, 24f)

    private val playlistActionsRow = LinearLayout(context)
    private val importBtn = TextView(context)
    private val exportBtn = TextView(context)
    private val createPlaylistBtn = TextView(context)

    // Bottom 5-tab interactive glass scrub bar + detached circular Search capsule
    private val bottomNavRow = LinearLayout(context)
    private val bottomScrubBar = InteractiveGlassScrubView(context, 24f)
    private val searchCapsuleBtn = ImageButton(context)
    private val searchCapsuleGlass = LiquidGlassDrawable(context, 28f)
    private val topDissolutionEdge = View(context)
    private val bottomDissolutionEdge = View(context)
    private val tabViews = ArrayList<TabItemView>(5)
    private var isNavBarCompact: Boolean = false

    private val navSampleDim = LiquidGlassDrawable.TabBackdropSamplerMath.clampSampleDimension(
        LiquidGlassDrawable.TabBackdropSamplerMath.DEFAULT_SAMPLE_DIM_PX
    )
    private val navSampleBitmap: Bitmap? = runCatching {
        Bitmap.createBitmap(navSampleDim, navSampleDim, Bitmap.Config.ARGB_8888)
    }.getOrNull()
    private val navSampleCanvas: Canvas? = navSampleBitmap?.let { Canvas(it) }
    private val navSamplePixels = IntArray(navSampleDim * navSampleDim)
    private val navSampleDstRect = RectF()
    private val navSamplePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tmpNavLocation = IntArray(2)
    private val tmpChildLocation = IntArray(2)
    private var smoothedNavArgb: Int = LiquidGlassDrawable.LayerTokens.DARK_BASE_GROUND
    private var smoothedNavLum: Float = 0.12f
    private val sampleBackdropRunnable = Runnable { sampleBottomTabBackdrop() }
    private var hostActive: Boolean = true
    private var pendingStaggerEntrance: Boolean = false
    private val staggerHandler = Handler(Looper.getMainLooper())
    private val staggerRunnables = ArrayList<Runnable>(12)
    private val staggerPostRunnable = Runnable { runVisibleRowsStagger() }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val miniSwipeController = NowPlayingView.HorizontalSwipeCommitController()
    private val miniSwipeThresholdPx: Float = PageMotionSpec.SWIPE_COMMIT_THRESHOLD_DP * density
    private val miniSwipeMaxOffsetPx: Float = NowPlayingView.HorizontalSwipeCommitController.MAX_SWIPE_OFFSET_DP * density

    init {
        buildUi()
        applyPalette(palette)
        selectTab(Tab.SONGS)
    }

    fun getUtilitiesAnchorView(): View = utilitiesPill

    fun setHostActive(active: Boolean) {
        hostActive = active
        if (!active) {
            bottomScrubBar.cancelActiveInteractions()
            SpringAnimatorHelper.cancelSpring(bottomScrubBar, CHANNEL_NAV_COMPACT)
            SpringAnimatorHelper.cancelSpring(miniPlayerPill, CHANNEL_MINI_SWIPE)
            miniPlayerPill.translationX = 0f
            cancelVisibleRowsStagger()
        }
    }

    override fun onDetachedFromWindow() {
        bottomScrubBar.cancelActiveInteractions()
        SpringAnimatorHelper.cancelSpring(bottomScrubBar, CHANNEL_NAV_COMPACT)
        cancelVisibleRowsStagger()
        super.onDetachedFromWindow()
    }

    fun applySystemInsets(topPx: Int, bottomPx: Int) {
        mainColumn.setPadding(
            dp(16),
            topPx + dp(10),
            dp(16),
            bottomPx + dp(10)
        )
    }

    fun setPalette(newPalette: CoverPalette) {
        palette = newPalette
        applyPalette(newPalette)
    }

    fun setGlassTransparency(level: Float) {
        searchGlass.setContinuousTransparency(level)
        (drillBackBtn.background as? LiquidGlassDrawable)?.setContinuousTransparency(level)
        shuffleAllGlass.setContinuousTransparency(level)
        searchCapsuleGlass.setContinuousTransparency(level)
        utilitiesGlass.setContinuousTransparency(level)
        (permissionGrantBtn.background as? LiquidGlassDrawable)?.setContinuousTransparency(level)
        (importBtn.background as? LiquidGlassDrawable)?.setContinuousTransparency(level)
        (exportBtn.background as? LiquidGlassDrawable)?.setContinuousTransparency(level)
        (createPlaylistBtn.background as? LiquidGlassDrawable)?.setContinuousTransparency(level)
        miniPlayerGlass.setContinuousTransparency(level)
        bottomScrubBar.setGlassTransparency(level)
    }

    fun setAccessibilityFlags(flags: LiquidGlassDrawable.AccessibilityFlags) {
        searchGlass.setAccessibilityFlags(flags)
        (drillBackBtn.background as? LiquidGlassDrawable)?.setAccessibilityFlags(flags)
        shuffleAllGlass.setAccessibilityFlags(flags)
        searchCapsuleGlass.setAccessibilityFlags(flags)
        utilitiesGlass.setAccessibilityFlags(flags)
        (permissionGrantBtn.background as? LiquidGlassDrawable)?.setAccessibilityFlags(flags)
        (importBtn.background as? LiquidGlassDrawable)?.setAccessibilityFlags(flags)
        (exportBtn.background as? LiquidGlassDrawable)?.setAccessibilityFlags(flags)
        (createPlaylistBtn.background as? LiquidGlassDrawable)?.setAccessibilityFlags(flags)
        miniPlayerGlass.setAccessibilityFlags(flags)
        bottomScrubBar.setAccessibilityFlags(flags)
    }

    fun setLibraryData(
        tracks: List<Track>,
        loadedPlaylists: List<Playlist>,
        pinnedFolders: Set<String> = emptySet()
    ) {
        allTracks = tracks
        pinnedFolderPaths = pinnedFolders
        folders = LibraryScanner.groupByFolderWithPins(tracks, pinnedFolders)
        albums = LibraryScanner.groupByAlbum(tracks)
        artists = LibraryScanner.groupByArtist(tracks)
        playlists = loadedPlaylists
        drillDownTitle = null
        drillDownTracks = null
        pendingStaggerEntrance = true
        rebuildDisplayedEntries()
    }

    fun setAmoledMode(enabled: Boolean) {
        setBackgroundColor(if (enabled) 0xFF000000.toInt() else 0x00000000)
        val groundColor = if (enabled) 0xFF000000.toInt() else LiquidGlassDrawable.LayerTokens.DARK_BASE_GROUND
        (listContainer.background as? GradientDrawable)?.setColor(groundColor)
    }

    fun handleBackStep(): Boolean {
        if (searchBox.visibility == View.VISIBLE) {
            toggleSearchBar()
            return true
        }
        if (drillDownTracks != null) {
            drillDownTitle = null
            drillDownTracks = null
            rebuildDisplayedEntries()
            return true
        }
        return false
    }

    fun setPlaylists(loadedPlaylists: List<Playlist>) {
        playlists = loadedPlaylists
        if (activeTab == Tab.PLAYLISTS && drillDownTracks == null) {
            rebuildDisplayedEntries()
        }
    }

    fun setPermissionRequired(required: Boolean) {
        permissionCard.visibility = if (required) View.VISIBLE else View.GONE
        recyclerView.visibility = if (required) View.GONE else View.VISIBLE
        if (required) {
            emptyStateView.visibility = View.GONE
        } else {
            rebuildDisplayedEntries()
        }
    }

    fun updateMiniPlayer(track: Track?, isPlaying: Boolean, artwork: Bitmap?) {
        if (track == null) {
            if (miniPlayerPill.visibility == View.VISIBLE) {
                SpringAnimatorHelper.springDismiss(miniPlayerPill, toTranslationY = dp(12).toFloat(), toScale = 0.96f)
            }
            return
        }
        if (miniPlayerPill.visibility != View.VISIBLE) {
            SpringAnimatorHelper.springReveal(miniPlayerPill, fromTranslationY = dp(12).toFloat(), fromScale = 0.96f)
        }
        miniTitleView.text = track.title.ifBlank { context.getString(R.string.unknown_title) }
        miniArtistView.text = track.artist.ifBlank { context.getString(R.string.unknown_artist) }
        miniPlayPauseBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (artwork != null && !artwork.isRecycled) {
            miniCoverImage.setImageBitmap(artwork)
            miniCoverImage.visibility = View.VISIBLE
            miniCoverPlaceholder.visibility = View.GONE
        } else {
            miniCoverImage.setImageBitmap(null)
            miniCoverImage.visibility = View.GONE
            miniCoverPlaceholder.visibility = View.VISIBLE
        }
    }

    fun updateMiniPlayerProgress(positionMs: Long, durationMs: Long) {
        val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        miniProgressBar.setProgress(fraction)
    }

    private fun buildUi() {
        mainColumn.orientation = LinearLayout.VERTICAL
        mainColumn.setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(mainColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 1. Top Passive Header (zero buttons in top corners)
        val headerBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        headerTitleView.apply {
            text = context.getString(R.string.app_name)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        headerSubtitleView.apply {
            setTextColor(0xA3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        headerBox.addView(headerTitleView)
        headerBox.addView(headerSubtitleView)
        mainColumn.addView(headerBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 2. Top-to-Middle RecyclerView + Swipe-to-Queue + Empty + Permission Card
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = listAdapter
            itemAnimator = null
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(8))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val nextCompact = NavBarScrollMath.resolveCompactState(isNavBarCompact, dy)
                    if (nextCompact != isNavBarCompact) {
                        setNavBarCompact(nextCompact)
                    }
                    updateListScrollDissolution()
                    sampleBottomTabBackdrop()
                }
            })
        }
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val pos = vh.bindingAdapterPosition
                val entry = listAdapter.getEntryOrNull(pos)
                return if (entry is ListEntry.TrackEntry) ItemTouchHelper.RIGHT else 0
            }

            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                val entry = listAdapter.getEntryOrNull(pos)
                if (entry is ListEntry.TrackEntry) {
                    vh.itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    callbacks.onEnqueueTrack(entry.track)
                }
                listAdapter.notifyItemChanged(pos)
            }
        }).attachToRecyclerView(recyclerView)
        emptyStateView.apply {
            text = context.getString(R.string.empty_library)
            setTextColor(0x88FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        buildPermissionCard()

        listContainer.apply {
            background = GradientDrawable().apply {
                setColor(LiquidGlassDrawable.LayerTokens.DARK_BASE_GROUND)
                cornerRadius = 18f * density
            }
            clipToOutline = true
        }
        topDissolutionEdge.apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xE0131316.toInt(), 0x00131316)
            )
            alpha = 0f
        }
        bottomDissolutionEdge.apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE0131316.toInt(), 0x00131316)
            )
            alpha = 0f
        }
        listContainer.addView(recyclerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        listContainer.addView(topDissolutionEdge, LayoutParams(LayoutParams.MATCH_PARENT, dp(24), Gravity.TOP))
        listContainer.addView(bottomDissolutionEdge, LayoutParams(LayoutParams.MATCH_PARENT, dp(24), Gravity.BOTTOM))
        listContainer.addView(emptyStateView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        listContainer.addView(permissionCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        mainColumn.addView(listContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // 3. Bottom Dock (Thumb-Zone)
        bottomDock.apply {
            orientation = LinearLayout.VERTICAL
            background = null
            elevation = 0f
        }
        mainColumn.addView(bottomDock, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 3a. Floating Mini Player locked directly above bottom action/nav area
        buildMiniPlayer()
        bottomDock.addView(
            miniPlayerPill,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
        )

        // 3b. Collapsible Inline Search Input (thumb-reachable)
        searchBox.apply {
            hint = context.getString(R.string.search_hint)
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = searchGlass
            minimumHeight = dp(48)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isSingleLine = true
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    rebuildDisplayedEntries()
                }
            })
        }
        bottomDock.addView(
            searchBox,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        )

        // 3c. Playlist .m3u8 actions (when Playlist tab active at top level)
        playlistActionsRow.apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        configureActionChip(importBtn, context.getString(R.string.playlist_import)) {
            callbacks.onImportM3u8Clicked()
        }
        configureActionChip(exportBtn, context.getString(R.string.playlist_export)) {
            callbacks.onExportM3u8Clicked()
        }
        configureActionChip(createPlaylistBtn, context.getString(R.string.playlist_create)) {
            callbacks.onCreatePlaylistClicked()
        }
        playlistActionsRow.addView(importBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        playlistActionsRow.addView(exportBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        playlistActionsRow.addView(createPlaylistBtn, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottomDock.addView(
            playlistActionsRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        )

        // 3d. Quick Action Strip (Putar Acak Semua + Cari + optional drill-down Kembali)
        buildQuickActionRow()
        bottomDock.addView(
            quickActionRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        )

        // 3e. Bottom 5-Tab Interactive Glass Scrub Navigation + Detached Circular Search Capsule
        buildBottomTabBar()
        bottomNavRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        searchCapsuleBtn.apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = context.getString(R.string.search_label)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = searchCapsuleGlass
            elevation = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = minOf(view.width, view.height) * 0.5f
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
            clipToOutline = true
            setPadding(dp(16), dp(16), dp(16), dp(16))
            LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.COMPACT)
            setOnClickListener { toggleSearchBar() }
        }
        bottomNavRow.addView(
            bottomScrubBar,
            LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(10) }
        )
        bottomNavRow.addView(
            searchCapsuleBtn,
            LinearLayout.LayoutParams(dp(56), dp(56))
        )
        bottomDock.addView(
            bottomNavRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    private fun buildQuickActionRow() {
        val pillOutlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = minOf(24f * density, view.height * 0.5f)
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        quickActionRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = null
            elevation = 0f
            clipChildren = false
            clipToPadding = false
        }

        drillBackBtn.apply {
            text = "\u2190 ${context.getString(R.string.back_to_list)}"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            background = LiquidGlassDrawable(context, 24f, palette)
            elevation = 0f
            outlineProvider = pillOutlineProvider
            clipToOutline = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
            LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.COMPACT)
            setOnClickListener {
                drillDownTitle = null
                drillDownTracks = null
                rebuildDisplayedEntries()
            }
        }

        shuffleAllPill.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            background = shuffleAllGlass
            elevation = 0f
            outlineProvider = pillOutlineProvider
            clipToOutline = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.WIDE)
            setOnClickListener {
                val targetTracks = drillDownTracks ?: allTracks
                if (targetTracks.isNotEmpty()) {
                    callbacks.onShufflePlayAll(targetTracks)
                }
            }
        }
        shuffleAllIcon.apply {
            setImageResource(R.drawable.ic_shuffle)
            imageTintList = ColorStateList.valueOf(palette.accentGlow)
        }
        shuffleAllText.apply {
            text = context.getString(R.string.shuffle_all)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }
        shuffleAllPill.addView(shuffleAllIcon, LinearLayout.LayoutParams(dp(18), dp(18)))
        shuffleAllPill.addView(shuffleAllText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        utilitiesPill.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            minimumWidth = dp(48)
            background = utilitiesGlass
            elevation = 0f
            outlineProvider = pillOutlineProvider
            clipToOutline = true
            contentDescription = context.getString(R.string.utilities_short)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.COMPACT)
            setOnClickListener { callbacks.onOpenUtilitiesClicked() }
        }
        utilitiesIcon.apply {
            setImageResource(R.drawable.ic_utilities)
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        utilitiesPill.addView(utilitiesIcon, LinearLayout.LayoutParams(dp(18), dp(18)))

        quickActionRow.addView(
            drillBackBtn,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) }
        )
        quickActionRow.addView(
            shuffleAllPill,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        )
        quickActionRow.addView(
            utilitiesPill,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
    }

    private fun buildPermissionCard() {
        permissionCard.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = permissionCardBg
            elevation = 6f * density
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        val title = TextView(context).apply {
            text = context.getString(R.string.permission_title)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val msg = TextView(context).apply {
            text = context.getString(R.string.permission_message)
            setTextColor(0xA3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        permissionGrantBtn.apply {
            text = context.getString(R.string.permission_grant)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER
            background = LiquidGlassDrawable(context, 16f, palette).apply {
                setTintProfile(0.35f, 0.65f, preferAccentGlow = true)
            }
            setPadding(dp(20), dp(10), dp(20), dp(10))
            LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.WIDE)
            setOnClickListener { callbacks.onRequestStoragePermission() }
        }
        permissionCard.addView(title)
        permissionCard.addView(msg)
        permissionCard.addView(permissionGrantBtn)
    }

    private fun buildMiniPlayer() {
        miniPlayerPill.apply {
            background = miniPlayerGlass
            elevation = 0f
            visibility = View.GONE
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 22f * density)
                }
            }
            clipToOutline = true
            foreground = null
            setOnClickListener { callbacks.onOpenNowPlaying() }
            setOnTouchListener { v, event ->
                LiquidGlassPressAnimator.handleTouchEvent(
                    v,
                    event,
                    LiquidGlassPressAnimator.PressStyle.WIDE,
                    miniPlayerGlass
                )
                val nowMs = SystemClock.elapsedRealtime()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        SpringAnimatorHelper.cancelSpring(miniPlayerPill, CHANNEL_MINI_SWIPE)
                        miniSwipeController.onDown(event.rawX, event.rawY, nowMs)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val prevState = miniSwipeController.state
                        miniSwipeController.onMove(
                            x = event.rawX,
                            y = event.rawY,
                            nowMs = nowMs,
                            touchSlopPx = touchSlop.toFloat(),
                            swipeThresholdPx = miniSwipeThresholdPx,
                            maxOffsetPx = miniSwipeMaxOffsetPx
                        )
                        if (miniSwipeController.state == NowPlayingView.HorizontalSwipeCommitController.GestureState.HORIZONTAL_DRAG) {
                            if (prevState != NowPlayingView.HorizontalSwipeCommitController.GestureState.HORIZONTAL_DRAG) {
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            if (miniSwipeController.thresholdCrossedThisMove) {
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            v.translationX = miniSwipeController.translationX
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        val wasHorizontal = miniSwipeController.state ==
                            NowPlayingView.HorizontalSwipeCommitController.GestureState.HORIZONTAL_DRAG
                        val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                        val triggerDir = miniSwipeController.onRelease(
                            x = event.rawX,
                            isCancel = isCancel,
                            swipeThresholdPx = miniSwipeThresholdPx
                        )
                        if (wasHorizontal) {
                            val profile = if (SpringAnimatorHelper.isReducedMotionEnabled(context)) {
                                SpringAnimatorHelper.SPRING_REDUCED_MOTION
                            } else {
                                SpringAnimatorHelper.SPRING_BOUNCY
                            }
                            SpringAnimatorHelper.animateSpring(
                                key = miniPlayerPill,
                                start = miniPlayerPill.translationX,
                                target = 0f,
                                stiffness = profile.stiffness,
                                dampingRatio = profile.dampingRatio,
                                valueThreshold = 0.25f,
                                channel = CHANNEL_MINI_SWIPE,
                                onUpdate = { tx -> miniPlayerPill.translationX = tx }
                            )
                            if (triggerDir > 0) {
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                callbacks.onNextTrack()
                            } else if (triggerDir < 0) {
                                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                callbacks.onPreviousTrack()
                            }
                            true
                        } else {
                            miniPlayerPill.translationX = 0f
                            false
                        }
                    }
                    else -> false
                }
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
        }

        miniCoverCard.apply {
            background = null
            elevation = 0f
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 10f * density)
                }
            }
            clipToOutline = true
        }
        miniCoverPlaceholder.apply {
            setImageResource(R.drawable.ic_default_album_cover)
            imageTintList = null
            scaleType = ImageView.ScaleType.CENTER_CROP
            setPadding(0, 0, 0, 0)
        }
        miniCoverImage.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        miniCoverCard.addView(miniCoverPlaceholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        miniCoverCard.addView(miniCoverImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        miniTitleView.apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        miniArtistView.apply {
            setTextColor(0xA3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        infoCol.addView(miniTitleView)
        infoCol.addView(miniArtistView)

        miniPlayPauseBtn.apply {
            setImageResource(R.drawable.ic_play)
            background = null
            imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            contentDescription = context.getString(R.string.cd_play_pause)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.COMPACT)
            setOnClickListener { callbacks.onTogglePlayPause() }
        }

        row.addView(miniCoverCard, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(infoCol, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(miniPlayPauseBtn, LinearLayout.LayoutParams(dp(48), dp(48)))

        miniPlayerPill.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        miniPlayerPill.addView(
            miniProgressBar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM)
        )
    }

    private fun buildBottomTabBar() {
        val tabsSpec = listOf(
            Triple(Tab.SONGS, R.string.tab_songs, R.drawable.ic_music_note),
            Triple(Tab.ALBUMS, R.string.tab_albums, R.drawable.ic_album),
            Triple(Tab.ARTISTS, R.string.tab_artists, R.drawable.ic_artist),
            Triple(Tab.FOLDERS, R.string.tab_folders, R.drawable.ic_folder),
            Triple(Tab.PLAYLISTS, R.string.tab_playlists, R.drawable.ic_playlist)
        )

        tabViews.clear()
        for ((tab, titleRes, iconRes) in tabsSpec) {
            tabViews.add(TabItemView(context, tab, context.getString(titleRes), iconRes))
        }
        bottomScrubBar.foreground = null
        bottomScrubBar.setNeutralLensMode(true)
        bottomScrubBar.setSlots(tabViews)
        bottomScrubBar.onSlotPreviewChanged = { idx ->
            for (i in 0 until tabViews.size) {
                tabViews[i].setSelectedState(i == idx, palette)
            }
        }
        bottomScrubBar.onSlotSelected = { idx ->
            val tab = tabViews.getOrNull(idx)?.tab
            if (tab != null && tab != activeTab) {
                selectTab(tab, updateScrub = false)
            } else {
                for (item in tabViews) {
                    item.setSelectedState(item.tab == activeTab, palette)
                }
            }
        }
    }

    private fun setNavBarCompact(compact: Boolean) {
        if (isNavBarCompact == compact) return
        isNavBarCompact = compact
        val targetScale = if (compact) NavBarScrollMath.COMPACT_SCALE else 1f
        val targetAlpha = if (compact) 0.90f else 1f
        bottomScrubBar.pivotX = bottomScrubBar.width * 0.5f
        bottomScrubBar.pivotY = bottomScrubBar.height.toFloat()
        SpringAnimatorHelper.animateSpring(
            key = bottomScrubBar,
            start = bottomScrubBar.scaleX,
            target = targetScale,
            stiffness = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.stiffness,
            dampingRatio = SpringAnimatorHelper.SPRING_LOUPE_GLIDE.dampingRatio,
            channel = CHANNEL_NAV_COMPACT,
            onUpdate = { s ->
                bottomScrubBar.scaleX = s
                bottomScrubBar.scaleY = s
                val t = ((s - NavBarScrollMath.COMPACT_SCALE) / (1f - NavBarScrollMath.COMPACT_SCALE)).coerceIn(0f, 1f)
                bottomScrubBar.alpha = 0.90f + (1f - 0.90f) * t
            }
        )
        if (SpringAnimatorHelper.getSpringValue(bottomScrubBar, CHANNEL_NAV_COMPACT) == null) {
            bottomScrubBar.alpha = targetAlpha
        }
    }

    private fun selectTab(tab: Tab, updateScrub: Boolean = true) {
        activeTab = tab
        drillDownTitle = null
        drillDownTracks = null
        setNavBarCompact(false)
        for (item in tabViews) {
            item.setSelectedState(item.tab == tab, palette)
        }
        if (updateScrub) {
            val idx = tabViews.indexOfFirst { it.tab == tab }
            if (idx >= 0) {
                bottomScrubBar.setSelectedIndex(idx, animate = true)
            }
        }
        rebuildDisplayedEntries()
    }

    private fun updateListScrollDissolution() {
        val topAlpha = SpringAnimatorHelper.ScrollEdgeMath.computeDissolutionProgress(
            recyclerView.computeVerticalScrollOffset(),
            dp(36).toFloat()
        )
        val bottomAlpha = if (recyclerView.canScrollVertically(1)) 1f else 0f
        topDissolutionEdge.alpha = topAlpha
        bottomDissolutionEdge.alpha = bottomAlpha
    }

    private fun toggleSearchBar() {
        val visible = searchBox.visibility == View.VISIBLE
        if (visible) {
            searchBox.setText("")
            searchQuery = ""
            SpringAnimatorHelper.springDismiss(searchBox, toTranslationY = dp(8).toFloat(), toScale = 0.97f)
            searchCapsuleGlass.setTintProfile(0.56f, 0.10f, preferAccentGlow = false)
            searchCapsuleBtn.imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            rebuildDisplayedEntries()
        } else {
            SpringAnimatorHelper.springReveal(searchBox, fromTranslationY = dp(8).toFloat(), fromScale = 0.97f)
            searchCapsuleGlass.setTintProfile(0.35f, 0.45f, preferAccentGlow = true)
            searchCapsuleBtn.imageTintList = ColorStateList.valueOf(palette.accentGlow)
            searchBox.requestFocus()
        }
    }

    private fun handleEntryClick(entry: ListEntry) {
        when (entry) {
            is ListEntry.TrackEntry -> callbacks.onPlayTracks(entry.queue, entry.indexInQueue)
            is ListEntry.FolderEntry -> {
                drillDownTitle = entry.node.name
                drillDownTracks = entry.node.tracks
                rebuildDisplayedEntries()
            }
            is ListEntry.AlbumEntry -> {
                drillDownTitle = entry.group.name
                drillDownTracks = entry.group.tracks
                rebuildDisplayedEntries()
            }
            is ListEntry.ArtistEntry -> {
                drillDownTitle = entry.group.name
                drillDownTracks = entry.group.tracks
                rebuildDisplayedEntries()
            }
            is ListEntry.PlaylistEntry -> {
                drillDownTitle = entry.playlist.name
                drillDownTracks = entry.playlist.tracks
                rebuildDisplayedEntries()
            }
        }
    }

    private fun handleEntryLongClick(entry: ListEntry) {
        when (entry) {
            is ListEntry.TrackEntry -> {
                val options = arrayOf("Putar Berikutnya", "Tambah ke Antrean", "Edit Tag Audio")
                android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(entry.track.title)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> callbacks.onPlayNextTrack(entry.track)
                            1 -> callbacks.onEnqueueTrack(entry.track)
                            2 -> callbacks.onEditTrackTags(entry.track)
                        }
                    }
                    .show()
            }
            is ListEntry.FolderEntry -> {
                val path = entry.node.path
                val isPinned = path in pinnedFolderPaths
                val pinLabel = if (isPinned) "Lepas Sematan Folder" else "Sematkan Folder"
                val options = arrayOf(pinLabel, "Segarkan Folder", "Sembunyikan Folder (Blacklist)")
                android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(entry.node.name)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> callbacks.onTogglePinFolder(path)
                            1 -> callbacks.onRefreshFolder(path)
                            2 -> callbacks.onBlacklistFolder(path)
                        }
                    }
                    .show()
            }
            else -> Unit
        }
    }

    private fun rebuildDisplayedEntries() {
        val q = searchQuery.lowercase()
        val drillTracks = drillDownTracks

        if (drillTracks != null) {
            if (drillBackBtn.visibility != View.VISIBLE) {
                SpringAnimatorHelper.springReveal(drillBackBtn, fromTranslationX = -dp(12).toFloat(), fromScale = 0.96f)
            }
        } else if (drillBackBtn.visibility == View.VISIBLE) {
            SpringAnimatorHelper.springDismiss(drillBackBtn, toTranslationX = -dp(12).toFloat(), toScale = 0.96f)
        }
        if (drillTracks != null && !drillDownTitle.isNullOrBlank()) {
            headerSubtitleView.text = "${drillDownTitle} \u2022 ${context.getString(R.string.songs_count_fmt, drillTracks.size)}"
            headerSubtitleView.visibility = View.VISIBLE
        } else {
            headerSubtitleView.visibility = View.GONE
        }

        if (activeTab == Tab.PLAYLISTS && drillTracks == null) {
            if (playlistActionsRow.visibility != View.VISIBLE) {
                SpringAnimatorHelper.springReveal(playlistActionsRow, fromTranslationY = dp(8).toFloat(), fromScale = 0.97f)
            }
        } else if (playlistActionsRow.visibility == View.VISIBLE) {
            SpringAnimatorHelper.springDismiss(playlistActionsRow, toTranslationY = dp(8).toFloat(), toScale = 0.97f)
        }

        val entries: List<ListEntry> = if (drillTracks != null) {
            val filtered = if (q.isEmpty()) drillTracks else drillTracks.filter { matchesQuery(it, q) }
            filtered.mapIndexed { idx, track -> ListEntry.TrackEntry(track, filtered, idx) }
        } else {
            when (activeTab) {
                Tab.SONGS -> {
                    val filtered = if (q.isEmpty()) allTracks else allTracks.filter { matchesQuery(it, q) }
                    filtered.mapIndexed { idx, track -> ListEntry.TrackEntry(track, filtered, idx) }
                }
                Tab.FOLDERS -> {
                    val filtered = if (q.isEmpty()) folders else folders.filter {
                        it.name.lowercase().contains(q) || it.path.lowercase().contains(q)
                    }
                    filtered.map { ListEntry.FolderEntry(it) }
                }
                Tab.ALBUMS -> {
                    val filtered = if (q.isEmpty()) albums else albums.filter {
                        it.name.lowercase().contains(q) || it.artist.lowercase().contains(q)
                    }
                    filtered.map { ListEntry.AlbumEntry(it) }
                }
                Tab.ARTISTS -> {
                    val filtered = if (q.isEmpty()) artists else artists.filter {
                        it.name.lowercase().contains(q)
                    }
                    filtered.map { ListEntry.ArtistEntry(it) }
                }
                Tab.PLAYLISTS -> {
                    val filtered = if (q.isEmpty()) playlists else playlists.filter {
                        it.name.lowercase().contains(q)
                    }
                    filtered.map { ListEntry.PlaylistEntry(it) }
                }
            }
        }

        listAdapter.submitEntries(entries, palette)
        if (pendingStaggerEntrance && entries.isNotEmpty()) {
            pendingStaggerEntrance = false
            scheduleVisibleRowsStagger()
        }
        if (permissionCard.visibility != View.VISIBLE) {
            emptyStateView.text = if (activeTab == Tab.PLAYLISTS && drillTracks == null) {
                context.getString(R.string.playlist_empty)
            } else {
                context.getString(R.string.empty_library)
            }
            emptyStateView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
        scheduleBackdropSample()
    }

    private fun scheduleVisibleRowsStagger() {
        cancelVisibleRowsStagger()
        if (!hostActive || SpringAnimatorHelper.isReducedMotionEnabled(context)) return
        recyclerView.post(staggerPostRunnable)
    }

    private fun runVisibleRowsStagger() {
        if (!hostActive || SpringAnimatorHelper.isReducedMotionEnabled(context)) return
        val visibleCount = minOf(recyclerView.childCount, 12)
        val entryOffsetPx = PageMotionSpec.LIBRARY_ROW_ENTRY_OFFSET_DP * density
        for (i in 0 until visibleCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            SpringAnimatorHelper.cancelSpring(child, CHANNEL_ROW_STAGGER)
            child.alpha = 0f
            child.translationY = entryOffsetPx
            val task = Runnable {
                if (!hostActive) {
                    child.alpha = 1f
                    child.translationY = 0f
                    return@Runnable
                }
                SpringAnimatorHelper.animateSpring(
                    key = child,
                    start = 0f,
                    target = 1f,
                    stiffness = SpringAnimatorHelper.SPRING_SMOOTH.stiffness,
                    dampingRatio = SpringAnimatorHelper.SPRING_SMOOTH.dampingRatio,
                    channel = CHANNEL_ROW_STAGGER,
                    onUpdate = { p ->
                        child.alpha = p.coerceIn(0f, 1f)
                        child.translationY = entryOffsetPx * (1f - p)
                    }
                )
            }
            staggerRunnables.add(task)
            staggerHandler.postDelayed(task, PageMotionSpec.computeStaggerDelayMs(i))
        }
    }

    private fun cancelVisibleRowsStagger() {
        recyclerView.removeCallbacks(staggerPostRunnable)
        for (i in 0 until staggerRunnables.size) {
            staggerHandler.removeCallbacks(staggerRunnables[i])
        }
        staggerRunnables.clear()
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            SpringAnimatorHelper.cancelSpring(child, CHANNEL_ROW_STAGGER)
            child.alpha = 1f
            child.translationY = 0f
        }
    }

    private fun scheduleBackdropSample() {
        recyclerView.removeCallbacks(sampleBackdropRunnable)
        recyclerView.post(sampleBackdropRunnable)
    }

    private fun sampleBottomTabBackdrop() {
        val sampleBmp = navSampleBitmap ?: return
        val sampleCanvas = navSampleCanvas ?: return
        val barH = bottomScrubBar.height
        if (bottomScrubBar.width <= 0 || barH <= 0 || recyclerView.childCount == 0) return

        bottomScrubBar.getLocationInWindow(tmpNavLocation)
        val barTopY = tmpNavLocation[1].toFloat()
        val barBottomY = barTopY + barH.toFloat()

        sampleBmp.eraseColor(LiquidGlassDrawable.LayerTokens.DARK_BASE_GROUND)
        var drewAnyCover = false
        val dimF = navSampleDim.toFloat()

        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            val childH = child.height
            if (childH <= 0) continue
            child.getLocationInWindow(tmpChildLocation)
            val childTopY = tmpChildLocation[1].toFloat()
            val childBottomY = childTopY + childH.toFloat()
            if (childBottomY <= barTopY || childTopY >= barBottomY) continue

            val holder = recyclerView.getChildViewHolder(child) as? LibraryAdapter.EntryViewHolder ?: continue
            val coverBmp = (holder.iconBadge.drawable as? BitmapDrawable)?.bitmap
            if (coverBmp != null && !coverBmp.isRecycled) {
                val overlapTop = ((childTopY - barTopY) / barH.toFloat()) * dimF
                val overlapBottom = ((childBottomY - barTopY) / barH.toFloat()) * dimF
                navSampleDstRect.set(0f, overlapTop, dimF, overlapBottom)
                sampleCanvas.drawBitmap(coverBmp, null, navSampleDstRect, navSamplePaint)
                drewAnyCover = true
            }
        }

        val rawSample = if (drewAnyCover) {
            sampleBmp.getPixels(navSamplePixels, 0, navSampleDim, 0, 0, navSampleDim, navSampleDim)
            LiquidGlassDrawable.TabBackdropSamplerMath.computeAverageSample(
                pixels = navSamplePixels,
                count = navSamplePixels.size,
                fallbackArgb = palette.deepBackdrop
            )
        } else {
            LiquidGlassDrawable.TabBackdropSamplerMath.computeAverageSample(
                pixels = navSamplePixels,
                count = 0,
                fallbackArgb = palette.deepBackdrop
            )
        }

        val next = LiquidGlassDrawable.TabBackdropSamplerMath.interpolateSample(
            currentArgb = smoothedNavArgb,
            currentLum = smoothedNavLum,
            target = rawSample
        )
        smoothedNavArgb = next.argb
        smoothedNavLum = next.luminance
        bottomScrubBar.setSampledBackdrop(next.argb, next.luminance)
    }

    private fun matchesQuery(track: Track, q: String): Boolean {
        return track.title.lowercase().contains(q) ||
            track.artist.lowercase().contains(q) ||
            track.album.lowercase().contains(q)
    }

    private fun configureActionChip(tv: TextView, label: String, onClick: () -> Unit) {
        tv.apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = LiquidGlassDrawable(context, 16f, palette)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.WIDE)
            setOnClickListener { onClick() }
        }
    }

    private fun applyPalette(newPalette: CoverPalette) {
        searchGlass.setPalette(newPalette)
        (drillBackBtn.background as? LiquidGlassDrawable)?.setPalette(newPalette)
        shuffleAllGlass.setPalette(newPalette)
        shuffleAllGlass.setTintProfile(baseFraction = 0.34f, accentFraction = 0.45f, preferAccentGlow = true)
        shuffleAllIcon.imageTintList = ColorStateList.valueOf(newPalette.accentGlow)
        searchCapsuleGlass.setPalette(newPalette)
        searchCapsuleGlass.setTintProfile(baseFraction = 0.56f, accentFraction = 0.10f, preferAccentGlow = false)
        utilitiesGlass.setPalette(newPalette)
        utilitiesGlass.setTintProfile(baseFraction = 0.56f, accentFraction = 0.10f, preferAccentGlow = false)
        if (searchBox.visibility == View.VISIBLE) {
            searchCapsuleBtn.imageTintList = ColorStateList.valueOf(newPalette.accentGlow)
        }
        (permissionGrantBtn.background as? LiquidGlassDrawable)?.setPalette(newPalette)
        (importBtn.background as? LiquidGlassDrawable)?.setPalette(newPalette)
        (exportBtn.background as? LiquidGlassDrawable)?.setPalette(newPalette)
        (createPlaylistBtn.background as? LiquidGlassDrawable)?.setPalette(newPalette)
        miniPlayerGlass.setPalette(newPalette)
        miniPlayerGlass.setTintProfile(baseFraction = 0.65f, accentFraction = 0.18f)
        miniProgressBar.setAccentColor(newPalette.accentGlow)
        bottomScrubBar.setPalette(newPalette)
        for (item in tabViews) {
            item.setSelectedState(item.tab == activeTab, newPalette)
        }
        listAdapter.updatePalette(newPalette)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    // --- Tab Item View ---

    private class TabItemView(
        context: Context,
        val tab: Tab,
        label: String,
        iconRes: Int
    ) : LinearLayout(context) {

        private val density = resources.displayMetrics.density
        private val iconView = ImageView(context)
        private val labelView = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = (48 * density).toInt()
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())

            iconView.setImageResource(iconRes)
            labelView.apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }

            addView(iconView, LayoutParams((18 * density).toInt(), (18 * density).toInt()))
            addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * density).toInt()
            })
            LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.COMPACT)
        }

        fun setSelectedState(selected: Boolean, palette: CoverPalette) {
            if (selected) {
                iconView.imageTintList = ColorStateList.valueOf(palette.accentGlow)
                labelView.setTextColor(palette.accentGlow)
            } else {
                iconView.imageTintList = ColorStateList.valueOf(0x8FFFFFFF.toInt())
                labelView.setTextColor(0x8FFFFFFF.toInt())
            }
        }
    }

    // --- Thin Mini Player Progress Bar (zero allocations in onDraw) ---

    private class MiniProgressBarView(context: Context) : View(context) {
        private var fraction: Float = 0f
        private val bgPaint = Paint().apply {
            color = 0x26FFFFFF
            style = Paint.Style.FILL
        }
        private val fgPaint = Paint().apply {
            color = CoverPaletteExtractor.defaultPalette().accentGlow
            style = Paint.Style.FILL
        }

        fun setAccentColor(color: Int) {
            if (fgPaint.color != color) {
                fgPaint.color = color
                invalidate()
            }
        }

        fun setProgress(value: Float) {
            val clamped = value.coerceIn(0f, 1f)
            if (fraction != clamped) {
                fraction = clamped
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, bgPaint)
            if (fraction > 0f) {
                canvas.drawRect(0f, 0f, w * fraction, h, fgPaint)
            }
        }
    }

    // --- RecyclerView Adapter (ZERO allocations in onBindViewHolder) ---

    private class LibraryAdapter(
        private val onEntryClick: (ListEntry) -> Unit,
        private val onEntryLongClick: (ListEntry) -> Unit,
        private val onThumbDecoded: (() -> Unit)? = null
    ) : RecyclerView.Adapter<LibraryAdapter.EntryViewHolder>() {

        private var entries: List<ListEntry> = emptyList()
        private var palette: CoverPalette = CoverPaletteExtractor.defaultPalette()
        private var accentTintList: ColorStateList = ColorStateList.valueOf(palette.accentGlow)

        fun getEntryOrNull(position: Int): ListEntry? = entries.getOrNull(position)

        fun submitEntries(newEntries: List<ListEntry>, currentPalette: CoverPalette) {
            entries = newEntries
            if (palette != currentPalette) {
                palette = currentPalette
                accentTintList = ColorStateList.valueOf(currentPalette.accentGlow)
            }
            notifyDataSetChanged()
        }

        fun updatePalette(newPalette: CoverPalette) {
            if (palette != newPalette) {
                palette = newPalette
                accentTintList = ColorStateList.valueOf(newPalette.accentGlow)
            }
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val dp = { v: Int -> (v * density).toInt() }

            val cardBg = GradientDrawable().apply {
                setColor(LiquidGlassDrawable.LayerTokens.contentCardColor(isDarkMode = true))
                cornerRadius = 14f * density
            }
            val rowWrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
                background = cardBg
                elevation = 2f * density
                LiquidGlassPressAnimator.attach(this, LiquidGlassPressAnimator.PressStyle.WIDE)
            }

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(12), dp(10), dp(14), dp(10))
            }

            val iconBadge = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = null
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 10f * density)
                    }
                }
                clipToOutline = true
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
            }
            val primary = TextView(ctx).apply {
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = TYPEFACE_MEDIUM
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val secondary = TextView(ctx).apply {
                setTextColor(0xA3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(primary)
            textCol.addView(secondary)

            val trailing = TextView(ctx).apply {
                setTextColor(0x88FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                fontFeatureSettings = "tnum"
                gravity = Gravity.END
                maxLines = 1
            }

            row.addView(iconBadge, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(trailing, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

            val hairlineHeight = (0.5f * density).toInt().coerceAtLeast(1)
            val hairline = View(ctx).apply {
                setBackgroundColor(LiquidGlassDrawable.LayerTokens.hairlineColor(isDarkMode = true))
            }

            rowWrapper.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            rowWrapper.addView(hairline, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, hairlineHeight).apply {
                marginStart = dp(64)
                marginEnd = dp(14)
            })

            val folderBadgeBg = GradientDrawable().apply {
                setColor(LiquidGlassDrawable.LayerTokens.nestedCardColor(isDarkMode = true))
                cornerRadius = 10f * density
            }
            val holder = EntryViewHolder(rowWrapper, iconBadge, primary, secondary, trailing, folderBadgeBg)
            rowWrapper.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in entries.indices) {
                    onEntryClick(entries[pos])
                }
            }
            rowWrapper.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos in entries.indices) {
                    onEntryLongClick(entries[pos])
                    true
                } else false
            }
            return holder
        }

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
            val badgePad = (9f * holder.itemView.resources.displayMetrics.density).toInt()
            when (val entry = entries[position]) {
                is ListEntry.TrackEntry -> {
                    val t = entry.track
                    if (holder.iconBadge.background != null) holder.iconBadge.background = null
                    holder.iconBadge.scaleType = ImageView.ScaleType.CENTER_CROP
                    holder.iconBadge.setPadding(0, 0, 0, 0)
                    holder.iconBadge.imageTintList = null
                    TrackThumbnailLoader.bind(holder.iconBadge, t, onThumbDecoded)
                    holder.primary.text = t.title
                    holder.secondary.text = t.artist
                    holder.bindDuration(t.durationMs)
                }
                is ListEntry.FolderEntry -> {
                    holder.iconBadge.tag = null
                    if (holder.iconBadge.background !== holder.folderBadgeBg) {
                        holder.iconBadge.background = holder.folderBadgeBg
                    }
                    holder.iconBadge.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    holder.iconBadge.setPadding(badgePad, badgePad, badgePad, badgePad)
                    holder.iconBadge.setImageResource(R.drawable.ic_folder)
                    holder.iconBadge.imageTintList = accentTintList
                    holder.primary.text = entry.node.name
                    holder.secondary.text = entry.node.path
                    holder.bindCount(entry.node.tracks.size)
                }
                is ListEntry.AlbumEntry -> {
                    if (holder.iconBadge.background != null) holder.iconBadge.background = null
                    holder.iconBadge.scaleType = ImageView.ScaleType.CENTER_CROP
                    holder.iconBadge.setPadding(0, 0, 0, 0)
                    holder.iconBadge.imageTintList = null
                    val repTrack = entry.group.tracks.firstOrNull()
                    if (repTrack != null) {
                        TrackThumbnailLoader.bind(holder.iconBadge, repTrack, onThumbDecoded)
                    } else {
                        holder.iconBadge.tag = null
                        holder.iconBadge.setImageResource(R.drawable.ic_default_album_cover)
                    }
                    holder.primary.text = entry.group.name
                    holder.secondary.text = entry.group.artist
                    holder.bindCount(entry.group.tracks.size)
                }
                is ListEntry.ArtistEntry -> {
                    holder.iconBadge.tag = null
                    if (holder.iconBadge.background != null) holder.iconBadge.background = null
                    holder.iconBadge.scaleType = ImageView.ScaleType.CENTER_CROP
                    holder.iconBadge.setPadding(0, 0, 0, 0)
                    holder.iconBadge.setImageResource(R.drawable.ic_default_album_cover)
                    holder.iconBadge.imageTintList = null
                    holder.primary.text = entry.group.name
                    holder.secondary.text = ""
                    holder.bindCount(entry.group.tracks.size)
                }
                is ListEntry.PlaylistEntry -> {
                    holder.iconBadge.tag = null
                    if (holder.iconBadge.background !== holder.folderBadgeBg) {
                        holder.iconBadge.background = holder.folderBadgeBg
                    }
                    holder.iconBadge.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    holder.iconBadge.setPadding(badgePad, badgePad, badgePad, badgePad)
                    holder.iconBadge.setImageResource(R.drawable.ic_playlist)
                    holder.iconBadge.imageTintList = accentTintList
                    holder.primary.text = entry.playlist.name
                    holder.secondary.text = entry.playlist.filePath
                    holder.bindCount(entry.playlist.tracks.size)
                }
            }
        }

        class EntryViewHolder(
            itemView: View,
            val iconBadge: ImageView,
            val primary: TextView,
            val secondary: TextView,
            val trailing: TextView,
            val folderBadgeBg: GradientDrawable
        ) : RecyclerView.ViewHolder(itemView) {
            private val trailBuf = CharArray(16)

            fun bindDuration(durationMs: Long) {
                if (durationMs <= 0L) {
                    trailing.text = ""
                    return
                }
                val totalSec = durationMs / 1000L
                val min = totalSec / 60L
                val sec = totalSec % 60L
                var idx = writeDigits(min, pad2 = false, out = trailBuf, start = 0)
                trailBuf[idx++] = ':'
                idx = writeDigits(sec, pad2 = true, out = trailBuf, start = idx)
                trailing.setText(trailBuf, 0, idx)
            }

            fun bindCount(count: Int) {
                var idx = writeDigits(count.toLong().coerceAtLeast(0L), pad2 = false, out = trailBuf, start = 0)
                trailBuf[idx++] = ' '
                trailBuf[idx++] = 'l'
                trailBuf[idx++] = 'a'
                trailBuf[idx++] = 'g'
                trailBuf[idx++] = 'u'
                trailing.setText(trailBuf, 0, idx)
            }

            private fun writeDigits(value: Long, pad2: Boolean, out: CharArray, start: Int): Int {
                var idx = start
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
                var div = 1L
                while (v / div >= 10L) div *= 10L
                while (div > 0L && idx < out.size) {
                    out[idx++] = ('0'.code + ((v / div) % 10L).toInt()).toChar()
                    div /= 10L
                }
                return idx
            }
        }

        private companion object {
            val TYPEFACE_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    /**
     * Async 96x96 track thumbnail loader backed by LRU caches and URI-tag verification
     * so fast scrolling never swaps artwork between recycled rows.
     */
    private object TrackThumbnailLoader {
        private const val THUMB_SIZE_PX = 96
        private val bitmapCache = LruCache<String, Bitmap>(96)
        private val missingCache = LruCache<String, Boolean>(256)
        private val mainHandler = Handler(Looper.getMainLooper())
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LagukuTrackThumb").apply { isDaemon = true }
        }

        fun bind(imageView: ImageView, track: Track, onDecoded: (() -> Unit)? = null) {
            val key = track.contentUri.ifBlank { track.path }
            imageView.tag = key
            val cached = bitmapCache.get(key)?.takeIf { !it.isRecycled }
            if (cached != null) {
                imageView.setImageBitmap(cached)
                return
            }
            imageView.setImageResource(R.drawable.ic_default_album_cover)
            if (track.path.isBlank() || missingCache.get(key) == true) return

            val filePath = track.path
            executor.execute {
                val again = bitmapCache.get(key)?.takeIf { !it.isRecycled }
                if (again != null) {
                    mainHandler.post {
                        if (imageView.tag == key) {
                            imageView.setImageBitmap(again)
                            onDecoded?.invoke()
                        }
                    }
                    return@execute
                }
                val bytes = runCatching { BinaryTagReader.read(File(filePath)).artworkBytes }.getOrNull()
                val bmp = bytes?.let { decodeThumbnail(it) }
                if (bmp == null) {
                    missingCache.put(key, true)
                    return@execute
                }
                bitmapCache.put(key, bmp)
                mainHandler.post {
                    if (imageView.tag == key) {
                        imageView.setImageBitmap(bmp)
                        onDecoded?.invoke()
                    }
                }
            }
        }

        private fun decodeThumbnail(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null

            var sampleSize = 1
            while (w / sampleSize > THUMB_SIZE_PX * 2 && h / sampleSize > THUMB_SIZE_PX * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            if (decoded.width <= THUMB_SIZE_PX * 2 && decoded.height <= THUMB_SIZE_PX * 2) {
                return decoded
            }
            val scaled = Bitmap.createScaledBitmap(decoded, THUMB_SIZE_PX, THUMB_SIZE_PX, true)
            if (scaled !== decoded) decoded.recycle()
            return scaled
        }
    }

    private companion object {
        const val CHANNEL_NAV_COMPACT = 30
        const val CHANNEL_ROW_STAGGER = 41
        const val CHANNEL_MINI_SWIPE = 42
    }
}
