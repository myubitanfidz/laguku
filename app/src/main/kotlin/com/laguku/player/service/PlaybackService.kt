package com.laguku.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.laguku.player.R
import com.laguku.player.audio.AudioEngine
import com.laguku.player.audio.TenBandBiquadEq
import com.laguku.player.data.LibraryScanner
import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.model.AudioQualityInfo
import com.laguku.player.model.Track
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.widget.LagukuPillWidgetProvider
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground mediaPlayback service hosting [AudioEngine], [MediaSession],
 * lock-screen/notification controls, AudioFocus management, and noisy earphone receiver.
 */
class PlaybackService : Service(), AudioEngine.Listener {

    interface RouteListener {
        fun onOutputRouteChanged(route: ActiveOutputRoute, btLatencyOffsetMs: Long)
    }

    inner class LocalBinder : Binder() {
        fun getEngine(): AudioEngine = audioEngine
        fun getService(): PlaybackService = this@PlaybackService
        fun getStateStore(): PlaybackStateStore = stateStore
        fun getBluetoothManager(): BluetoothProfileManager = bluetoothManager
        fun getClickMapper(): MultiClickMediaMapper = clickMapper
        fun getActiveOutputRoute(): ActiveOutputRoute = activeRoute
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val routeListeners = CopyOnWriteArraySet<RouteListener>()

    private lateinit var audioEngine: AudioEngine
    private lateinit var mediaSession: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var artworkExecutor: ExecutorService
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var bluetoothManager: BluetoothProfileManager
    private val clickMapper = MultiClickMediaMapper()

    @Volatile
    private var activeRoute: ActiveOutputRoute = ActiveOutputRoute(
        BluetoothProfileManager.fallbackKeyForType(DeviceRouteType.SPEAKER),
        "Speaker",
        DeviceRouteType.SPEAKER
    )

    private var eqPresetCycleIdx: Int = 0
    private var activeSessionTrack: Track? = null
    private var activeSessionPlayStartElapsedMs: Long = 0L
    private var activeSessionAccumulatedMs: Long = 0L
    private var lastPlaybackStateSyncElapsedMs: Long = 0L
    private var lastNotifiedQualityBadge: String? = null

    private val clickFlushRunnable = Runnable {
        val action = clickMapper.flushPending(SystemClock.uptimeMillis())
        if (action != null) {
            executeMediaAction(action)
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshOutputRoute(reasonAdded = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshOutputRoute(reasonAdded = false)
        }
    }

    val engine: AudioEngine
        get() = audioEngine

    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private var resumeOnFocusGain: Boolean = false
    private var isForegroundActive: Boolean = false

    @Volatile
    private var cachedArtworkPath: String? = null

    @Volatile
    private var cachedArtworkBitmap: Bitmap? = null

    @Volatile
    private var defaultCoverBitmap: Bitmap? = null

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                audioEngine.pause()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                resumeOnFocusGain = false
                audioEngine.setDuckMultiplier(1.0f)
                audioEngine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (audioEngine.isPlaying()) {
                    resumeOnFocusGain = true
                    audioEngine.pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                audioEngine.setDuckMultiplier(0.25f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                audioEngine.setDuckMultiplier(1.0f)
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    audioEngine.play()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        artworkExecutor = Executors.newSingleThreadExecutor()

        stateStore = PlaybackStateStore(File(filesDir, "laguku_state.json"))
        bluetoothManager = BluetoothProfileManager(File(filesDir, "laguku_bt_profiles.json"))

        createNotificationChannel()

        audioEngine = AudioEngine(applicationContext)
        audioEngine.updateDspConfig(stateStore.getDspConfig())
        audioEngine.addListener(this)

        mediaSession = MediaSession(this, "LagukuMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (event != null &&
                        (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                            event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK)
                    ) {
                        val now = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            val immediate = clickMapper.onKeyDown(now, event.isLongPress)
                            if (immediate != null) {
                                mainHandler.removeCallbacks(clickFlushRunnable)
                                executeMediaAction(immediate)
                            }
                            return true
                        } else if (event.action == KeyEvent.ACTION_UP) {
                            mainHandler.removeCallbacks(clickFlushRunnable)
                            val immediate = clickMapper.onKeyUp(now)
                            if (immediate != null) {
                                executeMediaAction(immediate)
                            } else {
                                mainHandler.postDelayed(clickFlushRunnable, MultiClickMediaMapper.MULTI_CLICK_WINDOW_MS)
                            }
                            return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() {
                    requestFocusAndPlay()
                }

                override fun onPause() {
                    resumeOnFocusGain = false
                    audioEngine.pause()
                }

                override fun onSkipToNext() {
                    audioEngine.next()
                }

                override fun onSkipToPrevious() {
                    audioEngine.previous()
                }

                override fun onSeekTo(pos: Long) {
                    audioEngine.seekTo(pos)
                }

                override fun onRewind() {
                    seekByMs(-10_000L)
                }

                override fun onFastForward() {
                    seekByMs(10_000L)
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    when (action) {
                        CUSTOM_ACTION_REWIND_10 -> seekByMs(-10_000L)
                        CUSTOM_ACTION_FORWARD_10 -> seekByMs(10_000L)
                    }
                }

                override fun onStop() {
                    stopPlaybackAndForeground()
                }
            })
            isActive = true
        }

        val noisyFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(this, noisyReceiver, noisyFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        refreshOutputRoute(reasonAdded = false, applyAutoPlay = false)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_PLAY_PAUSE ||
            action == ACTION_PREVIOUS ||
            action == ACTION_NEXT ||
            action == ACTION_REWIND_10 ||
            action == ACTION_FORWARD_10 ||
            action == ACTION_MORNING_ALARM_TRIGGER
        ) {
            ensureForegroundForStartCommand()
        }
        when (action) {
            ACTION_PLAY_PAUSE -> {
                if (audioEngine.isPlaying()) {
                    resumeOnFocusGain = false
                    audioEngine.pause()
                } else {
                    requestFocusAndPlay()
                }
            }
            ACTION_PREVIOUS -> audioEngine.previous()
            ACTION_NEXT -> audioEngine.next()
            ACTION_REWIND_10 -> seekByMs(-10_000L)
            ACTION_FORWARD_10 -> seekByMs(10_000L)
            ACTION_MORNING_ALARM_TRIGGER -> {
                val fadeSec = intent.getIntExtra(AlarmScheduler.EXTRA_FADE_IN_SEC, 30)
                handleMorningAlarmTrigger(fadeSec)
            }
            ACTION_STOP -> {
                ensureForegroundForStartCommand()
                stopPlaybackAndForeground()
                return START_NOT_STICKY
            }
        }
        if (action == ACTION_PLAY_PAUSE ||
            action == ACTION_PREVIOUS ||
            action == ACTION_NEXT ||
            action == ACTION_REWIND_10 ||
            action == ACTION_FORWARD_10
        ) {
            val currentTrack = audioEngine.getCurrentTrack()
            if (currentTrack == null) {
                stopForegroundCompat(removeNotification = true)
            } else if (!audioEngine.isPlaying()) {
                refreshNotification(currentTrack, false)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clickFlushRunnable)
        flushListeningSession(saveResume = true)
        saveCurrentRouteProfile()
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { unregisterReceiver(noisyReceiver) }
        abandonAudioFocus()
        audioEngine.removeListener(this)
        audioEngine.release()
        artworkExecutor.shutdownNow()
        cachedArtworkBitmap = null
        defaultCoverBitmap = null
        mediaSession.isActive = false
        mediaSession.release()
        stopForegroundCompat(removeNotification = true)
        super.onDestroy()
    }

    fun addRouteListener(listener: RouteListener) {
        routeListeners.add(listener)
        listener.onOutputRouteChanged(activeRoute, getActiveBtLatencyOffsetMs())
    }

    fun removeRouteListener(listener: RouteListener) {
        routeListeners.remove(listener)
    }

    fun getActiveBtLatencyOffsetMs(): Long {
        return bluetoothManager.getEffectiveLyricLatencyOffsetMs(activeRoute)
    }

    fun saveCurrentRouteProfile() {
        val dsp = audioEngine.getDspConfig()
        val existing = bluetoothManager.getProfileForRoute(activeRoute)
        bluetoothManager.saveProfile(
            existing.copy(
                eqEnabled = dsp.eqEnabled,
                eqBandsDb = dsp.eqBandsDb.copyOf(),
                crossfeedLevel = dsp.crossfeedLevel
            )
        )
        stateStore.setDspConfig(dsp)
    }

    // --- AudioEngine.Listener ---

    override fun onTrackChanged(track: Track?) {
        flushListeningSession(saveResume = false)
        activeSessionTrack = track
        activeSessionAccumulatedMs = 0L
        activeSessionPlayStartElapsedMs = if (audioEngine.isPlaying()) SystemClock.elapsedRealtime() else 0L
        lastKnownTrackTitle = track?.title

        val artwork = currentArtworkForTrack(track)
        val playing = audioEngine.isPlaying()
        updateMediaMetadata(track, artwork)
        loadArtworkAsync(track)
        updatePlaybackState(playing)
        refreshNotification(track, playing)
        LagukuPillWidgetProvider.updateAllWidgets(applicationContext, track, playing, artwork)
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        lastKnownPlayingState = isPlaying
        if (isPlaying) {
            if (activeSessionPlayStartElapsedMs == 0L && activeSessionTrack != null) {
                activeSessionPlayStartElapsedMs = SystemClock.elapsedRealtime()
            }
            if (!hasAudioFocus) requestAudioFocus()
        } else {
            accumulateSessionTime()
            audioEngine.getCurrentTrack()?.let { t ->
                stateStore.saveResumePosition(t, audioEngine.getCurrentPositionMs())
            }
        }
        val track = audioEngine.getCurrentTrack()
        updatePlaybackState(isPlaying)
        refreshNotification(track, isPlaying)
        LagukuPillWidgetProvider.updateAllWidgets(
            applicationContext,
            track,
            isPlaying,
            currentArtworkForTrack(track)
        )
    }

    override fun onPositionUpdated(positionMs: Long, durationMs: Long) {
        // Throttle MediaSession Binder IPC position sync to once every 5s while playing
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlaybackStateSyncElapsedMs >= 5_000L) {
            lastPlaybackStateSyncElapsedMs = now
            updatePlaybackState(audioEngine.isPlaying(), positionMs)
        }
    }

    override fun onQualityChanged(quality: AudioQualityInfo) {
        val badge = quality.formatBadge()
        if (badge == lastNotifiedQualityBadge) return
        lastNotifiedQualityBadge = badge
        val track = audioEngine.getCurrentTrack()?.copy(quality = quality)
        LagukuPillWidgetProvider.updateAllWidgets(
            applicationContext,
            track,
            audioEngine.isPlaying(),
            currentArtworkForTrack(track)
        )
    }

    override fun onQueueCompleted() {
        abandonAudioFocus()
        updatePlaybackState(isPlaying = false, positionMs = 0L)
        stopForegroundCompat(removeNotification = true)
        LagukuPillWidgetProvider.updateAllWidgets(applicationContext, null, false, null)
    }

    private fun requestFocusAndPlay() {
        if (requestAudioFocus()) {
            audioEngine.play()
        }
    }

    private fun requestAudioFocus(): Boolean {
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
            .also { audioFocusRequest = it }

        val result = audioManager.requestAudioFocus(request)
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { req ->
            runCatching { audioManager.abandonAudioFocusRequest(req) }
        }
        hasAudioFocus = false
        resumeOnFocusGain = false
    }

    private fun currentArtworkForTrack(track: Track?): Bitmap? {
        if (track == null || track.path.isBlank()) return null
        return cachedArtworkBitmap?.takeIf { !it.isRecycled && track.path == cachedArtworkPath }
    }

    private fun loadArtworkAsync(track: Track?) {
        if (track == null || track.path.isBlank()) {
            cachedArtworkPath = null
            cachedArtworkBitmap = null
        defaultCoverBitmap = null
            return
        }
        val path = track.path
        if (path == cachedArtworkPath && cachedArtworkBitmap != null) {
            return
        }
        artworkExecutor.execute {
            val bytes = runCatching { BinaryTagReader.read(File(path)).artworkBytes }.getOrNull()
            val bmp = bytes?.let { decodeScaledBitmap(it, MAX_ARTWORK_DIM_PX) }
            if (audioEngine.getCurrentTrack()?.path == path) {
                cachedArtworkPath = path
                cachedArtworkBitmap = bmp
                val playing = audioEngine.isPlaying()
                updateMediaMetadata(track, bmp)
                refreshNotification(track, playing)
                LagukuPillWidgetProvider.updateAllWidgets(applicationContext, track, playing, bmp)
            } else {
                bmp?.recycle()
            }
        }
    }

    private fun getOrCreateDefaultCover(): Bitmap? {
        defaultCoverBitmap?.takeIf { !it.isRecycled }?.let { return it }
        return CoverPaletteExtractor.renderDefaultCoverBitmap(this, 256)?.also {
            defaultCoverBitmap = it
        }
    }

    private fun updateMediaMetadata(track: Track?, artwork: Bitmap?) {
        if (track == null) {
            mediaSession.setMetadata(null)
            return
        }
        val duration = audioEngine.getDurationMs().takeIf { it > 0L } ?: track.durationMs
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, track.trackNumber.toLong().coerceAtLeast(0L))
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        val displayArt = artwork?.takeIf { !it.isRecycled } ?: getOrCreateDefaultCover()
        if (displayArt != null && !displayArt.isRecycled) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, displayArt)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, displayArt)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun refreshOutputRoute(reasonAdded: Boolean, applyAutoPlay: Boolean = true) {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val newRoute = bluetoothManager.resolveOutputRoute(devices)
        val oldRoute = activeRoute
        val routeChanged = newRoute != oldRoute
        if (routeChanged) {
            saveCurrentRouteProfile()
            activeRoute = newRoute
            val profile = bluetoothManager.getProfileForRoute(newRoute)
            val btSettings = bluetoothManager.getSettings()
            val nextDsp = audioEngine.getDspConfig().copy(
                eqEnabled = profile.eqEnabled,
                eqBandsDb = profile.eqBandsDb.copyOf(),
                crossfeedLevel = profile.crossfeedLevel,
                btPreRollEnabled = btSettings.btPreRollEnabled
            )
            audioEngine.updateDspConfig(nextDsp)
            if (newRoute.type == DeviceRouteType.BLUETOOTH) {
                audioEngine.triggerBluetoothPreRoll()
                if (profile.safeStartVolumePercent in 0..100) {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val safeVol = (maxVol * profile.safeStartVolumePercent / 100f).toInt().coerceIn(0, maxVol)
                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (curVol > safeVol) {
                        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, safeVol, 0) }
                    }
                }
                if (applyAutoPlay && reasonAdded && btSettings.autoPlayOnConnect && !audioEngine.isPlaying()) {
                    if (audioEngine.getCurrentTrack() != null || audioEngine.getQueue().isNotEmpty()) {
                        requestFocusAndPlay()
                    }
                }
            } else if (oldRoute.type == DeviceRouteType.BLUETOOTH &&
                btSettings.autoPauseOnDisconnect &&
                audioEngine.isPlaying()
            ) {
                audioEngine.pause()
            }
        }
        val offset = getActiveBtLatencyOffsetMs()
        for (listener in routeListeners) {
            listener.onOutputRouteChanged(activeRoute, offset)
        }
    }

    private fun seekByMs(deltaMs: Long) {
        val dur = audioEngine.getDurationMs().coerceAtLeast(0L)
        val cur = audioEngine.getCurrentPositionMs()
        val target = if (dur > 0L) (cur + deltaMs).coerceIn(0L, dur) else (cur + deltaMs).coerceAtLeast(0L)
        audioEngine.seekTo(target)
        updatePlaybackState(audioEngine.isPlaying(), target)
    }

    private fun executeMediaAction(action: MediaAction) {
        when (action) {
            MediaAction.PLAY_PAUSE -> {
                if (audioEngine.isPlaying()) {
                    resumeOnFocusGain = false
                    audioEngine.pause()
                } else {
                    requestFocusAndPlay()
                }
            }
            MediaAction.NEXT -> audioEngine.next()
            MediaAction.PREVIOUS -> audioEngine.previous()
            MediaAction.SEEK_FORWARD_10S -> audioEngine.seekTo(audioEngine.getCurrentPositionMs() + 10_000L)
            MediaAction.SEEK_BACKWARD_10S -> audioEngine.seekTo((audioEngine.getCurrentPositionMs() - 10_000L).coerceAtLeast(0L))
            MediaAction.CYCLE_EQ_PRESET -> {
                val names = TenBandBiquadEq.PRESET_NAMES
                eqPresetCycleIdx = (eqPresetCycleIdx + 1) % names.size
                audioEngine.setTenBandEqEnabled(true)
                audioEngine.applyEqPreset(names[eqPresetCycleIdx])
                saveCurrentRouteProfile()
            }
            MediaAction.NONE -> Unit
        }
    }

    private fun handleMorningAlarmTrigger(fadeSec: Int) {
        artworkExecutor.execute {
            if (audioEngine.getQueue().isEmpty()) {
                val scanned = LibraryScanner.scanMediaStore(applicationContext)
                if (scanned.isNotEmpty()) {
                    mainHandler.post {
                        audioEngine.setQueue(scanned, 0)
                        audioEngine.startVolumeRampIn(fadeSec * 1000L)
                        requestFocusAndPlay()
                    }
                }
            } else {
                mainHandler.post {
                    audioEngine.startVolumeRampIn(fadeSec * 1000L)
                    requestFocusAndPlay()
                }
            }
        }
        val alarmCfg = stateStore.getMorningAlarm()
        if (alarmCfg.enabled) {
            AlarmScheduler.schedule(applicationContext, alarmCfg)
        }
    }

    private fun accumulateSessionTime() {
        if (activeSessionPlayStartElapsedMs > 0L) {
            activeSessionAccumulatedMs += (SystemClock.elapsedRealtime() - activeSessionPlayStartElapsedMs).coerceAtLeast(0L)
            activeSessionPlayStartElapsedMs = 0L
        }
    }

    private fun flushListeningSession(saveResume: Boolean) {
        accumulateSessionTime()
        val track = activeSessionTrack
        if (track != null) {
            if (activeSessionAccumulatedMs >= PlaybackStateStore.MIN_INSIGHT_SESSION_MS) {
                stateStore.recordPlaySession(track, activeSessionAccumulatedMs)
            }
            if (saveResume) {
                stateStore.saveResumePosition(track, audioEngine.getCurrentPositionMs())
            }
        }
        activeSessionAccumulatedMs = 0L
    }

    private fun updatePlaybackState(
        isPlaying: Boolean,
        positionMs: Long = audioEngine.getCurrentPositionMs()
    ) {
        val state = if (isPlaying) {
            PlaybackState.STATE_PLAYING
        } else if (audioEngine.getCurrentTrack() != null) {
            PlaybackState.STATE_PAUSED
        } else {
            PlaybackState.STATE_STOPPED
        }
        val speed = if (isPlaying) 1.0f else 0.0f
        val playbackState = PlaybackState.Builder()
            .setActions(SUPPORTED_SESSION_ACTIONS)
            .setState(state, positionMs, speed)
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_ACTION_REWIND_10,
                    "Mundur 10d",
                    R.drawable.ic_notif_rewind_10
                ).build()
            )
            .addCustomAction(
                PlaybackState.CustomAction.Builder(
                    CUSTOM_ACTION_FORWARD_10,
                    "Maju 10d",
                    R.drawable.ic_notif_forward_10
                ).build()
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun refreshNotification(track: Track?, isPlaying: Boolean) {
        if (track == null) {
            stopForegroundCompat(removeNotification = true)
            return
        }

        val notification = buildNotification(track, isPlaying)
        if (isPlaying) {
            if (!isForegroundActive) {
                startForegroundCompat(notification)
            } else {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            if (isForegroundActive) {
                stopForegroundCompat(removeNotification = false)
            }
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(track: Track, isPlaying: Boolean): Notification {
        val rewind10Action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_rewind_10),
            "Mundur 10 detik",
            createServiceActionIntent(ACTION_REWIND_10, 4)
        ).build()

        val prevAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_prev),
            "Sebelumnya",
            createServiceActionIntent(ACTION_PREVIOUS, 1)
        ).build()

        val playPauseIcon = if (isPlaying) {
            R.drawable.ic_notif_pause
        } else {
            R.drawable.ic_notif_play
        }
        val playPauseLabel = if (isPlaying) "Jeda" else "Putar"
        val playPauseAction = Notification.Action.Builder(
            Icon.createWithResource(this, playPauseIcon),
            playPauseLabel,
            createServiceActionIntent(ACTION_PLAY_PAUSE, 2)
        ).build()

        val nextAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_next),
            "Berikutnya",
            createServiceActionIntent(ACTION_NEXT, 3)
        ).build()

        val forward10Action = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notif_forward_10),
            "Maju 10 detik",
            createServiceActionIntent(ACTION_FORWARD_10, 5)
        ).build()

        val contentIntent = createMainActivityPendingIntent()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setSubText(track.album)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(rewind10Action)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .addAction(forward10Action)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )

        val largeIcon = cachedArtworkBitmap?.takeIf { !it.isRecycled } ?: getOrCreateDefaultCover()
        largeIcon?.takeIf { !it.isRecycled }?.let { bmp ->
            builder.setLargeIcon(bmp)
        }

        return builder.build()
    }

    private fun createServiceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureForegroundForStartCommand() {
        if (isForegroundActive) return
        val track = audioEngine.getCurrentTrack()
        val notification = if (track != null) {
            buildNotification(track, audioEngine.isPlaying())
        } else {
            buildIdleForegroundNotification()
        }
        startForegroundCompat(notification)
    }

    private fun createMainActivityPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(this, "com.laguku.player.MainActivity").apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildIdleForegroundNotification(): Notification {
        val contentIntent = createMainActivityPendingIntent()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.widget_idle_subtitle))
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundActive = true
    }

    private fun stopPlaybackAndForeground() {
        abandonAudioFocus()
        audioEngine.pause()
        updatePlaybackState(isPlaying = false)
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        val flag = if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
        stopForeground(flag)
        isForegroundActive = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media playback controls"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "laguku_playback"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "com.laguku.player.action.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.laguku.player.action.PREVIOUS"
        const val ACTION_NEXT = "com.laguku.player.action.NEXT"
        const val ACTION_REWIND_10 = "com.laguku.player.action.REWIND_10"
        const val ACTION_FORWARD_10 = "com.laguku.player.action.FORWARD_10"
        const val ACTION_STOP = "com.laguku.player.action.STOP"
        const val ACTION_MORNING_ALARM_TRIGGER = "com.laguku.player.action.MORNING_ALARM_TRIGGER"

        const val CUSTOM_ACTION_REWIND_10 = "com.laguku.player.session.REWIND_10"
        const val CUSTOM_ACTION_FORWARD_10 = "com.laguku.player.session.FORWARD_10"

        @Volatile
        var lastKnownTrackTitle: String? = null
            private set

        @Volatile
        var lastKnownPlayingState: Boolean = false
            private set

        private const val MAX_ARTWORK_DIM_PX = 512
        private const val SUPPORTED_SESSION_ACTIONS =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_REWIND or
                PlaybackState.ACTION_FAST_FORWARD or
                PlaybackState.ACTION_STOP

        private fun decodeScaledBitmap(bytes: ByteArray, maxDim: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null

            var sampleSize = 1
            while (width / sampleSize > maxDim * 2 || height / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
    }
}
