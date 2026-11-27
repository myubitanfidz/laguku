package com.laguku.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.laguku.player.audio.AudioEngine
import com.laguku.player.data.LagukuBackupManager
import com.laguku.player.data.LibraryScanner
import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.lyrics.LrcParser
import com.laguku.player.lyrics.OnlineLyricFetcher
import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.metadata.BinaryTagWriter
import com.laguku.player.metadata.TagEditRequest
import com.laguku.player.model.AudioDspConfig
import com.laguku.player.model.AudioQualityInfo
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.LosslessAuditStatus
import com.laguku.player.model.LyricLine
import com.laguku.player.model.LyricSource
import com.laguku.player.model.Playlist
import com.laguku.player.model.Track
import com.laguku.player.service.ActiveOutputRoute
import com.laguku.player.service.AlarmScheduler
import com.laguku.player.service.BluetoothProfileManager
import com.laguku.player.service.BluetoothSettings
import com.laguku.player.service.ClickGesture
import com.laguku.player.service.DeviceRouteType
import com.laguku.player.service.MediaAction
import com.laguku.player.service.MorningAlarmConfig
import com.laguku.player.service.MultiClickMediaMapper
import com.laguku.player.service.PlaybackService
import com.laguku.player.service.TrackInstalledEvent
import com.laguku.player.service.TrackInstalledReceiver
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.InteractiveGlassScrubView
import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.glass.StackBlur
import com.laguku.player.ui.library.InsightsSheetView
import com.laguku.player.ui.library.LibraryUtilitiesSheetView
import com.laguku.player.ui.library.LibraryView
import com.laguku.player.ui.motion.PageMotionSpec
import com.laguku.player.ui.motion.SpringAnimatorHelper
import com.laguku.player.ui.player.AudioLabSheetView
import com.laguku.player.ui.player.LyricShareCardRenderer
import com.laguku.player.ui.player.NowPlayingView
import com.laguku.player.ui.player.TapToSyncSheetView
import com.laguku.player.ui.player.WaveformPillSeekBarView
import com.laguku.player.widget.LagukuPillWidgetProvider
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Main single-activity host coordinating edge-to-edge Liquid Glass UI,
 * adaptive blurred backdrop crossfade (or AMOLED #000000 Night Mode),
 * progressive disclosure bottom sheets (Audio Lab, Koleksi & Utilitas, Insights),
 * Android 16/17 Predictive Back, and battery-strict lifecycle gating.
 */
class MainActivity : Activity(), AudioEngine.Listener, LibraryView.Callbacks, PlaybackService.RouteListener {

    private lateinit var rootFrame: FrameLayout
    private lateinit var backdropPrimary: ImageView
    private lateinit var backdropSecondary: ImageView
    private lateinit var amoledOverlayView: View
    private lateinit var libraryView: LibraryView
    private lateinit var nowPlayingView: NowPlayingView
    private lateinit var audioLabSheet: AudioLabSheetView
    private lateinit var utilitiesSheet: LibraryUtilitiesSheetView
    private lateinit var insightsSheet: InsightsSheetView
    private lateinit var tapToSyncSheet: TapToSyncSheetView

    private var showingPrimaryBackdrop = true
    private var isNowPlayingVisible = false
    private var currentPalette: CoverPalette = CoverPaletteExtractor.defaultPalette()

    private val backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val artworkToken = AtomicInteger(0)

    private var playbackBinder: PlaybackService.LocalBinder? = null
    private var audioEngine: AudioEngine? = null
    private var stateStore: PlaybackStateStore? = null
    private var btManager: BluetoothProfileManager? = null
    private var clickMapper: MultiClickMediaMapper? = null

    private var serviceBound: Boolean = false
    private var rawScannedTracks: List<Track> = emptyList()
    private var cachedTracks: List<Track> = emptyList()
    private var userPlaylists: List<Playlist> = emptyList()
    private var currentActiveQueue: List<Track> = emptyList()
    private var lastLoadedTrackPath: String? = null
    private var lastDisplayCover: Bitmap? = null
    private var amoledNightMode: Boolean = false
    private var amoledOverlayProgress: Float = 0f
    private var pendingShuffleAllFromShortcut: Boolean = false
    private var pendingViewUri: Uri? = null
    private var lastObservedInstallEventMs: Long = 0L
    private val trackInstalledListener: (TrackInstalledEvent) -> Unit = { event ->
        runOnUiThread {
            if (!isDestroyed) {
                refreshLagukuLibraryOnInstall(event)
            }
        }
    }
    private var currentTransparencyLevel: Float = PlaybackStateStore.DEFAULT_GLASS_TRANSPARENCY
    private var currentAccessibilityFlags = LiquidGlassDrawable.AccessibilityFlags()
    private var lastUiAbLoopJumpElapsedMs: Long = 0L

    private var backCallback: OnBackInvokedCallback? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PlaybackService.LocalBinder ?: return
            playbackBinder = binder
            val engine = binder.getEngine()
            audioEngine = engine
            stateStore = binder.getStateStore()
            btManager = binder.getBluetoothManager()
            clickMapper = binder.getClickMapper()

            engine.addListener(this@MainActivity)
            binder.getService().addRouteListener(this@MainActivity)
            nowPlayingView.attachEngine(engine)

            amoledNightMode = stateStore?.isAmoledNightMode() == true
            applyAmoledNightMode(amoledNightMode, animate = false)
            currentTransparencyLevel = stateStore?.getGlassTransparencyLevel() ?: PlaybackStateStore.DEFAULT_GLASS_TRANSPARENCY
            applyGlassSettingsToViews()
            val highlightFrac = stateStore?.getLyricHighlightFraction() ?: PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL
            nowPlayingView.setLyricHighlightFraction(highlightFrac)

            onTrackChanged(engine.getCurrentTrack())
            onPlaybackStateChanged(engine.isPlaying())
            onPositionUpdated(engine.getCurrentPositionMs(), engine.getDurationMs())
            engine.setUiPositionUpdatesEnabled(true)
            engine.setVisualizerActive(isNowPlayingVisible && nowPlayingView.isVisualizerDemanded())

            if (cachedTracks.isNotEmpty()) {
                refreshFilteredLibraryUi()
            }
            val viewUri = pendingViewUri
            if (viewUri != null) {
                pendingViewUri = null
                openAndPlayExternalUri(viewUri)
            } else if (pendingShuffleAllFromShortcut && cachedTracks.isNotEmpty()) {
                pendingShuffleAllFromShortcut = false
                onShufflePlayAll(cachedTracks)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackBinder?.getService()?.removeRouteListener(this@MainActivity)
            audioEngine?.removeListener(this@MainActivity)
            playbackBinder = null
            audioEngine = null
            nowPlayingView.attachEngine(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        buildRootUi()
        setContentView(rootFrame)
        registerPredictiveBack()

        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        if (hasAudioPermission()) {
            libraryView.setPermissionRequired(false)
            loadLibraryAsync()
            requestNotificationPermissionIfNeeded()
        } else {
            libraryView.setPermissionRequired(true)
            requestAudioPermissions()
        }

        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        currentAccessibilityFlags = LiquidGlassDrawable.LayerTokens.readSystemAccessibilityFlags(this)
        applyGlassSettingsToViews()
        TrackInstalledReceiver.addListener(trackInstalledListener)
        val latestInstall = TrackInstalledReceiver.latestEvent
        if (latestInstall != null && latestInstall.receivedAtMs > lastObservedInstallEventMs) {
            refreshLagukuLibraryOnInstall(latestInstall)
        }
        SpringAnimatorHelper.setHostActive(true)
        libraryView.setHostActive(true)
        nowPlayingView.setHostActive(true)
        audioEngine?.setUiPositionUpdatesEnabled(true)
        audioEngine?.setVisualizerActive(isNowPlayingVisible && nowPlayingView.isVisualizerDemanded())
        if (!serviceBound) {
            val serviceIntent = Intent(this, PlaybackService::class.java)
            serviceBound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        TrackInstalledReceiver.removeListener(trackInstalledListener)
        SpringAnimatorHelper.setHostActive(false)
        libraryView.setHostActive(false)
        nowPlayingView.setHostActive(false)
        audioEngine?.setVisualizerActive(false)
        audioEngine?.setUiPositionUpdatesEnabled(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let {
                runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            }
        }
        SpringAnimatorHelper.setHostActive(false)
        SpringAnimatorHelper.cancelSpring(nowPlayingView)
        SpringAnimatorHelper.cancelSpring(libraryView)
        SpringAnimatorHelper.cancelSpring(audioLabSheet)
        SpringAnimatorHelper.cancelSpring(utilitiesSheet)
        SpringAnimatorHelper.cancelSpring(insightsSheet)
        SpringAnimatorHelper.cancelSpring(tapToSyncSheet)
        lastDisplayCover = null
        playbackBinder?.getService()?.removeRouteListener(this)
        audioEngine?.removeListener(this)
        if (serviceBound) {
            runCatching { unbindService(serviceConnection) }
            serviceBound = false
        }
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSIONS) {
            if (hasAudioPermission()) {
                libraryView.setPermissionRequired(false)
                loadLibraryAsync()
            } else {
                libraryView.setPermissionRequired(true)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_IMPORT_M3U8 -> importPlaylistFromUri(uri)
            REQ_EXPORT_M3U8 -> exportPlaylistToUri(uri)
            REQ_EXPORT_BACKUP -> exportBackupToUri(uri)
            REQ_IMPORT_BACKUP -> importBackupFromUri(uri)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBackNavigation()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // --- PlaybackService.RouteListener ---

    override fun onOutputRouteChanged(route: ActiveOutputRoute, btLatencyOffsetMs: Long) {
        runOnUiThread {
            if (isDestroyed) return@runOnUiThread
            nowPlayingView.setOutputRouteLabel(route.displayName)
            val trackPath = audioEngine?.getCurrentTrack()?.path.orEmpty()
            val userOffset = if (trackPath.isNotBlank()) stateStore?.getLyricOffsetMs(trackPath) ?: 0L else 0L
            nowPlayingView.setLyricOffsets(userOffset, btLatencyOffsetMs)
        }
    }

    // --- AudioEngine.Listener ---

    override fun onTrackChanged(track: Track?) {
        nowPlayingView.onTrackChanged(track)
        libraryView.updateMiniPlayer(track, audioEngine?.isPlaying() == true, lastDisplayCover)
        val userOff = track?.path?.takeIf { it.isNotBlank() }?.let { stateStore?.getLyricOffsetMs(it) } ?: 0L
        val btOff = playbackBinder?.getService()?.getActiveBtLatencyOffsetMs() ?: 0L
        nowPlayingView.setLyricOffsets(userOff, btOff)
        processTrackArtworkAndLyricsAsync(track)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        nowPlayingView.onPlaybackStateChanged(isPlaying)
        libraryView.updateMiniPlayer(audioEngine?.getCurrentTrack(), isPlaying, lastDisplayCover)
    }

    override fun onPositionUpdated(positionMs: Long, durationMs: Long) {
        val eng = audioEngine
        if (eng != null && eng.isPlaying()) {
            val (aMs, bMs) = eng.getAbLoop()
            val nowElapsed = SystemClock.elapsedRealtime()
            if (InteractiveGlassScrubView.AbLoopAndTempoMath.shouldTriggerLoopJump(positionMs, aMs, bMs) &&
                nowElapsed - lastUiAbLoopJumpElapsedMs > 280L
            ) {
                lastUiAbLoopJumpElapsedMs = nowElapsed
                eng.seekTo(aMs!!)
            }
        }
        nowPlayingView.onPositionUpdated(positionMs, durationMs)
        libraryView.updateMiniPlayerProgress(positionMs, durationMs)
    }

    override fun onQualityChanged(quality: AudioQualityInfo) {
        nowPlayingView.onQualityChanged(quality)
    }

    override fun onLosslessAuditChanged(status: LosslessAuditStatus) {
        nowPlayingView.setLosslessAuditStatus(status)
    }

    override fun onVisualizerLevel(level: Float) {
        nowPlayingView.setVisualizerLevel(level)
    }

    override fun onAbLoopChanged(startMs: Long?, endMs: Long?) {
        nowPlayingView.setAbLoop(startMs, endMs)
    }

    override fun onQueueCompleted() {
        nowPlayingView.onPlaybackStateChanged(false)
        libraryView.updateMiniPlayer(audioEngine?.getCurrentTrack(), false, lastDisplayCover)
    }

    // --- LibraryView.Callbacks ---

    override fun onPlayTracks(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        currentActiveQueue = tracks
        nowPlayingView.setQueueSnapshot(tracks)
        val selected = tracks.getOrNull(startIndex)
        audioEngine?.setQueue(tracks, startIndex)
        if (selected != null) {
            val resumeMs = stateStore?.shouldAutoResume(selected)
            if (resumeMs != null && resumeMs > 0L) {
                audioEngine?.seekTo(resumeMs)
            }
        }
    }

    override fun onShufflePlayAll(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        currentActiveQueue = tracks
        nowPlayingView.setQueueSnapshot(tracks)
        audioEngine?.setShuffle(true)
        audioEngine?.setQueue(tracks, 0)
    }

    override fun onTogglePlayPause() {
        audioEngine?.togglePlayPause()
    }

    override fun onNextTrack() {
        audioEngine?.next()
    }

    override fun onPreviousTrack() {
        audioEngine?.previous()
    }

    override fun onOpenNowPlaying() {
        showNowPlaying()
    }

    override fun onRequestStoragePermission() {
        requestAudioPermissions()
    }

    override fun onImportM3u8Clicked() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*"))
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_IMPORT_M3U8)
        }
    }

    override fun onExportM3u8Clicked() {
        val tracksToExport = if (currentActiveQueue.isNotEmpty()) currentActiveQueue else cachedTracks
        if (tracksToExport.isEmpty()) {
            Toast.makeText(this, R.string.empty_library, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/x-mpegurl"
            putExtra(Intent.EXTRA_TITLE, "laguku_playlist.m3u8")
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_EXPORT_M3U8)
        }
    }

    override fun onCreatePlaylistClicked() {
        val tracksToSave = if (currentActiveQueue.isNotEmpty()) currentActiveQueue else cachedTracks
        if (tracksToSave.isEmpty()) {
            Toast.makeText(this, R.string.empty_library, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "Nama playlist"
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.playlist_create)
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val rawName = input.text?.toString()?.trim().orEmpty()
                val safeName = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "Playlist" }
                saveInternalPlaylistAsync(safeName, tracksToSave)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.decorView?.let { SpringAnimatorHelper.springReveal(it, fromScale = 0.95f) }
    }

    override fun onOpenUtilitiesClicked() {
        val store = stateStore ?: return
        val mapper = clickMapper ?: return
        utilitiesSheet.bindState(
            minDurationEnabled = store.isMinDurationFilterEnabled(),
            alarmConfig = store.getMorningAlarm(),
            twsMappings = mapper.getAllMappings(),
            blacklistedFolders = store.getBlacklistedFolders(),
            glassTransparencyLevel = currentTransparencyLevel
        )
        showSheet(utilitiesSheet)
    }

    override fun onEnqueueTrack(track: Track) {
        audioEngine?.enqueue(track)
        Toast.makeText(this, "Ditambahkan ke antrean: ${track.title}", Toast.LENGTH_SHORT).show()
    }

    override fun onPlayNextTrack(track: Track) {
        audioEngine?.playNext(track)
        Toast.makeText(this, "Diputar berikutnya: ${track.title}", Toast.LENGTH_SHORT).show()
    }

    override fun onEditTrackTags(track: Track) {
        showTagEditorDialog(track)
    }

    override fun onTogglePinFolder(folderPath: String) {
        val pinned = stateStore?.togglePinnedFolder(folderPath) ?: return
        refreshFilteredLibraryUi()
        Toast.makeText(this, if (pinned) "Folder disematkan" else "Sematan dilepas", Toast.LENGTH_SHORT).show()
    }

    override fun onBlacklistFolder(folderPath: String) {
        stateStore?.addBlacklistedFolder(folderPath)
        refreshFilteredLibraryUi()
        Toast.makeText(this, "Folder disembunyikan", Toast.LENGTH_SHORT).show()
    }

    override fun onRefreshFolder(folderPath: String) {
        LibraryScanner.refreshFolder(this, File(folderPath)) {
            runOnUiThread { loadLibraryAsync() }
        }
    }

    // --- UI Construction & Navigation ---

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun buildRootUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(currentPalette.deepBackdrop)
        }

        backdropPrimary = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        backdropSecondary = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        amoledOverlayView = View(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = 0f
            visibility = View.GONE
        }

        libraryView = LibraryView(this, this)
        nowPlayingView = NowPlayingView(this) {
            hideNowPlaying()
        }.apply {
            visibility = View.GONE
            alpha = 0f
            setPlayerVisible(false)
            onOpenAudioLab = { openAudioLabSheet() }
            onToggleAmoled = { toggleAmoledNightMode() }
            onLiveLyricOffsetChanged = { track, offsetMs ->
                if (track.path.isNotBlank()) {
                    stateStore?.setLyricOffsetMs(track.path, offsetMs)
                }
            }
            onLyricHighlightChanged = { fraction ->
                stateStore?.setLyricHighlightFraction(fraction)
            }
            onSaveLyricOffset = { track, offsetMs ->
                if (track.path.isNotBlank()) {
                    stateStore?.setLyricOffsetMs(track.path, offsetMs)
                    val lrcFile = File(File(track.path).parentFile, "${File(track.path).nameWithoutExtension}.lrc")
                    backgroundExecutor.execute {
                        val savedFile = LrcParser.writeOffsetToLrcFile(lrcFile, offsetMs, getMusicRootDir())
                        runOnUiThread {
                            val msg = if (savedFile) "Offset lirik disimpan ke .lrc" else "Offset lirik disimpan"
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            onSearchOnlineLyrics = { track -> searchOnlineLyricsAsync(track) }
            onOpenTapToSync = { track, prefill ->
                tapToSyncSheet.bindTrack(track, audioEngine, prefill)
                showSheet(tapToSyncSheet)
            }
            onShareLyricQuote = { track, lines ->
                shareLyricQuoteCardAsync(track, lines)
            }
            onVisualizerDemandChanged = { demanded ->
                audioEngine?.setVisualizerActive(isNowPlayingVisible && demanded)
            }
            onSetAbPointA = {
                val eng = audioEngine
                if (eng != null) {
                    eng.setAbLoop(eng.getCurrentPositionMs(), eng.getAbLoop().second)
                }
            }
            onSetAbPointB = {
                val eng = audioEngine
                if (eng != null) {
                    val start = eng.getAbLoop().first ?: 0L
                    eng.setAbLoop(start, eng.getCurrentPositionMs())
                }
            }
            onClearAbLoop = {
                audioEngine?.clearAbLoop()
            }
            onVocalTempoSelected = { speed ->
                val eng = audioEngine
                if (eng != null) {
                    eng.setVocalTempo(speed)
                    nowPlayingView.setVocalTempoDisplay(speed)
                    playbackBinder?.getService()?.saveCurrentRouteProfile()
                }
            }
        }

        audioLabSheet = AudioLabSheetView(this) {
            hideSheet(audioLabSheet)
        }.apply {
            visibility = View.GONE
            callbacks = object : AudioLabSheetView.Callbacks {
                override fun onDspConfigChanged(config: AudioDspConfig) {
                    audioEngine?.updateDspConfig(config)
                    nowPlayingView.setVocalTempoDisplay(config.speed)
                    playbackBinder?.getService()?.saveCurrentRouteProfile()
                }

                override fun onBluetoothSettingsChanged(settings: BluetoothSettings, deviceLatencyMs: Long) {
                    val mgr = btManager ?: return
                    mgr.updateSettings(settings)
                    val route = playbackBinder?.getActiveOutputRoute() ?: return
                    val profile = mgr.getProfileForRoute(route)
                    mgr.saveProfile(profile.copy(lyricLatencyOffsetMs = deviceLatencyMs))
                    val trackPath = audioEngine?.getCurrentTrack()?.path.orEmpty()
                    val userOff = if (trackPath.isNotBlank()) stateStore?.getLyricOffsetMs(trackPath) ?: 0L else 0L
                    nowPlayingView.setLyricOffsets(userOff, mgr.getEffectiveLyricLatencyOffsetMs(route))
                }

                override fun onSetAbStart() {
                    val eng = audioEngine ?: return
                    eng.setAbLoop(eng.getCurrentPositionMs(), eng.getAbLoop().second)
                }

                override fun onSetAbEnd() {
                    val eng = audioEngine ?: return
                    val start = eng.getAbLoop().first ?: 0L
                    eng.setAbLoop(start, eng.getCurrentPositionMs())
                }

                override fun onClearAbLoop() {
                    audioEngine?.clearAbLoop()
                }

                override fun onJumpToChapter(startMs: Long) {
                    audioEngine?.seekTo(startMs)
                    hideSheet(audioLabSheet)
                }
            }
        }

        insightsSheet = InsightsSheetView(this) {
            hideSheet(insightsSheet)
        }.apply { visibility = View.GONE }

        tapToSyncSheet = TapToSyncSheetView(this) {
            hideSheet(tapToSyncSheet)
        }.apply {
            visibility = View.GONE
            onSaveLrc = { track, lrcContent ->
                saveLyricsAndApply(track, lrcContent)
                hideSheet(tapToSyncSheet)
            }
        }

        utilitiesSheet = LibraryUtilitiesSheetView(this) {
            hideSheet(utilitiesSheet)
        }.apply {
            visibility = View.GONE
            callbacks = object : LibraryUtilitiesSheetView.Callbacks {
                override fun onOpenInsights() {
                    val insights = stateStore?.getInsightsSummary() ?: return
                    insightsSheet.bindInsights(insights)
                    showSheet(insightsSheet)
                }

                override fun onToggleMinDurationFilter(enabled: Boolean) {
                    stateStore?.setMinDurationFilterEnabled(enabled)
                    refreshFilteredLibraryUi()
                }

                override fun onRunHealthScan() {
                    runHealthScanAsync()
                }

                override fun onCycleTwsMapping(gesture: ClickGesture): MediaAction {
                    val mapper = clickMapper ?: return MediaAction.NONE
                    val all = MediaAction.entries
                    val cur = mapper.getMapping(gesture)
                    val next = all[(all.indexOf(cur) + 1) % all.size]
                    mapper.setMapping(gesture, next)
                    return next
                }

                override fun onAlarmChanged(config: MorningAlarmConfig) {
                    stateStore?.setMorningAlarm(config)
                    AlarmScheduler.schedule(this@MainActivity, config)
                }

                override fun onEditCurrentTrackTags() {
                    val track = audioEngine?.getCurrentTrack()
                    if (track == null) {
                        Toast.makeText(this@MainActivity, "Tidak ada lagu aktif", Toast.LENGTH_SHORT).show()
                    } else {
                        showTagEditorDialog(track)
                    }
                }

                override fun onExportBackupJson() {
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "laguku_backup.json")
                    }
                    runCatching {
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, REQ_EXPORT_BACKUP)
                    }
                }

                override fun onImportBackupJson() {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
                    }
                    runCatching {
                        @Suppress("DEPRECATION")
                        startActivityForResult(intent, REQ_IMPORT_BACKUP)
                    }
                }

                override fun onUnhideBlacklistedFolder(folderPath: String) {
                    stateStore?.removeBlacklistedFolder(folderPath)
                    refreshFilteredLibraryUi()
                }

                override fun onPinWidgetToHomeScreen(): Boolean {
                    val status = LagukuPillWidgetProvider.requestPinWidgetStatus(this@MainActivity)
                    val requested = status == LagukuPillWidgetProvider.PinWidgetStatus.PIN_REQUEST_SENT
                    val msgRes = LagukuPillWidgetProvider.resolvePinFeedbackRes(status)
                    if (requested) {
                        Toast.makeText(this@MainActivity, getString(msgRes), Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle(R.string.pin_widget_label)
                            .setMessage(msgRes)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    return requested
                }

                override fun onGlassTransparencyChanged(level: Float) {
                    currentTransparencyLevel = level.coerceIn(0f, 1f)
                    stateStore?.setGlassTransparencyLevel(currentTransparencyLevel)
                    applyGlassSettingsToViews()
                }
            }
        }

        rootFrame.addView(backdropPrimary, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(backdropSecondary, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(amoledOverlayView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(libraryView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(nowPlayingView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(audioLabSheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(utilitiesSheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(insightsSheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootFrame.addView(tapToSyncSheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            libraryView.setPadding(sysBars.left, 0, sysBars.right, 0)
            libraryView.applySystemInsets(statusTop, navBars.bottom)
            nowPlayingView.setPadding(sysBars.left, 0, sysBars.right, 0)
            nowPlayingView.applySystemInsets(statusTop, navBars.bottom)
            audioLabSheet.setPadding(sysBars.left, 0, sysBars.right, 0)
            audioLabSheet.applyBottomInset(navBars.bottom)
            utilitiesSheet.setPadding(sysBars.left, 0, sysBars.right, 0)
            utilitiesSheet.applyBottomInset(navBars.bottom)
            insightsSheet.setPadding(sysBars.left, 0, sysBars.right, 0)
            insightsSheet.applyBottomInset(navBars.bottom)
            tapToSyncSheet.setPadding(sysBars.left, 0, sysBars.right, 0)
            tapToSyncSheet.applyBottomInset(navBars.bottom)
            insets
        }
    }

    private fun applyGlassSettingsToViews() {
        libraryView.setGlassTransparency(currentTransparencyLevel)
        libraryView.setAccessibilityFlags(currentAccessibilityFlags)
        nowPlayingView.setGlassTransparency(currentTransparencyLevel)
        nowPlayingView.setAccessibilityFlags(currentAccessibilityFlags)
        utilitiesSheet.setGlassTransparency(currentTransparencyLevel)
        utilitiesSheet.setAccessibilityFlags(currentAccessibilityFlags)
        audioLabSheet.setGlassTransparency(currentTransparencyLevel)
        audioLabSheet.setAccessibilityFlags(currentAccessibilityFlags)
        insightsSheet.setGlassTransparency(currentTransparencyLevel)
        insightsSheet.setAccessibilityFlags(currentAccessibilityFlags)
        tapToSyncSheet.setGlassTransparency(currentTransparencyLevel)
        tapToSyncSheet.setAccessibilityFlags(currentAccessibilityFlags)
    }

    private fun openAudioLabSheet() {
        val binder = playbackBinder ?: return
        val route = binder.getActiveOutputRoute()
        val mgr = binder.getBluetoothManager()
        val lat = mgr.getProfileForRoute(route).lyricLatencyOffsetMs
        audioLabSheet.bindState(audioEngine, route, mgr.getSettings(), lat)
        showSheet(audioLabSheet)
    }

    private fun anchorForSheet(sheet: View): View? {
        return when (sheet) {
            audioLabSheet -> nowPlayingView.getAudioLabAnchorView()
            utilitiesSheet -> libraryView.getUtilitiesAnchorView()
            insightsSheet -> utilitiesSheet.getInsightsAnchorView()
            tapToSyncSheet -> nowPlayingView.getTapToSyncAnchorView()
            else -> null
        }
    }

    private fun showSheet(sheet: View) {
        SpringAnimatorHelper.springMorphReveal(sheet, anchorForSheet(sheet), rootFrame)
    }

    private fun hideSheet(sheet: View) {
        if (sheet.visibility != View.VISIBLE) return
        SpringAnimatorHelper.springMorphDismiss(sheet, anchorForSheet(sheet), rootFrame)
    }

    private fun registerPredictiveBack() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback {
                if (!handleBackNavigation()) {
                    finish()
                }
            }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
    }

    private fun handleBackNavigation(): Boolean {
        if (audioLabSheet.visibility == View.VISIBLE) {
            hideSheet(audioLabSheet)
            return true
        }
        if (insightsSheet.visibility == View.VISIBLE) {
            hideSheet(insightsSheet)
            return true
        }
        if (utilitiesSheet.visibility == View.VISIBLE) {
            hideSheet(utilitiesSheet)
            return true
        }
        if (tapToSyncSheet.visibility == View.VISIBLE) {
            hideSheet(tapToSyncSheet)
            return true
        }
        if (isNowPlayingVisible) {
            if (nowPlayingView.isModalOpen()) {
                nowPlayingView.closeModal()
            } else if (nowPlayingView.isZenMode()) {
                nowPlayingView.exitZenMode()
            } else {
                hideNowPlaying()
            }
            return true
        }
        if (libraryView.handleBackStep()) {
            return true
        }
        return false
    }

    private fun handleLaunchIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val viewUri = intent.data
                if (viewUri != null) {
                    intent.data = null
                    if (audioEngine != null) {
                        openAndPlayExternalUri(viewUri)
                    } else {
                        pendingViewUri = viewUri
                    }
                }
            }
            ACTION_SHORTCUT_SHUFFLE_ALL -> {
                if (cachedTracks.isNotEmpty() && audioEngine != null) {
                    onShufflePlayAll(cachedTracks)
                } else {
                    pendingShuffleAllFromShortcut = true
                }
            }
            ACTION_SHORTCUT_AMOLED_NIGHT -> {
                toggleAmoledNightMode(forceOn = true)
            }
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) {
            intent.removeExtra(EXTRA_OPEN_NOW_PLAYING)
            showNowPlaying()
        }
    }

    private fun openAndPlayExternalUri(uri: Uri) {
        backgroundExecutor.execute {
            val resolved = LibraryScanner.buildTrackFromUri(applicationContext, uri, getMusicRootDir())
                ?: return@execute
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                lastLoadedTrackPath = null
                val updatedRaw = if (resolved.path.isNotBlank()) {
                    rawScannedTracks.filterNot { it.path == resolved.path } + resolved
                } else {
                    rawScannedTracks + resolved
                }
                rawScannedTracks = updatedRaw.sortedBy { it.title.lowercase() }
                refreshFilteredLibraryUi()

                val baseQueue = cachedTracks.filterNot {
                    (resolved.path.isNotBlank() && it.path == resolved.path) ||
                        it.contentUri == resolved.contentUri
                }
                val playQueue = listOf(resolved) + baseQueue
                onPlayTracks(playQueue, 0)
                showNowPlaying()
            }
        }
    }

    private fun refreshLagukuLibraryOnInstall(event: TrackInstalledEvent) {
        lastObservedInstallEventMs = maxOf(lastObservedInstallEventMs, event.receivedAtMs)
        val lagukuDir = File(getMusicRootDir(), "Laguku")
        backgroundExecutor.execute {
            val dirTracks = LibraryScanner.scanDirectory(lagukuDir)
            val eventTrack = event.audioUri?.let { raw ->
                runCatching {
                    LibraryScanner.buildTrackFromUri(applicationContext, Uri.parse(raw), getMusicRootDir())
                }.getOrNull()
            }
            val combinedDirTracks = if (eventTrack != null && eventTrack.path.isNotBlank()) {
                dirTracks.filterNot { it.path == eventTrack.path } + eventTrack
            } else {
                dirTracks
            }
            val merged = LibraryScanner.mergeDirectoryTracks(rawScannedTracks, combinedDirTracks, lagukuDir)
            val playlists = scanPlaylists(merged)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rawScannedTracks = merged
                userPlaylists = playlists
                refreshFilteredLibraryUi()
            }
        }
    }

    private fun toggleAmoledNightMode(forceOn: Boolean? = null) {
        val next = forceOn ?: !amoledNightMode
        amoledNightMode = next
        stateStore?.setAmoledNightMode(next)
        applyAmoledNightMode(next, animate = true)
    }

    private fun applyAmoledNightMode(enabled: Boolean, animate: Boolean = true) {
        val target = if (enabled) 1f else 0f
        nowPlayingView.setAmoledMode(enabled, animate = animate)
        libraryView.setAmoledMode(enabled)

        backdropPrimary.visibility = View.VISIBLE
        backdropSecondary.visibility = View.VISIBLE
        amoledOverlayView.visibility = View.VISIBLE

        if (!animate || SpringAnimatorHelper.isReducedMotionEnabled(this)) {
            SpringAnimatorHelper.cancelSpring(amoledOverlayView, CHANNEL_AMOLED_OVERLAY)
            amoledOverlayProgress = target
            amoledOverlayView.alpha = SpringAnimatorHelper.AmoledTransitionMath.computeOverlayAlpha(target)
            if (enabled) {
                rootFrame.setBackgroundColor(0xFF000000.toInt())
                backdropPrimary.visibility = View.GONE
                backdropSecondary.visibility = View.GONE
            } else {
                rootFrame.setBackgroundColor(currentPalette.deepBackdrop)
                amoledOverlayView.visibility = View.GONE
            }
            return
        }

        if (!enabled) {
            rootFrame.setBackgroundColor(currentPalette.deepBackdrop)
        }
        SpringAnimatorHelper.animateSpring(
            key = amoledOverlayView,
            start = amoledOverlayProgress,
            target = target,
            stiffness = SpringAnimatorHelper.AmoledTransitionMath.AMOLED_STIFFNESS,
            dampingRatio = SpringAnimatorHelper.AmoledTransitionMath.AMOLED_DAMPING_RATIO,
            channel = CHANNEL_AMOLED_OVERLAY,
            onUpdate = { p ->
                amoledOverlayProgress = p.coerceIn(0f, 1f)
                amoledOverlayView.alpha = SpringAnimatorHelper.AmoledTransitionMath.computeOverlayAlpha(amoledOverlayProgress)
            },
            onEnd = {
                if (amoledNightMode) {
                    rootFrame.setBackgroundColor(0xFF000000.toInt())
                    backdropPrimary.visibility = View.GONE
                    backdropSecondary.visibility = View.GONE
                } else {
                    amoledOverlayView.visibility = View.GONE
                }
            }
        )
    }

    private fun showNowPlaying() {
        if (isNowPlayingVisible) return
        isNowPlayingVisible = true
        nowPlayingView.setPlayerVisible(true)
        audioEngine?.setVisualizerActive(nowPlayingView.isVisualizerDemanded())
        SpringAnimatorHelper.cancelSpring(nowPlayingView)
        nowPlayingView.visibility = View.VISIBLE
        val startOffset = (rootFrame.height * 0.08f).coerceAtLeast(32f)
        nowPlayingView.alpha = 0f
        nowPlayingView.translationY = startOffset
        nowPlayingView.scaleX = 0.94f
        nowPlayingView.scaleY = 0.94f
        val reduced = SpringAnimatorHelper.isReducedMotionEnabled(this)
        val stiffness = if (reduced) SpringAnimatorHelper.SPRING_REDUCED_MOTION.stiffness else PageMotionSpec.PAGE_MORPH_STIFFNESS
        val damping = if (reduced) SpringAnimatorHelper.SPRING_REDUCED_MOTION.dampingRatio else PageMotionSpec.PAGE_MORPH_DAMPING_RATIO
        SpringAnimatorHelper.animateSpring(
            key = nowPlayingView,
            start = 0f,
            target = 1f,
            stiffness = stiffness,
            dampingRatio = damping,
            onUpdate = { p ->
                val clamped = p.coerceIn(0f, 1f)
                nowPlayingView.alpha = clamped
                nowPlayingView.translationY = startOffset * (1f - p)
                val npScale = PageMotionSpec.LIBRARY_RECEDE_SCALE + (1f - PageMotionSpec.LIBRARY_RECEDE_SCALE) * p
                nowPlayingView.scaleX = npScale
                nowPlayingView.scaleY = npScale
                val libScale = PageMotionSpec.computeLibraryRecedeScale(clamped)
                libraryView.scaleX = libScale
                libraryView.scaleY = libScale
            },
            onEnd = {
                if (isNowPlayingVisible) {
                    libraryView.visibility = View.INVISIBLE
                }
                libraryView.scaleX = 1f
                libraryView.scaleY = 1f
            }
        )
    }

    private fun hideNowPlaying() {
        if (!isNowPlayingVisible) return
        isNowPlayingVisible = false
        nowPlayingView.setPlayerVisible(false)
        audioEngine?.setVisualizerActive(false)
        SpringAnimatorHelper.cancelSpring(nowPlayingView)
        libraryView.visibility = View.VISIBLE
        val endOffset = (rootFrame.height * 0.08f).coerceAtLeast(32f)
        val reduced = SpringAnimatorHelper.isReducedMotionEnabled(this)
        val stiffness = if (reduced) SpringAnimatorHelper.SPRING_REDUCED_MOTION.stiffness else PageMotionSpec.PAGE_MORPH_STIFFNESS
        val damping = if (reduced) SpringAnimatorHelper.SPRING_REDUCED_MOTION.dampingRatio else PageMotionSpec.PAGE_MORPH_DAMPING_RATIO
        SpringAnimatorHelper.animateSpring(
            key = nowPlayingView,
            start = 0f,
            target = 1f,
            stiffness = stiffness,
            dampingRatio = damping,
            onUpdate = { p ->
                val clamped = p.coerceIn(0f, 1f)
                nowPlayingView.alpha = 1f - clamped
                nowPlayingView.translationY = endOffset * p
                val npScale = 1f - (1f - PageMotionSpec.LIBRARY_RECEDE_SCALE) * p
                nowPlayingView.scaleX = npScale
                nowPlayingView.scaleY = npScale
                val libScale = PageMotionSpec.computeLibraryRecedeScale(1f - clamped)
                libraryView.scaleX = libScale
                libraryView.scaleY = libScale
            },
            onEnd = {
                if (!isNowPlayingVisible) {
                    nowPlayingView.visibility = View.GONE
                }
                nowPlayingView.translationY = 0f
                nowPlayingView.scaleX = 1f
                nowPlayingView.scaleY = 1f
                nowPlayingView.alpha = 0f
                libraryView.scaleX = 1f
                libraryView.scaleY = 1f
            }
        )
    }

    // --- Background Processing (64x64 StackBlur + Palette + LRC + Waveform) ---

    private fun processTrackArtworkAndLyricsAsync(track: Track?) {
        if (track == null || track.path.isBlank()) {
            lastLoadedTrackPath = null
            lastDisplayCover = null
            applyPaletteAndBackdrop(
                CoverPaletteExtractor.defaultPalette(),
                null,
                null,
                NowPlayingView.AmbientMeshMath.defaultMeshPalette()
            )
            nowPlayingView.setLyrics(LrcParser.parse(""))
            nowPlayingView.setWaveform(null)
            return
        }

        val token = artworkToken.incrementAndGet()
        val trackPath = track.path
        if (trackPath == lastLoadedTrackPath) {
            return
        }

        backgroundExecutor.execute {
            val file = File(trackPath)
            val embedded = runCatching { BinaryTagReader.readEmbedded(file) }.getOrNull()
            val parsedLyrics = LrcParser.loadTrackLyrics(file, getInternalLyricsDir())
            val waveform = WaveformPillSeekBarView.extractWaveformBars(
                file,
                track.contentUri.ifBlank { trackPath }
            )
            val artBytes = embedded?.artworkBytes ?: file.parentFile?.let { BinaryTagReader.readArtworkSidecar(it) }

            val microBitmap = StackBlur.decodeMicroCover(artBytes, targetSize = 64)
            val blurredBackdrop = microBitmap?.let { StackBlur.createBlurredBackdrop(it, radius = 12) }
            val palette: CoverPalette
            val meshPalette: NowPlayingView.AmbientMeshMath.MeshPalette
            if (microBitmap != null) {
                palette = CoverPaletteExtractor.extract(microBitmap)
                val microPixels = IntArray(microBitmap.width * microBitmap.height)
                microBitmap.getPixels(microPixels, 0, microBitmap.width, 0, 0, microBitmap.width, microBitmap.height)
                meshPalette = NowPlayingView.AmbientMeshMath.extractMeshPaletteFromPixels(microPixels)
            } else {
                palette = CoverPaletteExtractor.defaultPalette()
                meshPalette = NowPlayingView.AmbientMeshMath.defaultMeshPalette()
            }
            val displayCover = if (artBytes != null) {
                StackBlur.decodeMicroCover(artBytes, targetSize = 512)
            } else {
                null
            }
            if (microBitmap != null && microBitmap !== displayCover && !microBitmap.isRecycled) {
                microBitmap.recycle()
            }

            runOnUiThread {
                if (isDestroyed || artworkToken.get() != token) {
                    blurredBackdrop?.recycle()
                    displayCover?.recycle()
                    return@runOnUiThread
                }
                lastLoadedTrackPath = trackPath
                lastDisplayCover = displayCover
                nowPlayingView.setLyrics(parsedLyrics)
                nowPlayingView.setWaveform(waveform)
                applyPaletteAndBackdrop(palette, blurredBackdrop, displayCover, meshPalette)
                libraryView.updateMiniPlayer(track, audioEngine?.isPlaying() == true, displayCover)
            }
        }
    }

    private fun applyPaletteAndBackdrop(
        palette: CoverPalette,
        blurredBackdrop: Bitmap?,
        displayCover: Bitmap?,
        meshPalette: NowPlayingView.AmbientMeshMath.MeshPalette = NowPlayingView.AmbientMeshMath.fromCoverPalette(palette)
    ) {
        val previousBackdropColor = currentPalette.deepBackdrop
        currentPalette = palette
        if (amoledNightMode) {
            rootFrame.setBackgroundColor(0xFF000000.toInt())
            blurredBackdrop?.recycle()
        } else {
            SpringAnimatorHelper.animateBackgroundColor(rootFrame, previousBackdropColor, palette.deepBackdrop)
            val incoming = if (showingPrimaryBackdrop) backdropSecondary else backdropPrimary
            val outgoing = if (showingPrimaryBackdrop) backdropPrimary else backdropSecondary
            showingPrimaryBackdrop = !showingPrimaryBackdrop
            SpringAnimatorHelper.crossfadeBackdrop(incoming, outgoing, blurredBackdrop)
        }
        nowPlayingView.setPalette(palette)
        nowPlayingView.setAmbientMeshPalette(meshPalette)
        nowPlayingView.setCoverArtwork(displayCover)
        libraryView.setPalette(palette)
        audioLabSheet.setPalette(palette)
        utilitiesSheet.setPalette(palette)
        insightsSheet.setPalette(palette)
        tapToSyncSheet.setPalette(palette)
    }

    // --- Online Lyrics (LRCLIB) & Offline Tap-to-Sync Persistence ---

    private fun searchOnlineLyricsAsync(track: Track) {
        val targetPath = track.path
        nowPlayingView.setLyricFetchStatus(getString(R.string.lyrics_searching), isLoading = true)
        backgroundExecutor.execute {
            val fileName = if (targetPath.isNotBlank()) File(targetPath).nameWithoutExtension else ""
            val res = OnlineLyricFetcher.fetch(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                fileNameFallback = fileName
            )
            runOnUiThread {
                if (isDestroyed || audioEngine?.getCurrentTrack()?.path != targetPath) return@runOnUiThread
                when (res) {
                    is OnlineLyricFetcher.FetchResult.Found -> {
                        if (res.isSynced) {
                            nowPlayingView.setLyricFetchStatus(null, isLoading = false)
                            saveLyricsAndApply(track, res.lrcContent)
                        } else {
                            nowPlayingView.setPlainLyricsFallback(res.plainText)
                            nowPlayingView.setLyricFetchStatus(getString(R.string.lyrics_plain_found_hint), isLoading = false)
                            nowPlayingView.setLyrics(LrcParser.parse(res.plainText))
                            saveLyricsOnly(track, res.plainText)
                        }
                    }
                    is OnlineLyricFetcher.FetchResult.NotFound -> {
                        nowPlayingView.setLyricFetchStatus(getString(R.string.lyrics_not_found), isLoading = false)
                    }
                    is OnlineLyricFetcher.FetchResult.NetworkError -> {
                        nowPlayingView.setLyricFetchStatus(getString(R.string.lyrics_offline_error), isLoading = false)
                    }
                }
            }
        }
    }

    private fun saveLyricsAndApply(track: Track, lrcContent: String) {
        nowPlayingView.setLyrics(LrcParser.parse(lrcContent).copy(source = LyricSource.SIDECAR_LRC))
        saveLyricsOnly(track, lrcContent)
    }

    private fun saveLyricsOnly(track: Track, lrcContent: String) {
        if (track.path.isBlank()) return
        val audioFile = File(track.path)
        backgroundExecutor.execute {
            val saved = LrcParser.saveLrcFileSafely(
                audioFile = audioFile,
                lrcContent = lrcContent,
                allowedRootDir = getMusicRootDir(),
                internalFallbackDir = getInternalLyricsDir()
            )
            runOnUiThread {
                if (!isDestroyed && saved != null) {
                    val actualSource = if (saved.parentFile?.canonicalPath == getInternalLyricsDir().canonicalPath) {
                        LyricSource.INTERNAL_FALLBACK
                    } else {
                        LyricSource.SIDECAR_LRC
                    }
                    if (audioEngine?.getCurrentTrack()?.path == track.path) {
                        nowPlayingView.setLyrics(LrcParser.parse(lrcContent).copy(source = actualSource))
                    }
                    Toast.makeText(this@MainActivity, getString(R.string.lyrics_saved_lrc), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getInternalLyricsDir(): File {
        return File(filesDir, "lyrics").apply { mkdirs() }
    }

    // --- Library, Tag Editor, Health Scanner, Backup ---

    private fun loadLibraryAsync() {
        backgroundExecutor.execute {
            var scanned = LibraryScanner.scanMediaStore(applicationContext)
            if (scanned.isEmpty()) {
                scanned = LibraryScanner.scanDirectory(getMusicRootDir())
            }
            val loadedPlaylists = scanPlaylists(scanned)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rawScannedTracks = scanned
                userPlaylists = loadedPlaylists
                refreshFilteredLibraryUi()
                if (pendingShuffleAllFromShortcut && cachedTracks.isNotEmpty() && audioEngine != null) {
                    pendingShuffleAllFromShortcut = false
                    onShufflePlayAll(cachedTracks)
                }
            }
        }
    }

    private fun refreshFilteredLibraryUi() {
        val store = stateStore
        val minDur = if (store == null || store.isMinDurationFilterEnabled()) 30_000L else 0L
        val blacklisted = store?.getBlacklistedFolders() ?: emptySet()
        val pinned = store?.getPinnedFolders() ?: emptySet()
        val filtered = LibraryScanner.filterTracks(rawScannedTracks, minDur, blacklisted)
        cachedTracks = filtered

        val smart = LibraryScanner.buildSmartPlaylists(
            filtered,
            store?.getTotalPlayCountsMap() ?: emptyMap(),
            store?.getMonthlyPlayCountsMap() ?: emptyMap()
        )
        libraryView.setLibraryData(filtered, smart + userPlaylists, pinned)
    }

    private fun runHealthScanAsync() {
        val tracks = rawScannedTracks
        backgroundExecutor.execute {
            val report = LibraryScanner.scanLibraryHealth(tracks)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val msg = "Duplikat: ${report.duplicateGroups.size} grup\nBerkas bermasalah/korup: ${report.corruptTracks.size}"
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Kesehatan Koleksi")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showTagEditorDialog(track: Track) {
        val file = File(track.path)
        if (!BinaryTagWriter.isSupportedFile(file)) {
            Toast.makeText(this, "Edit tag mendukung FLAC, MP3, dan M4A", Toast.LENGTH_SHORT).show()
            return
        }
        val titleEt = EditText(this).apply { setText(track.title); hint = "Judul" }
        val artistEt = EditText(this).apply { setText(track.artist); hint = "Artis" }
        val albumEt = EditText(this).apply { setText(track.album); hint = "Album" }
        val numEt = EditText(this).apply {
            setText(if (track.trackNumber > 0) track.trackNumber.toString() else "")
            hint = "Nomor Trek"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(titleEt)
            addView(artistEt)
            addView(albumEt)
            addView(numEt)
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Edit Tag Audio")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val req = TagEditRequest(
                    title = titleEt.text.toString().trim().takeIf { it.isNotEmpty() },
                    artist = artistEt.text.toString().trim().takeIf { it.isNotEmpty() },
                    album = albumEt.text.toString().trim().takeIf { it.isNotEmpty() },
                    trackNumber = numEt.text.toString().trim().toIntOrNull()
                )
                backgroundExecutor.execute {
                    val ok = BinaryTagWriter.writeTags(file, req)
                    runOnUiThread {
                        if (ok) {
                            Toast.makeText(this@MainActivity, "Tag berhasil diperbarui", Toast.LENGTH_SHORT).show()
                            loadLibraryAsync()
                        } else {
                            Toast.makeText(this@MainActivity, "Gagal menulis tag (cadangan dipulihkan)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        dialog.window?.decorView?.let { SpringAnimatorHelper.springReveal(it, fromScale = 0.95f) }
    }

    private fun shareLyricQuoteCardAsync(track: Track, lines: List<LyricLine>) {
        val cover = lastDisplayCover
        val pal = currentPalette
        backgroundExecutor.execute {
            val file = LyricShareCardRenderer.renderCardFile(this@MainActivity, track, lines, cover, pal)
            runOnUiThread {
                if (file != null && !isDestroyed) {
                    val shareIntent = LyricShareCardRenderer.buildShareIntent(this@MainActivity, file, track.title)
                    runCatching { startActivity(Intent.createChooser(shareIntent, getString(R.string.share_quote_card_label))) }
                } else {
                    Toast.makeText(this@MainActivity, "Gagal membuat kartu lirik", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportBackupToUri(uri: Uri) {
        val store = stateStore ?: return
        val mgr = btManager ?: return
        val mapper = clickMapper ?: return
        val pls = userPlaylists
        backgroundExecutor.execute {
            val json = LagukuBackupManager.exportToJson(store, mgr, mapper, pls, getMusicRootDir())
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                true
            }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(this@MainActivity, if (ok) "Backup JSON diekspor" else "Gagal ekspor backup", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importBackupFromUri(uri: Uri) {
        val store = stateStore ?: return
        val mgr = btManager ?: return
        val mapper = clickMapper ?: return
        backgroundExecutor.execute {
            val json = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull().orEmpty()
            val res = LagukuBackupManager.importFromJson(
                json, store, mgr, mapper, getInternalPlaylistDir(), getMusicRootDir()
            )
            runOnUiThread {
                if (res.success) {
                    amoledNightMode = store.isAmoledNightMode()
                    applyAmoledNightMode(amoledNightMode, animate = false)
                    AlarmScheduler.schedule(this@MainActivity, store.getMorningAlarm())
                    loadLibraryAsync()
                    Toast.makeText(
                        this@MainActivity,
                        "Pulih: ${res.profilesImported} profil, ${res.playlistsImported} playlist",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, res.errorMessage ?: "Backup tidak valid", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun scanPlaylists(tracks: List<Track>): List<Playlist> {
        val byCanonical = HashMap<String, Track>(tracks.size)
        for (t in tracks) {
            if (t.path.isNotBlank()) {
                val key = runCatching { File(t.path).canonicalPath }.getOrDefault(t.path)
                byCanonical[key] = t
            }
        }

        val allowedRoot = getMusicRootDir()
        val playlistDirs = listOf(getInternalPlaylistDir(), allowedRoot)
        val result = mutableListOf<Playlist>()
        val seenPaths = HashSet<String>()

        for (dir in playlistDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { f ->
                f.isFile && f.extension.equals("m3u8", ignoreCase = true)
            } ?: continue
            for (file in files.sortedBy { it.name.lowercase() }) {
                val canonicalFile = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                if (!seenPaths.add(canonicalFile)) continue
                val resolved = LibraryScanner.readM3u8(file, allowedRoot)
                val resolvedTracks = resolved.mapNotNull { f -> byCanonical[f.canonicalPath] }
                result.add(
                    Playlist(
                        name = file.nameWithoutExtension,
                        filePath = file.absolutePath,
                        tracks = resolvedTracks
                    )
                )
            }
        }
        return result
    }

    private fun saveInternalPlaylistAsync(name: String, tracks: List<Track>) {
        backgroundExecutor.execute {
            val outFile = File(getInternalPlaylistDir(), "$name.m3u8")
            LibraryScanner.writeM3u8(outFile, tracks, getMusicRootDir())
            val refreshed = scanPlaylists(rawScannedTracks)
            runOnUiThread {
                if (!isDestroyed) {
                    userPlaylists = refreshed
                    refreshFilteredLibraryUi()
                }
            }
        }
    }

    private fun importPlaylistFromUri(uri: Uri) {
        backgroundExecutor.execute {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull() ?: return@execute

            val tempName = "Impor_${System.currentTimeMillis() % 10000}.m3u8"
            val destFile = File(getInternalPlaylistDir(), tempName)
            destFile.parentFile?.mkdirs()
            destFile.writeText(text, Charsets.UTF_8)

            val allowedRoot = getMusicRootDir()
            val validFiles = LibraryScanner.readM3u8(destFile, allowedRoot)
            val byPath = rawScannedTracks.associateBy { runCatching { File(it.path).canonicalPath }.getOrDefault(it.path) }
            val matchedTracks = validFiles.mapNotNull { byPath[it.canonicalPath] }
            LibraryScanner.writeM3u8(destFile, matchedTracks, allowedRoot)

            val refreshed = scanPlaylists(rawScannedTracks)
            runOnUiThread {
                if (!isDestroyed) {
                    userPlaylists = refreshed
                    refreshFilteredLibraryUi()
                }
            }
        }
    }

    private fun exportPlaylistToUri(uri: Uri) {
        val tracksToExport = if (currentActiveQueue.isNotEmpty()) currentActiveQueue else cachedTracks
        backgroundExecutor.execute {
            val tempFile = File(cacheDir, "export_tmp.m3u8")
            LibraryScanner.writeM3u8(tempFile, tracksToExport, getMusicRootDir())
            val content = runCatching { tempFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
            runCatching { tempFile.delete() }
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write(content)
                }
            }
        }
    }

    private fun getInternalPlaylistDir(): File {
        return File(filesDir, "playlists").apply { mkdirs() }
    }

    private fun getMusicRootDir(): File {
        @Suppress("DEPRECATION")
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            ?: Environment.getExternalStorageDirectory()
            ?: filesDir
    }

    // --- Permissions ---

    private fun hasAudioPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, REQ_AUDIO_PERMISSIONS)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF_PERMISSION)
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "com.laguku.player.EXTRA_OPEN_NOW_PLAYING"
        const val ACTION_SHORTCUT_SHUFFLE_ALL = "com.laguku.player.action.SHORTCUT_SHUFFLE_ALL"
        const val ACTION_SHORTCUT_AMOLED_NIGHT = "com.laguku.player.action.SHORTCUT_AMOLED_NIGHT"

        private const val REQ_AUDIO_PERMISSIONS = 101
        private const val REQ_NOTIF_PERMISSION = 102
        private const val REQ_IMPORT_M3U8 = 201
        private const val REQ_EXPORT_M3U8 = 202
        private const val REQ_EXPORT_BACKUP = 203
        private const val REQ_IMPORT_BACKUP = 204
        private const val CHANNEL_AMOLED_OVERLAY = 40
    }
}
