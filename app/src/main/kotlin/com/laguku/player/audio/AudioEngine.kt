package com.laguku.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.laguku.player.model.AudioDspConfig
import com.laguku.player.model.AudioQualityInfo
import com.laguku.player.model.LosslessAuditStatus
import com.laguku.player.model.RepeatMode
import com.laguku.player.model.Track
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Universal audio playback engine powered by MediaExtractor + MediaCodec + AudioTrack
 * with gapless pre-buffering, ReplayGain normalization, 5-band Equalizer, Sleep Timer,
 * and automatic fallback to android.media.MediaPlayer for vendor-specific formats.
 */
class AudioEngine(private val context: Context) {

    interface Listener {
        fun onTrackChanged(track: Track?)
        fun onPlaybackStateChanged(isPlaying: Boolean)
        fun onPositionUpdated(positionMs: Long, durationMs: Long)
        fun onQualityChanged(quality: AudioQualityInfo)
        fun onQueueCompleted()
        fun onLosslessAuditChanged(status: LosslessAuditStatus) {}
        fun onVisualizerLevel(level: Float) {}
        fun onQueueChanged(queue: List<Track>) {}
        fun onAbLoopChanged(startMs: Long?, endMs: Long?) {}
    }

    private data class DecodedPcmChunk(
        val data: ByteArray,
        val size: Int,
        val presentationTimeUs: Long
    )

    private class DecoderSession(
        val track: Track,
        val extractor: MediaExtractor,
        val codec: MediaCodec,
        var quality: AudioQualityInfo,
        var sampleRate: Int,
        var channelCount: Int,
        var pcmEncoding: Int,
        var durationUs: Long
    ) {
        var inputEos: Boolean = false
        var outputEos: Boolean = false
        val preDecodedChunks: ArrayDeque<DecodedPcmChunk> = ArrayDeque()

        fun matchesAudioFormat(otherSampleRate: Int, otherChannels: Int, otherEncoding: Int): Boolean {
            return sampleRate == otherSampleRate &&
                channelCount == otherChannels &&
                pcmEncoding == otherEncoding
        }

        fun prebuffer(maxChunks: Int = 6) {
            val bufferInfo = MediaCodec.BufferInfo()
            var iterations = 0
            while (preDecodedChunks.size < maxChunks && !outputEos && iterations < 48) {
                iterations++
                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(2_000L)
                    if (inIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inIdx)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEos = true
                            } else {
                                val pts = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 2_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        updateFromOutputFormat(codec.outputFormat)
                    }
                    outIdx >= 0 -> {
                        if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                outBuf.get(bytes)
                                preDecodedChunks.addLast(
                                    DecodedPcmChunk(bytes, bufferInfo.size, bufferInfo.presentationTimeUs)
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true
                        }
                    }
                }
            }
        }

        fun updateFromOutputFormat(outFormat: MediaFormat) {
            if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
            if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            }
            val updatedBitDepth = resolveBitDepth(outFormat, pcmEncoding, quality.bitDepth)
            quality = quality.copy(bitDepth = updatedBitDepth, sampleRateHz = sampleRate)
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
            preDecodedChunks.clear()
        }
    }

    private sealed class EngineCommand {
        data class LoadTrack(val track: Track, val startPlaying: Boolean, val generation: Int, val fadeOutFirst: Boolean = false) : EngineCommand()
        data class Seek(val positionMs: Long, val generation: Int) : EngineCommand()
        data object Play : EngineCommand()
        data object Pause : EngineCommand()
        data object Stop : EngineCommand()
        data object Release : EngineCommand()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val stateLock = Any()
    private val dspChain = AudioDspChain()

    @Volatile
    private var dspConfig: AudioDspConfig = AudioDspConfig()

    @Volatile
    private var currentAuditStatus: LosslessAuditStatus = LosslessAuditStatus.NOT_APPLICABLE

    @Volatile
    private var abLoopStartMs: Long? = null

    @Volatile
    private var abLoopEndMs: Long? = null

    private var lastAbLoopSeekElapsedMs: Long = 0L
    private var lastVisualizerDispatchMs: Long = 0L

    @Volatile
    private var uiPositionUpdatesEnabled: Boolean = true

    @Volatile
    private var pendingVisualizerLevel: Float = 0f

    private val visualizerDispatchPending = AtomicBoolean(false)

    private val visualizerDispatchRunnable = Runnable {
        visualizerDispatchPending.set(false)
        if (released.get()) return@Runnable
        val level = pendingVisualizerLevel
        for (listener in listeners) {
            listener.onVisualizerLevel(level)
        }
    }

    internal interface WakeLockAdapter {
        fun acquire(timeoutMs: Long)
        fun release()
    }

    internal class WakeLockLeaseManager(
        private val adapter: WakeLockAdapter,
        private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
        private val timeoutMs: Long = WAKE_LOCK_TIMEOUT_MS,
        private val renewIntervalMs: Long = WAKE_LOCK_RENEW_INTERVAL_MS
    ) {
        @Volatile
        var isHeld: Boolean = false
            private set

        @Volatile
        private var lastAcquireElapsedMs: Long = 0L

        fun acquireOrRenewNow() {
            adapter.acquire(timeoutMs)
            isHeld = true
            lastAcquireElapsedMs = clockMs()
        }

        fun renewIfDue(isPlaying: Boolean) {
            if (!isPlaying) return
            val now = clockMs()
            if (!isHeld || (now - lastAcquireElapsedMs) >= renewIntervalMs) {
                adapter.acquire(timeoutMs)
                isHeld = true
                lastAcquireElapsedMs = now
            }
        }

        fun releaseNow() {
            if (isHeld) {
                adapter.release()
                isHeld = false
                lastAcquireElapsedMs = 0L
            }
        }
    }

    private val wakeLock: PowerManager.WakeLock? = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Laguku:AudioPlayback")?.apply {
            setReferenceCounted(false)
        }
    }.getOrNull()

    private val wakeLockLease = WakeLockLeaseManager(
        adapter = object : WakeLockAdapter {
            override fun acquire(timeoutMs: Long) {
                wakeLock?.let { wl -> runCatching { wl.acquire(timeoutMs) } }
            }

            override fun release() {
                wakeLock?.let { wl -> runCatching { if (wl.isHeld) wl.release() } }
            }
        }
    )

    private var originalQueue: List<Track> = emptyList()
    private var playOrder: List<Int> = emptyList()
    private var orderIndex: Int = -1
    private var shuffleEnabled: Boolean = false
    private var repeatMode: RepeatMode = RepeatMode.OFF

    @Volatile
    private var currentTrack: Track? = null

    @Volatile
    private var currentQuality: AudioQualityInfo = AudioQualityInfo()

    @Volatile
    private var playing: Boolean = false

    @Volatile
    private var currentPositionMs: Long = 0L

    @Volatile
    private var currentDurationMs: Long = 0L

    @Volatile
    private var sleepFadeMultiplier: Float = 1.0f

    @Volatile
    private var duckMultiplier: Float = 1.0f

    private val released = AtomicBoolean(false)
    private val loadGeneration = AtomicInteger(0)

    private val commandLock = Object()
    private val pendingCommands = ArrayDeque<EngineCommand>()

    private var audioSessionId: Int = generateInitialSessionId()

    // Equalizer state preserved across AudioTrack recreations
    private var equalizer: Equalizer? = null
    private var equalizerEnabled: Boolean = false
    private var bandLevels: ShortArray? = null

    // Sleep timer state
    private var sleepEndElapsedMs: Long = 0L
    private var sleepTickerRunnable: Runnable? = null

    // Active playback resources (owned exclusively by playbackThread)
    private var activeSession: DecoderSession? = null
    private var prebufferedNextSession: DecoderSession? = null
    private var prebufferedNextOrderIndex: Int = -1
    private var audioTrack: AudioTrack? = null
    private var fallbackPlayer: MediaPlayer? = null
    private var fallbackPrepared: Boolean = false
    private var usingFallback: Boolean = false

    // Position tracking anchors
    private var trackAnchorMs: Long = 0L
    private var headAnchorFrames: Long = 0L
    private var totalFramesWrittenToCurrentTrack: Long = 0L
    private var activeSampleRate: Int = 44100
    private var activeChannels: Int = 2
    private var activeEncoding: Int = AudioFormat.ENCODING_PCM_16BIT

    // Reusable PCM scratch buffers (zero per-frame allocations on playback thread)
    private var pcmByteBuffer = ByteArray(16384)
    private var pcmFloatBuffer = FloatArray(4096)
    private var preRollFloatBuffer = FloatArray(1024)
    private var crossfadeMixBuffer = FloatArray(4096)
    private var crossfadeActive: Boolean = false
    private var crossfadeFramesElapsed: Int = 0
    private var crossfadeTotalFrames: Int = 0

    private val positionTicker = object : Runnable {
        override fun run() {
            if (released.get() || !uiPositionUpdatesEnabled) return
            updatePositionSnapshot()
            val pos = currentPositionMs
            val dur = currentDurationMs
            for (listener in listeners) {
                listener.onPositionUpdated(pos, dur)
            }
            if (playing && uiPositionUpdatesEnabled) {
                mainHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    fun setUiPositionUpdatesEnabled(enabled: Boolean) {
        uiPositionUpdatesEnabled = enabled
        mainHandler.post {
            if (released.get()) return@post
            mainHandler.removeCallbacks(positionTicker)
            if (enabled && playing) {
                mainHandler.post(positionTicker)
            }
        }
    }

    private val playbackThread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        runPlaybackLoop()
    }, "LagukuAudioThread").apply {
        start()
    }

    private fun generateInitialSessionId(): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val id = runCatching { audioManager?.generateAudioSessionId() ?: AudioManager.ERROR }.getOrDefault(AudioManager.ERROR)
        return if (id == AudioManager.ERROR || id == 0) 0 else id
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun setListener(listener: Listener?) {
        listeners.clear()
        if (listener != null) {
            listeners.add(listener)
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (released.get()) return
        val selectedTrack: Track?
        synchronized(stateLock) {
            originalQueue = tracks.toList()
            if (originalQueue.isEmpty()) {
                playOrder = emptyList()
                orderIndex = -1
                selectedTrack = null
            } else {
                val clampedStart = startIndex.coerceIn(0, originalQueue.lastIndex)
                if (shuffleEnabled) {
                    val rest = originalQueue.indices.filter { it != clampedStart }.shuffled()
                    playOrder = listOf(clampedStart) + rest
                    orderIndex = 0
                } else {
                    playOrder = originalQueue.indices.toList()
                    orderIndex = clampedStart
                }
                selectedTrack = originalQueue[playOrder[orderIndex]]
            }
        }

        if (selectedTrack == null) {
            enqueueCommand(EngineCommand.Stop)
            updateTrackState(null, AudioQualityInfo(), 0L)
            setPlayingState(false)
            notifyQueueCompleted()
        } else {
            loadAndStart(selectedTrack, startPlaying = true, fadeOutFirst = false)
        }
        notifyQueueChanged()
    }

    fun playTrack(index: Int) {
        if (released.get()) return
        val trackToPlay: Track?
        synchronized(stateLock) {
            if (index !in originalQueue.indices) return
            val existingOrderPos = playOrder.indexOf(index)
            if (existingOrderPos >= 0) {
                orderIndex = existingOrderPos
            } else {
                playOrder = originalQueue.indices.toList()
                orderIndex = index
            }
            trackToPlay = originalQueue[playOrder[orderIndex]]
        }
        if (trackToPlay != null) {
            loadAndStart(trackToPlay, startPlaying = true, fadeOutFirst = playing)
        }
    }

    fun getQueue(): List<Track> = synchronized(stateLock) {
        playOrder.mapNotNull { originalQueue.getOrNull(it) }
    }

    fun getCurrentQueueIndex(): Int = synchronized(stateLock) { orderIndex }

    fun playNext(track: Track) {
        if (released.get()) return
        var shouldStart = false
        synchronized(stateLock) {
            if (originalQueue.isEmpty()) {
                originalQueue = listOf(track)
                playOrder = listOf(0)
                orderIndex = 0
                shouldStart = true
            } else {
                val newOrigIdx = originalQueue.size
                originalQueue = originalQueue + track
                val mutableOrder = playOrder.toMutableList()
                val insertPos = (orderIndex + 1).coerceIn(0, mutableOrder.size)
                mutableOrder.add(insertPos, newOrigIdx)
                playOrder = mutableOrder
            }
        }
        if (shouldStart) {
            loadAndStart(track, startPlaying = true, fadeOutFirst = false)
        }
        notifyQueueChanged()
    }

    fun enqueue(track: Track) {
        if (released.get()) return
        var shouldStart = false
        synchronized(stateLock) {
            if (originalQueue.isEmpty()) {
                originalQueue = listOf(track)
                playOrder = listOf(0)
                orderIndex = 0
                shouldStart = true
            } else {
                val newOrigIdx = originalQueue.size
                originalQueue = originalQueue + track
                playOrder = playOrder + newOrigIdx
            }
        }
        if (shouldStart) {
            loadAndStart(track, startPlaying = true, fadeOutFirst = false)
        }
        notifyQueueChanged()
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        synchronized(stateLock) {
            if (fromIndex !in playOrder.indices || toIndex !in playOrder.indices || fromIndex == toIndex) return
            val mutable = playOrder.toMutableList()
            val item = mutable.removeAt(fromIndex)
            mutable.add(toIndex, item)
            playOrder = mutable
            when {
                orderIndex == fromIndex -> orderIndex = toIndex
                fromIndex < orderIndex && toIndex >= orderIndex -> orderIndex--
                fromIndex > orderIndex && toIndex <= orderIndex -> orderIndex++
            }
        }
        notifyQueueChanged()
    }

    fun removeQueueItem(index: Int) {
        var needStop = false
        var nextToLoad: Track? = null
        synchronized(stateLock) {
            if (index !in playOrder.indices) return
            val mutable = playOrder.toMutableList()
            mutable.removeAt(index)
            playOrder = mutable
            if (mutable.isEmpty()) {
                originalQueue = emptyList()
                orderIndex = -1
                needStop = true
            } else if (index < orderIndex) {
                orderIndex--
            } else if (index == orderIndex) {
                if (orderIndex >= mutable.size) orderIndex = mutable.lastIndex
                nextToLoad = originalQueue.getOrNull(mutable[orderIndex])
            }
        }
        if (needStop) {
            enqueueCommand(EngineCommand.Stop)
            updateTrackState(null, AudioQualityInfo(), 0L)
            setPlayingState(false)
            notifyQueueCompleted()
        } else if (nextToLoad != null) {
            loadAndStart(nextToLoad!!, startPlaying = playing, fadeOutFirst = false)
        }
        notifyQueueChanged()
    }

    fun play() {
        if (released.get()) return
        val track = currentTrack
        if (track == null) {
            synchronized(stateLock) {
                if (playOrder.isNotEmpty()) {
                    if (orderIndex !in playOrder.indices) orderIndex = 0
                    val first = originalQueue[playOrder[orderIndex]]
                    loadAndStart(first, startPlaying = true, fadeOutFirst = false)
                }
            }
            return
        }
        dspChain.triggerFadeIn(AudioDspChain.DEFAULT_FADE_IN_MS)
        setPlayingState(true)
        enqueueCommand(EngineCommand.Play)
    }

    fun pause() {
        if (released.get()) return
        setPlayingState(false)
        enqueueCommand(EngineCommand.Pause)
    }

    fun togglePlayPause() {
        if (playing) pause() else play()
    }

    fun next() {
        advanceToNext(fromCompletion = false)
    }

    fun previous() {
        if (released.get()) return
        if (getCurrentPositionMs() > PREVIOUS_RESTART_THRESHOLD_MS) {
            seekTo(0L)
            return
        }
        val prevTrack: Track?
        synchronized(stateLock) {
            if (playOrder.isEmpty()) return
            if (orderIndex > 0) {
                orderIndex--
                prevTrack = originalQueue[playOrder[orderIndex]]
            } else if (repeatMode == RepeatMode.ALL) {
                orderIndex = playOrder.lastIndex
                prevTrack = originalQueue[playOrder[orderIndex]]
            } else {
                prevTrack = null
            }
        }
        if (prevTrack != null) {
            loadAndStart(prevTrack, startPlaying = true, fadeOutFirst = playing)
        } else {
            seekTo(0L)
        }
    }

    fun seekTo(positionMs: Long) {
        if (released.get()) return
        val dur = currentDurationMs
        val target = if (dur > 0L) positionMs.coerceIn(0L, dur) else positionMs.coerceAtLeast(0L)
        currentPositionMs = target
        val gen = loadGeneration.get()
        enqueueCommand(EngineCommand.Seek(target, gen))
        notifyPosition(target, dur)
    }

    fun setShuffle(enabled: Boolean) {
        synchronized(stateLock) {
            if (shuffleEnabled == enabled) return
            shuffleEnabled = enabled
            if (originalQueue.isEmpty()) return
            val currentOrigIdx = if (orderIndex in playOrder.indices) playOrder[orderIndex] else 0
            if (enabled) {
                val others = originalQueue.indices.filter { it != currentOrigIdx }.shuffled()
                playOrder = listOf(currentOrigIdx) + others
                orderIndex = 0
            } else {
                playOrder = originalQueue.indices.toList()
                orderIndex = currentOrigIdx
            }
        }
    }

    fun isShuffleEnabled(): Boolean = synchronized(stateLock) { shuffleEnabled }

    fun cycleRepeatMode(): RepeatMode {
        val nextMode = synchronized(stateLock) {
            repeatMode = when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            repeatMode
        }
        return nextMode
    }

    fun getRepeatMode(): RepeatMode = synchronized(stateLock) { repeatMode }

    fun getCurrentTrack(): Track? = currentTrack

    fun isPlaying(): Boolean = playing

    fun getCurrentPositionMs(): Long {
        updatePositionSnapshot()
        return currentPositionMs
    }

    fun getDurationMs(): Long = currentDurationMs

    fun getAudioSessionId(): Int = audioSessionId

    fun getCurrentQuality(): AudioQualityInfo = currentQuality

    fun getLosslessAuditStatus(): LosslessAuditStatus = currentAuditStatus

    // --- 10-Band DSP, Speed/Pitch, AB-Loop, BT Pre-Roll, & Visualizer ---

    fun getDspConfig(): AudioDspConfig = dspConfig

    fun updateDspConfig(config: AudioDspConfig) {
        dspConfig = config
        dspChain.applyConfig(config)
        equalizerEnabled = config.eqEnabled
        syncFallbackSystemEq()
        applyPlaybackParamsInternal(config.speed, config.pitch)
    }

    fun setTenBandEqEnabled(enabled: Boolean) {
        updateDspConfig(dspConfig.copy(eqEnabled = enabled))
    }

    fun isTenBandEqEnabled(): Boolean = dspConfig.eqEnabled

    fun setTenBandLevelDb(band: Int, gainDb: Float) {
        if (band !in 0 until TenBandBiquadEq.BAND_COUNT) return
        val next = dspConfig.eqBandsDb.copyOf()
        next[band] = gainDb.coerceIn(TenBandBiquadEq.MIN_GAIN_DB, TenBandBiquadEq.MAX_GAIN_DB)
        updateDspConfig(dspConfig.copy(eqBandsDb = next))
    }

    fun setAllTenBandLevelsDb(gainsDb: FloatArray) {
        val next = FloatArray(TenBandBiquadEq.BAND_COUNT)
        for (i in next.indices) {
            next[i] = if (i < gainsDb.size) gainsDb[i].coerceIn(TenBandBiquadEq.MIN_GAIN_DB, TenBandBiquadEq.MAX_GAIN_DB) else 0f
        }
        updateDspConfig(dspConfig.copy(eqBandsDb = next))
    }

    fun applyEqPreset(presetName: String): Boolean {
        val preset = TenBandBiquadEq.PRESETS[presetName] ?: return false
        setAllTenBandLevelsDb(preset)
        return true
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        val s = speed.coerceIn(0.5f, 2.0f)
        val p = pitch.coerceIn(0.5f, 2.0f)
        dspConfig = dspConfig.copy(speed = s, pitch = p)
        applyPlaybackParamsInternal(s, p)
    }

    fun setAbLoop(startMs: Long?, endMs: Long?) {
        val dur = currentDurationMs
        val a = startMs?.coerceAtLeast(0L)?.let { if (dur > 0L) it.coerceAtMost(dur) else it }
        val b = endMs?.coerceAtLeast(0L)?.let { if (dur > 0L) it.coerceAtMost(dur) else it }
        abLoopStartMs = a
        abLoopEndMs = if (a != null && b != null && b > a + 200L) b else null
        notifyAbLoopChanged(abLoopStartMs, abLoopEndMs)
    }

    fun clearAbLoop() {
        if (abLoopStartMs != null || abLoopEndMs != null) {
            abLoopStartMs = null
            abLoopEndMs = null
            notifyAbLoopChanged(null, null)
        }
    }

    fun getAbLoop(): Pair<Long?, Long?> = abLoopStartMs to abLoopEndMs

    fun triggerBluetoothPreRoll() {
        if (dspConfig.btPreRollEnabled) {
            dspChain.triggerBluetoothPreRoll(AudioDspChain.DEFAULT_BT_PREROLL_MS)
        }
    }

    fun setVisualizerActive(active: Boolean) {
        dspChain.visualizerEnabled = active
        if (!active) {
            notifyVisualizerLevel(0f)
        }
    }

    fun startVolumeRampIn(durationMs: Long) {
        dspChain.triggerVolumeRamp(durationMs.coerceIn(500L, 120_000L).toInt())
    }

    internal fun setDuckMultiplier(multiplier: Float) {
        duckMultiplier = multiplier.coerceIn(0f, 1f)
        applyActiveVolume()
    }

    // --- Equalizer 5-band ---

    fun initEqualizer() {
        if (released.get()) return
        if (audioSessionId == 0) {
            audioSessionId = generateInitialSessionId()
        }
        if (audioSessionId == 0) return
        runCatching {
            equalizer?.release()
            val eq = Equalizer(0, audioSessionId)
            val bands = eq.numberOfBands.toInt().coerceAtLeast(0)
            val saved = bandLevels
            if (saved != null && saved.size == bands) {
                for (i in 0 until bands) {
                    eq.setBandLevel(i.toShort(), saved[i])
                }
            } else {
                bandLevels = ShortArray(bands) { i -> eq.getBandLevel(i.toShort()) }
            }
            eq.enabled = equalizerEnabled
            equalizer = eq
        }
    }

    fun getBandCount(): Int {
        return runCatching { equalizer?.numberOfBands?.toInt() ?: 0 }.getOrDefault(0)
    }

    fun getBandFreqRange(band: Short): IntArray {
        val eq = equalizer ?: return intArrayOf(0, 0)
        return runCatching {
            if (band in 0 until eq.numberOfBands) eq.getBandFreqRange(band) else intArrayOf(0, 0)
        }.getOrDefault(intArrayOf(0, 0))
    }

    fun getBandLevelRange(): ShortArray {
        val eq = equalizer ?: return shortArrayOf(-1500, 1500)
        return runCatching { eq.bandLevelRange }.getOrDefault(shortArrayOf(-1500, 1500))
    }

    fun getBandLevel(band: Short): Short {
        val eq = equalizer ?: return bandLevels?.getOrNull(band.toInt()) ?: 0
        return runCatching {
            if (band in 0 until eq.numberOfBands) eq.getBandLevel(band) else 0
        }.getOrDefault(0)
    }

    fun setBandLevel(band: Short, level: Short) {
        val eq = equalizer ?: return
        runCatching {
            val count = eq.numberOfBands.toInt()
            if (band in 0 until count) {
                val range = eq.bandLevelRange
                val clamped = level.coerceIn(range[0], range[1])
                eq.setBandLevel(band, clamped)
                val levels = bandLevels ?: ShortArray(count).also { bandLevels = it }
                if (band.toInt() < levels.size) {
                    levels[band.toInt()] = clamped
                }
            }
        }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        equalizerEnabled = enabled
        if (enabled && equalizer == null) {
            initEqualizer()
        }
        runCatching { equalizer?.enabled = enabled }
    }

    fun isEqualizerEnabled(): Boolean = equalizerEnabled

    // --- Sleep timer ---

    fun startSleepTimer(durationMs: Long) {
        cancelSleepTimer()
        if (released.get()) return
        if (durationMs <= 0L) {
            pause()
            return
        }
        sleepEndElapsedMs = SystemClock.elapsedRealtime() + durationMs
        val ticker = object : Runnable {
            override fun run() {
                if (released.get()) return
                val remaining = sleepEndElapsedMs - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    sleepTickerRunnable = null
                    pause()
                    sleepFadeMultiplier = 1.0f
                    applyActiveVolume()
                } else {
                    if (remaining <= SLEEP_FADE_WINDOW_MS) {
                        sleepFadeMultiplier = (remaining.toFloat() / SLEEP_FADE_WINDOW_MS.toFloat()).coerceIn(0f, 1f)
                        applyActiveVolume()
                    }
                    mainHandler.postDelayed(this, SLEEP_TICK_INTERVAL_MS)
                }
            }
        }
        sleepTickerRunnable = ticker
        mainHandler.post(ticker)
    }

    fun cancelSleepTimer() {
        sleepTickerRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepTickerRunnable = null
        sleepEndElapsedMs = 0L
        sleepFadeMultiplier = 1.0f
        applyActiveVolume()
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        cancelSleepTimer()
        mainHandler.removeCallbacks(positionTicker)
        releaseWakeLock()
        listeners.clear()
        runCatching { equalizer?.release() }
        equalizer = null
        enqueueCommand(EngineCommand.Release)
        playbackThread.interrupt()
    }

    // --- Internal orchestration ---

    private fun loadAndStart(track: Track, startPlaying: Boolean, fadeOutFirst: Boolean = false) {
        val gen = loadGeneration.incrementAndGet()
        currentPositionMs = 0L
        clearAbLoop()
        updateTrackState(track, track.quality, track.durationMs)
        setPlayingState(startPlaying)
        enqueueCommand(EngineCommand.LoadTrack(track, startPlaying, gen, fadeOutFirst))
    }

    private fun advanceToNext(fromCompletion: Boolean) {
        if (released.get()) return
        val nextTrack: Track?
        synchronized(stateLock) {
            if (playOrder.isEmpty()) {
                nextTrack = null
            } else if (fromCompletion && repeatMode == RepeatMode.ONE && orderIndex in playOrder.indices) {
                nextTrack = originalQueue[playOrder[orderIndex]]
            } else if (orderIndex + 1 < playOrder.size) {
                orderIndex++
                nextTrack = originalQueue[playOrder[orderIndex]]
            } else if (repeatMode == RepeatMode.ALL) {
                orderIndex = 0
                nextTrack = originalQueue[playOrder[orderIndex]]
            } else {
                nextTrack = null
            }
        }
        if (nextTrack != null) {
            loadAndStart(nextTrack, startPlaying = true, fadeOutFirst = !fromCompletion && playing)
        } else {
            setPlayingState(false)
            enqueueCommand(EngineCommand.Stop)
            notifyQueueCompleted()
        }
    }

    private fun peekNextAutoAdvance(): Pair<Int, Track>? {
        synchronized(stateLock) {
            if (playOrder.isEmpty()) return null
            return when {
                repeatMode == RepeatMode.ONE && orderIndex in playOrder.indices -> {
                    orderIndex to originalQueue[playOrder[orderIndex]]
                }
                orderIndex + 1 < playOrder.size -> {
                    val idx = orderIndex + 1
                    idx to originalQueue[playOrder[idx]]
                }
                repeatMode == RepeatMode.ALL -> {
                    0 to originalQueue[playOrder[0]]
                }
                else -> null
            }
        }
    }

    private fun enqueueCommand(command: EngineCommand) {
        synchronized(commandLock) {
            if (command is EngineCommand.LoadTrack || command is EngineCommand.Release) {
                pendingCommands.clear()
            }
            pendingCommands.addLast(command)
            commandLock.notifyAll()
        }
    }

    private fun pollCommand(waitIfIdle: Boolean): EngineCommand? {
        synchronized(commandLock) {
            if (pendingCommands.isEmpty() && waitIfIdle && !released.get()) {
                // Zero-wake idle sleep until a new command arrives
                runCatching { commandLock.wait() }
            }
            return if (pendingCommands.isEmpty()) null else pendingCommands.removeFirst()
        }
    }

    private fun runPlaybackLoop() {
        val outputInfo = MediaCodec.BufferInfo()
        while (!released.get()) {
            val isIdle = (!playing || (activeSession == null && !usingFallback))
            val cmd = pollCommand(waitIfIdle = isIdle)
            if (cmd != null) {
                if (!handleCommand(cmd)) break
                continue
            }

            if (!playing) continue

            // Check AB-Loop wrap
            val aMs = abLoopStartMs
            val bMs = abLoopEndMs
            if (aMs != null && bMs != null && bMs > aMs + 200L) {
                updatePositionSnapshot()
                val nowElapsed = SystemClock.elapsedRealtime()
                if (currentPositionMs >= bMs && nowElapsed - lastAbLoopSeekElapsedMs > 250L) {
                    lastAbLoopSeekElapsedMs = nowElapsed
                    performSeek(aMs)
                    currentPositionMs = aMs
                    notifyPosition(aMs, currentDurationMs)
                    continue
                }
            }

            if (usingFallback) {
                // MediaPlayer handles its own audio loop; sleep up to 8 min for wake-lock renewal (250ms if AB-loop active)
                synchronized(commandLock) {
                    if (pendingCommands.isEmpty() && !released.get() && playing && usingFallback) {
                        val hasAb = abLoopStartMs != null && abLoopEndMs != null
                        runCatching {
                            commandLock.wait(if (hasAb) 250L else WAKE_LOCK_RENEW_INTERVAL_MS)
                        }
                    }
                }
                wakeLockLease.renewIfDue(playing)
                continue
            }

            wakeLockLease.renewIfDue(playing)
            val session = activeSession ?: continue
            if (!pumpCodecStep(session, outputInfo)) {
                // Current session ended at EOS -> attempt gapless handoff
                handleCodecStreamEnded()
            }
        }
        releaseWakeLock()
        cleanupPlaybackResources()
    }

    private fun handleCommand(cmd: EngineCommand): Boolean {
        when (cmd) {
            is EngineCommand.Release -> return false
            is EngineCommand.Stop -> {
                releaseWakeLock()
                cleanupPlaybackResources()
            }
            is EngineCommand.Play -> {
                acquireWakeLockWithTimeout()
                if (usingFallback) {
                    if (fallbackPrepared) {
                        runCatching { fallbackPlayer?.start() }
                        applyActiveVolume()
                    }
                } else {
                    runCatching { audioTrack?.play() }
                    applyActiveVolume()
                }
            }
            is EngineCommand.Pause -> {
                releaseWakeLock()
                if (usingFallback) {
                    runCatching {
                        if (fallbackPlayer?.isPlaying == true) fallbackPlayer?.pause()
                    }
                } else {
                    runCatching { audioTrack?.pause() }
                }
            }
            is EngineCommand.Seek -> {
                if (cmd.generation == loadGeneration.get()) {
                    performSeek(cmd.positionMs)
                }
            }
            is EngineCommand.LoadTrack -> {
                if (cmd.fadeOutFirst && !usingFallback && activeSession != null && audioTrack != null) {
                    drainQuickFadeOut(activeSession!!, cmd.generation)
                }
                if (cmd.generation == loadGeneration.get()) {
                    loadTrackInternal(cmd.track, cmd.startPlaying)
                }
            }
        }
        return true
    }

    private fun drainQuickFadeOut(session: DecoderSession, targetGen: Int) {
        dspChain.triggerFadeOut(AudioDspChain.DEFAULT_FADE_OUT_MS)
        val info = MediaCodec.BufferInfo()
        val maxEndMs = SystemClock.elapsedRealtime() + 260L
        while (!dspChain.isFadeOutComplete() &&
            SystemClock.elapsedRealtime() < maxEndMs &&
            !released.get() &&
            loadGeneration.get() == targetGen
        ) {
            if (!pumpCodecStep(session, info)) break
        }
    }

    private fun loadTrackInternal(track: Track, startPlaying: Boolean) {
        clearPrebufferedNext()
        crossfadeActive = false
        crossfadeFramesElapsed = 0
        crossfadeTotalFrames = 0
        activeSession?.release()
        activeSession = null
        releaseFallbackPlayer()
        usingFallback = false

        try {
            val session = prepareDecoderSession(track)
            session.prebuffer(2)
            val targetRate = resolveTargetSampleRate(session.sampleRate)
            ensureAudioTrack(targetRate, session.channelCount, session.pcmEncoding, flushExisting = true)

            activeSession = session
            trackAnchorMs = 0L
            headAnchorFrames = 0L
            totalFramesWrittenToCurrentTrack = 0L
            activeSampleRate = targetRate
            activeChannels = session.channelCount
            activeEncoding = session.pcmEncoding

            dspChain.configure(targetRate, session.quality.codec, session.track.replayGainDb)
            dspChain.applyConfig(dspConfig)
            if (startPlaying) {
                dspChain.triggerFadeIn(AudioDspChain.DEFAULT_FADE_IN_MS)
            }
            updateAuditStatus(dspChain.auditor.getStatus())

            val resolvedDurationMs = if (session.durationUs > 0L) session.durationUs / 1000L else track.durationMs
            updateTrackState(track, session.quality, resolvedDurationMs)

            if (startPlaying && playing) {
                acquireWakeLockWithTimeout()
                audioTrack?.play()
                applyActiveVolume()
                applyPlaybackParamsInternal(dspConfig.speed, dspConfig.pitch)
            }
        } catch (_: Exception) {
            activeSession?.release()
            activeSession = null
            releaseAudioTrack()
            startFallbackMediaPlayer(track, startPlaying)
        }
    }

    private fun prepareDecoderSession(track: Track): DecoderSession {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (track.contentUri.isNotEmpty()) {
                try {
                    extractor.setDataSource(context, Uri.parse(track.contentUri), null)
                } catch (_: Exception) {
                    extractor.setDataSource(track.path)
                }
            } else {
                extractor.setDataSource(track.path)
            }

            var audioIndex = -1
            var selectedFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioIndex = i
                    selectedFormat = fmt
                    break
                }
            }
            if (audioIndex < 0 || selectedFormat == null) {
                throw IllegalStateException("No audio track found in ${track.path}")
            }

            extractor.selectTrack(audioIndex)
            val mime = selectedFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = if (selectedFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                selectedFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channelCount = if (selectedFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                selectedFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2
            val pcmEncoding = if (selectedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                selectedFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                AudioFormat.ENCODING_PCM_16BIT
            }
            val bitrateKbps = if (selectedFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                selectedFormat.getInteger(MediaFormat.KEY_BIT_RATE) / 1000
            } else track.quality.bitrateKbps
            val durationUs = if (selectedFormat.containsKey(MediaFormat.KEY_DURATION)) {
                selectedFormat.getLong(MediaFormat.KEY_DURATION)
            } else track.durationMs * 1000L

            val bitDepth = resolveBitDepth(selectedFormat, pcmEncoding, 16)
            val quality = AudioQualityInfo(
                codec = mimeToCodecLabel(mime),
                bitDepth = bitDepth,
                sampleRateHz = sampleRate,
                bitrateKbps = bitrateKbps
            )

            val createdCodec = MediaCodec.createDecoderByType(mime)
            codec = createdCodec
            createdCodec.configure(selectedFormat, null, null, 0)
            createdCodec.start()

            return DecoderSession(
                track = track,
                extractor = extractor,
                codec = createdCodec,
                quality = quality,
                sampleRate = sampleRate,
                channelCount = channelCount,
                pcmEncoding = pcmEncoding,
                durationUs = durationUs
            )
        } catch (e: Exception) {
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            throw e
        }
    }

    private fun pumpCodecStep(session: DecoderSession, info: MediaCodec.BufferInfo): Boolean {
        // 1. Drain any pre-buffered PCM chunks first (from gapless or initial prebuffer)
        val preChunk = session.preDecodedChunks.pollFirst()
        if (preChunk != null) {
            writePcmBytes(preChunk.data, preChunk.size, session)
            return true
        }
        if (session.outputEos) {
            return false
        }

        // 2. Feed compressed samples to MediaCodec
        if (!session.inputEos) {
            val inIdx = session.codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inIdx >= 0) {
                val inputBuf = session.codec.getInputBuffer(inIdx)
                if (inputBuf != null) {
                    val sampleSize = session.extractor.readSampleData(inputBuf, 0)
                    if (sampleSize < 0) {
                        session.codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        session.inputEos = true
                        prepareNextTrackGapless()
                    } else {
                        val pts = session.extractor.sampleTime.coerceAtLeast(0L)
                        session.codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                        session.extractor.advance()
                    }
                }
            }
        }

        // 3. Drain decoded PCM from MediaCodec
        val outIdx = session.codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
        when {
            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                session.updateFromOutputFormat(session.codec.outputFormat)
                val qualityChanged = session.quality
                currentQuality = qualityChanged
                notifyQualityChanged(qualityChanged)
                if (!session.matchesAudioFormat(activeSampleRate, activeChannels, activeEncoding)) {
                    ensureAudioTrack(session.sampleRate, session.channelCount, session.pcmEncoding, flushExisting = true)
                    activeSampleRate = session.sampleRate
                    activeChannels = session.channelCount
                    activeEncoding = session.pcmEncoding
                    if (playing) {
                        audioTrack?.play()
                        applyActiveVolume()
                    }
                }
            }
            outIdx >= 0 -> {
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val outBuf = session.codec.getOutputBuffer(outIdx)
                    if (outBuf != null) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        ensureByteCapacity(info.size)
                        outBuf.get(pcmByteBuffer, 0, info.size)
                        writePcmBytes(pcmByteBuffer, info.size, session)
                    }
                }
                session.codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    session.outputEos = true
                    return false
                }
            }
        }
        return true
    }

    private fun prepareNextTrackGapless() {
        if (prebufferedNextSession != null) return
        val nextPair = peekNextAutoAdvance() ?: return
        val (nextOrderIdx, nextTrack) = nextPair
        try {
            val nextSession = prepareDecoderSession(nextTrack)
            nextSession.prebuffer(6)
            prebufferedNextSession = nextSession
            prebufferedNextOrderIndex = nextOrderIdx
            if (shuffleEnabled && dspConfig.shuffleCrossfadeEnabled &&
                nextSession.matchesAudioFormat(activeSampleRate, activeChannels, activeEncoding)
            ) {
                crossfadeActive = true
                crossfadeFramesElapsed = 0
                crossfadeTotalFrames = ((activeSampleRate.toLong() * AudioDspChain.SHUFFLE_CROSSFADE_MS) / 1000L).toInt().coerceAtLeast(1)
            }
        } catch (_: Exception) {
            // Some devices restrict concurrent hardware decoders; fall back cleanly at EOS
            prebufferedNextSession = null
            prebufferedNextOrderIndex = -1
        }
    }

    private fun handleCodecStreamEnded() {
        val nextSession = prebufferedNextSession
        val nextIdx = prebufferedNextOrderIndex
        prebufferedNextSession = null
        prebufferedNextOrderIndex = -1
        val wasCrossfading = crossfadeActive
        crossfadeActive = false
        crossfadeFramesElapsed = 0
        crossfadeTotalFrames = 0

        val currentGen = loadGeneration.get()
        if (nextSession != null && nextIdx >= 0) {
            val expectedPair = peekNextAutoAdvance()
            if (expectedPair != null && expectedPair.first == nextIdx) {
                synchronized(stateLock) {
                    orderIndex = nextIdx
                }
                activeSession?.release()
                activeSession = nextSession

                val sameFormat = nextSession.matchesAudioFormat(activeSampleRate, activeChannels, activeEncoding)
                if (sameFormat && audioTrack != null) {
                    // True gapless handoff on the same AudioTrack without stop/flush
                    trackAnchorMs = if (wasCrossfading) {
                        ((nextSession.preDecodedChunks.peekFirst()?.presentationTimeUs ?: 0L) / 1000L).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    headAnchorFrames = totalFramesWrittenToCurrentTrack
                    totalFramesWrittenToCurrentTrack = 0L
                } else {
                    val targetRate = resolveTargetSampleRate(nextSession.sampleRate)
                    ensureAudioTrack(
                        targetRate,
                        nextSession.channelCount,
                        nextSession.pcmEncoding,
                        flushExisting = true
                    )
                    trackAnchorMs = 0L
                    headAnchorFrames = 0L
                    totalFramesWrittenToCurrentTrack = 0L
                    activeSampleRate = targetRate
                    activeChannels = nextSession.channelCount
                    activeEncoding = nextSession.pcmEncoding
                    if (playing) {
                        audioTrack?.play()
                    }
                }
                dspChain.configure(activeSampleRate, nextSession.quality.codec, nextSession.track.replayGainDb)
                dspChain.applyConfig(dspConfig)
                updateAuditStatus(dspChain.auditor.getStatus())
                applyActiveVolume()

                val durMs = if (nextSession.durationUs > 0L) nextSession.durationUs / 1000L else nextSession.track.durationMs
                currentPositionMs = trackAnchorMs
                updateTrackState(nextSession.track, nextSession.quality, durMs)
                return
            } else {
                nextSession.release()
            }
        }

        // Standard completion transition (either queue ended, repeat, or sequential decoder open)
        if (currentGen == loadGeneration.get()) {
            activeSession?.release()
            activeSession = null
            mainHandler.post {
                if (!released.get() && currentGen == loadGeneration.get()) {
                    advanceToNext(fromCompletion = true)
                }
            }
        }
    }

    private fun writePcmBytes(raw: ByteArray, size: Int, session: DecoderSession) {
        val trackObj = audioTrack ?: return
        val channels = session.channelCount.coerceAtLeast(1)
        val bytesPerSample = bytesPerSample(session.pcmEncoding)
        val bytesPerFrame = (channels * bytesPerSample).coerceAtLeast(2)
        val totalFrames = size / bytesPerFrame
        if (totalFrames <= 0) return

        // Emit Bluetooth wake-up silent pre-roll frames before first audio chunk if queued
        if (dspChain.hasPendingPreRoll()) {
            writePreRollSilence(trackObj, channels, session.pcmEncoding)
        }

        val totalSamples = totalFrames * channels
        ensureFloatCapacity(totalSamples)
        decodePcmToFloat(raw, size, session.pcmEncoding, pcmFloatBuffer, totalSamples)

        // Trim leading silence (< -65 dB) during first 3s when enabled
        var frameOffset = 0
        var activeFrames = totalFrames
        if (dspChain.isAutoTrimSilenceEnabled() && totalFramesWrittenToCurrentTrack < activeSampleRate.toLong() * 3L) {
            val skipped = dspChain.countLeadingSilentFrames(pcmFloatBuffer, totalFrames, channels)
            if (skipped > 0) {
                frameOffset = skipped
                activeFrames = totalFrames - skipped
                if (activeFrames <= 0) return
            }
        }

        // Trailing silence trim in last 3s of track when enabled
        if (dspChain.isAutoTrimSilenceEnabled() &&
            currentDurationMs > 6_000L &&
            currentPositionMs >= currentDurationMs - 3_000L &&
            dspChain.isBlockSilent(pcmFloatBuffer, totalFrames, channels)
        ) {
            return
        }

        if (frameOffset > 0) {
            val sampleShift = frameOffset * channels
            val remainingSamples = activeFrames * channels
            System.arraycopy(pcmFloatBuffer, sampleShift, pcmFloatBuffer, 0, remainingSamples)
        }

        // Blend with next shuffle track if 1.8s shuffle crossfade is active
        if (crossfadeActive && session.inputEos && prebufferedNextSession != null) {
            blendNextTrackChunk(activeFrames, channels)
        }

        val audit = dspChain.processInterleaved(pcmFloatBuffer, activeFrames, channels)
        if (audit != currentAuditStatus) {
            updateAuditStatus(audit)
        }

        if (dspChain.visualizerEnabled && playing) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVisualizerDispatchMs >= 33L) {
                lastVisualizerDispatchMs = now
                notifyVisualizerLevel(dspChain.visualizerLevel)
            }
        }

        val activeSamples = activeFrames * channels
        if (activeEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            var writtenFloats = 0
            while (writtenFloats < activeSamples && playing && !released.get() && pendingCommands.isEmpty()) {
                val n = trackObj.write(pcmFloatBuffer, writtenFloats, activeSamples - writtenFloats, AudioTrack.WRITE_NON_BLOCKING)
                if (n < 0) break
                if (n == 0) {
                    SystemClock.sleep(4L)
                } else {
                    writtenFloats += n
                }
            }
            totalFramesWrittenToCurrentTrack += writtenFloats.toLong() / channels
            return
        }

        val outBytes = activeFrames * bytesPerFrame
        ensureByteCapacity(outBytes)
        encodeFloatToPcm(pcmFloatBuffer, activeSamples, activeEncoding, pcmByteBuffer)
        writeBlockingResponsive(trackObj, pcmByteBuffer, outBytes, bytesPerFrame)
    }

    private fun writePreRollSilence(trackObj: AudioTrack, channels: Int, encoding: Int) {
        while (dspChain.hasPendingPreRoll() && playing && !released.get() && pendingCommands.isEmpty()) {
            val maxFrames = preRollFloatBuffer.size / channels
            val frames = dspChain.fillPreRollSilence(preRollFloatBuffer, maxFrames, channels)
            if (frames <= 0) break
            val samples = frames * channels
            if (activeEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                var off = 0
                while (off < samples && playing && !released.get()) {
                    val w = trackObj.write(preRollFloatBuffer, off, samples - off, AudioTrack.WRITE_NON_BLOCKING)
                    if (w < 0) break
                    if (w == 0) SystemClock.sleep(2L) else off += w
                }
            } else {
                val bytesNeeded = frames * channels * bytesPerSample(activeEncoding)
                ensureByteCapacity(bytesNeeded)
                for (i in 0 until bytesNeeded) pcmByteBuffer[i] = 0
                writeBlockingResponsive(trackObj, pcmByteBuffer, bytesNeeded, channels * bytesPerSample(activeEncoding))
            }
        }
    }

    private fun blendNextTrackChunk(frameCount: Int, channels: Int) {
        val nextSess = prebufferedNextSession ?: return
        if (nextSess.preDecodedChunks.size < 2 && !nextSess.outputEos) {
            nextSess.prebuffer(4)
        }
        val nextChunk = nextSess.preDecodedChunks.pollFirst() ?: return
        val nextBpf = (nextSess.channelCount * bytesPerSample(nextSess.pcmEncoding)).coerceAtLeast(2)
        val nextFrames = minOf(frameCount, nextChunk.size / nextBpf)
        val nextSamples = nextFrames * channels
        if (crossfadeMixBuffer.size < nextSamples) {
            crossfadeMixBuffer = FloatArray(nextSamples)
        }
        decodePcmToFloat(nextChunk.data, nextFrames * nextBpf, nextSess.pcmEncoding, crossfadeMixBuffer, nextSamples)

        val total = crossfadeTotalFrames.coerceAtLeast(1)
        var idx = 0
        for (f in 0 until nextFrames) {
            val t = (crossfadeFramesElapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            val outGain = 1.0f - t
            val inGain = t
            for (ch in 0 until channels) {
                pcmFloatBuffer[idx] = pcmFloatBuffer[idx] * outGain + crossfadeMixBuffer[idx] * inGain
                idx++
            }
            if (crossfadeFramesElapsed < total) {
                crossfadeFramesElapsed++
            }
        }
    }

    private fun writeBlockingResponsive(trackObj: AudioTrack, data: ByteArray, size: Int, bytesPerFrame: Int) {
        var offset = 0
        while (offset < size && playing && !released.get() && pendingCommands.isEmpty()) {
            val written = trackObj.write(data, offset, size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) break
            if (written == 0) {
                SystemClock.sleep(4L)
            } else {
                offset += written
            }
        }
        totalFramesWrittenToCurrentTrack += offset.toLong() / bytesPerFrame
    }

    private fun performSeek(positionMs: Long) {
        if (usingFallback) {
            runCatching {
                if (fallbackPrepared) {
                    fallbackPlayer?.seekTo(positionMs.toInt())
                }
            }
            return
        }
        val session = activeSession ?: return
        clearPrebufferedNext()
        session.preDecodedChunks.clear()
        session.inputEos = false
        session.outputEos = false

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            session.codec.flush()
            session.extractor.seekTo(positionMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            trackAnchorMs = positionMs
            headAnchorFrames = (audioTrack?.playbackHeadPosition?.toLong() ?: 0L) and 0xFFFFFFFFL
            totalFramesWrittenToCurrentTrack = 0L
            if (playing) {
                audioTrack?.play()
                applyActiveVolume()
            }
        }
    }

    private fun startFallbackMediaPlayer(track: Track, startPlaying: Boolean) {
        usingFallback = true
        fallbackPrepared = false
        val gen = loadGeneration.get()
        val mp = MediaPlayer()
        fallbackPlayer = mp
        try {
            if (audioSessionId != 0) {
                mp.audioSessionId = audioSessionId
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            if (track.contentUri.isNotEmpty()) {
                try {
                    mp.setDataSource(context, Uri.parse(track.contentUri))
                } catch (_: Exception) {
                    mp.setDataSource(track.path)
                }
            } else {
                mp.setDataSource(track.path)
            }
            mp.setOnCompletionListener {
                if (!released.get() && gen == loadGeneration.get()) {
                    mainHandler.post {
                        if (!released.get() && gen == loadGeneration.get()) {
                            advanceToNext(fromCompletion = true)
                        }
                    }
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                if (!released.get() && gen == loadGeneration.get()) {
                    mainHandler.post {
                        if (!released.get() && gen == loadGeneration.get()) {
                            advanceToNext(fromCompletion = true)
                        }
                    }
                }
                true
            }
            mp.prepare()
            fallbackPrepared = true
            if (audioSessionId == 0) {
                audioSessionId = mp.audioSessionId
            }
            val durMs = mp.duration.toLong().takeIf { it > 0L } ?: track.durationMs
            updateAuditStatus(LosslessAuditStatus.NOT_APPLICABLE)
            updateTrackState(track, track.quality, durMs)
            syncFallbackSystemEq()
            applyActiveVolume()
            applyPlaybackParamsInternal(dspConfig.speed, dspConfig.pitch)
            if (startPlaying && playing) {
                acquireWakeLockWithTimeout()
                mp.start()
            }
        } catch (_: Exception) {
            releaseWakeLock()
            releaseFallbackPlayer()
            usingFallback = false
            setPlayingState(false)
        }
    }

    private fun resolveTargetSampleRate(sourceRateHz: Int): Int {
        if (!dspConfig.passthroughSampleRateEnabled) return 48_000
        val valid = AudioTrack.getMinBufferSize(
            sourceRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ) > 0
        return if (valid) sourceRateHz else 48_000
    }

    private fun applyPlaybackParamsInternal(speed: Float, pitch: Float) {
        val s = speed.coerceIn(0.5f, 2.0f)
        val p = pitch.coerceIn(0.5f, 2.0f)
        if (usingFallback) {
            val mp = fallbackPlayer
            if (mp != null && fallbackPrepared) {
                runCatching { mp.playbackParams = PlaybackParams().setSpeed(s).setPitch(p) }
            }
        } else {
            val at = audioTrack
            if (at != null && at.state == AudioTrack.STATE_INITIALIZED) {
                runCatching { at.playbackParams = PlaybackParams().setSpeed(s).setPitch(p) }
            }
        }
    }

    private fun syncFallbackSystemEq() {
        if (!usingFallback) return
        if (dspConfig.eqEnabled && equalizer == null) {
            initEqualizer()
        }
        val eq = equalizer ?: return
        runCatching {
            eq.enabled = dspConfig.eqEnabled
            val count = eq.numberOfBands.toInt().coerceAtLeast(0)
            if (count > 0) {
                val centers = IntArray(count) { i ->
                    val range = eq.getBandFreqRange(i.toShort())
                    ((range.getOrElse(0) { 0 } + range.getOrElse(1) { 0 }) / 2)
                }
                val range = eq.bandLevelRange
                val minMb = range.getOrElse(0) { -1500 }
                val maxMb = range.getOrElse(1) { 1500 }
                dspChain.eq.setAllBandsDb(dspConfig.eqBandsDb)
                val mapped = dspChain.eq.mapToSystemEqBands(centers, minMb, maxMb)
                for (i in mapped.indices) {
                    eq.setBandLevel(i.toShort(), mapped[i])
                }
            }
        }
    }

    private fun acquireWakeLockWithTimeout() {
        wakeLockLease.acquireOrRenewNow()
    }

    private fun releaseWakeLock() {
        wakeLockLease.releaseNow()
    }

    private fun ensureAudioTrack(
        sampleRate: Int,
        channelCount: Int,
        requestedEncoding: Int,
        flushExisting: Boolean
    ) {
        val encoding = resolveSupportedEncoding(sampleRate, channelCount, requestedEncoding)
        val channelMask = channelCountToMask(channelCount)
        val existing = audioTrack
        if (existing != null &&
            existing.state == AudioTrack.STATE_INITIALIZED &&
            sampleRate == activeSampleRate &&
            channelCount == activeChannels &&
            encoding == activeEncoding
        ) {
            if (flushExisting) {
                runCatching {
                    existing.pause()
                    existing.flush()
                }
            }
            return
        }

        releaseAudioTrack()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            .coerceAtLeast(sampleRate * channelCount * bytesPerSample(encoding) / 10)
        val bufferBytes = (minBuf * 4).coerceAtLeast(8192)

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (audioSessionId != 0) {
            builder.setSessionId(audioSessionId)
        }

        val trackObj = builder.build()
        if (audioSessionId == 0) {
            audioSessionId = trackObj.audioSessionId
        }
        audioTrack = trackObj
        applyActiveVolume()
    }

    private fun resolveSupportedEncoding(sampleRate: Int, channelCount: Int, requested: Int): Int {
        val mask = channelCountToMask(channelCount)
        val isSupported = runCatching {
            AudioTrack.getMinBufferSize(sampleRate, mask, requested) > 0
        }.getOrDefault(false)
        return if (isSupported) requested else AudioFormat.ENCODING_PCM_16BIT
    }

    private fun applyActiveVolume() {
        val trackVol = (sleepFadeMultiplier * duckMultiplier).coerceIn(0f, 1f)
        if (usingFallback) {
            val gain = computeReplayGainLinear(currentTrack?.replayGainDb)
            val fallbackVol = (trackVol * gain).coerceIn(0f, 1f)
            runCatching { fallbackPlayer?.setVolume(fallbackVol, fallbackVol) }
        } else {
            runCatching { audioTrack?.setVolume(trackVol) }
        }
    }

    private fun updatePositionSnapshot() {
        if (usingFallback) {
            val mp = fallbackPlayer
            if (mp != null && fallbackPrepared) {
                runCatching {
                    val pos = mp.currentPosition.toLong().coerceAtLeast(0L)
                    currentPositionMs = if (currentDurationMs > 0L) pos.coerceAtMost(currentDurationMs) else pos
                }
            }
            return
        }

        val trackObj = audioTrack ?: return
        if (activeSampleRate <= 0) return
        val head = runCatching { trackObj.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        if (head < headAnchorFrames) {
            headAnchorFrames = 0L
        }
        val elapsedFrames = (head - headAnchorFrames).coerceAtLeast(0L)
        val computedMs = trackAnchorMs + (elapsedFrames * 1000L) / activeSampleRate
        currentPositionMs = if (currentDurationMs > 0L) computedMs.coerceIn(0L, currentDurationMs) else computedMs
    }

    private fun updateTrackState(track: Track?, quality: AudioQualityInfo, durationMs: Long) {
        currentTrack = track
        currentQuality = quality
        currentDurationMs = durationMs.coerceAtLeast(0L)
        mainHandler.post {
            if (released.get()) return@post
            for (listener in listeners) {
                listener.onTrackChanged(track)
                listener.onQualityChanged(quality)
                listener.onPositionUpdated(currentPositionMs, currentDurationMs)
            }
        }
    }

    private fun setPlayingState(isNowPlaying: Boolean) {
        val changed = playing != isNowPlaying
        playing = isNowPlaying
        if (!isNowPlaying) {
            releaseWakeLock()
        } else {
            acquireWakeLockWithTimeout()
        }
        mainHandler.post {
            if (released.get()) return@post
            mainHandler.removeCallbacks(positionTicker)
            if (isNowPlaying && uiPositionUpdatesEnabled) {
                mainHandler.post(positionTicker)
            }
            if (changed) {
                for (listener in listeners) {
                    listener.onPlaybackStateChanged(isNowPlaying)
                }
            }
        }
    }

    private fun updateAuditStatus(status: LosslessAuditStatus) {
        currentAuditStatus = status
        mainHandler.post {
            if (released.get()) return@post
            for (listener in listeners) {
                listener.onLosslessAuditChanged(status)
            }
        }
    }

    private fun notifyVisualizerLevel(level: Float) {
        pendingVisualizerLevel = level
        if (visualizerDispatchPending.compareAndSet(false, true)) {
            mainHandler.post(visualizerDispatchRunnable)
        }
    }

    private fun notifyQueueChanged() {
        val snapshot = getQueue()
        mainHandler.post {
            if (released.get()) return@post
            for (listener in listeners) {
                listener.onQueueChanged(snapshot)
            }
        }
    }

    private fun notifyAbLoopChanged(startMs: Long?, endMs: Long?) {
        mainHandler.post {
            if (released.get()) return@post
            for (listener in listeners) {
                listener.onAbLoopChanged(startMs, endMs)
            }
        }
    }

    private fun ensureFloatCapacity(required: Int) {
        if (pcmFloatBuffer.size < required) {
            pcmFloatBuffer = FloatArray(required)
        }
    }

    private fun decodePcmToFloat(
        raw: ByteArray,
        size: Int,
        encoding: Int,
        out: FloatArray,
        sampleCount: Int
    ) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                var b = 0
                for (i in 0 until sampleCount) {
                    if (b + 3 >= size) break
                    val bits = (raw[b].toInt() and 0xFF) or
                        ((raw[b + 1].toInt() and 0xFF) shl 8) or
                        ((raw[b + 2].toInt() and 0xFF) shl 16) or
                        ((raw[b + 3].toInt() and 0xFF) shl 24)
                    out[i] = Float.fromBits(bits)
                    b += 4
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until sampleCount) {
                    if (i >= size) break
                    out[i] = ((raw[i].toInt() and 0xFF) - 128) / 128.0f
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                var b = 0
                for (i in 0 until sampleCount) {
                    if (b + 2 >= size) break
                    val v = (raw[b].toInt() and 0xFF) or
                        ((raw[b + 1].toInt() and 0xFF) shl 8) or
                        (raw[b + 2].toInt() shl 16)
                    out[i] = v / 8_388_608.0f
                    b += 3
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                var b = 0
                for (i in 0 until sampleCount) {
                    if (b + 3 >= size) break
                    val v = (raw[b].toInt() and 0xFF) or
                        ((raw[b + 1].toInt() and 0xFF) shl 8) or
                        ((raw[b + 2].toInt() and 0xFF) shl 16) or
                        (raw[b + 3].toInt() shl 24)
                    out[i] = v / 2_147_483_648.0f
                    b += 4
                }
            }
            else -> {
                var b = 0
                for (i in 0 until sampleCount) {
                    if (b + 1 >= size) break
                    val sample = (raw[b + 1].toInt() shl 8) or (raw[b].toInt() and 0xFF)
                    out[i] = sample / 32_768.0f
                    b += 2
                }
            }
        }
    }

    private fun encodeFloatToPcm(
        src: FloatArray,
        sampleCount: Int,
        encoding: Int,
        out: ByteArray
    ) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> {
                for (i in 0 until sampleCount) {
                    val v = (src[i].coerceIn(-1f, 1f) * 127f + 128f).roundToInt().coerceIn(0, 255)
                    out[i] = v.toByte()
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                var b = 0
                for (i in 0 until sampleCount) {
                    val v = (src[i].coerceIn(-1f, 1f) * 8_388_607.0f).roundToInt().coerceIn(-8_388_608, 8_388_607)
                    out[b] = (v and 0xFF).toByte()
                    out[b + 1] = ((v ushr 8) and 0xFF).toByte()
                    out[b + 2] = ((v ushr 16) and 0xFF).toByte()
                    b += 3
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                var b = 0
                for (i in 0 until sampleCount) {
                    val v = (src[i].coerceIn(-1f, 1f) * 2_147_483_647.0).roundToInt()
                    out[b] = (v and 0xFF).toByte()
                    out[b + 1] = ((v ushr 8) and 0xFF).toByte()
                    out[b + 2] = ((v ushr 16) and 0xFF).toByte()
                    out[b + 3] = ((v ushr 24) and 0xFF).toByte()
                    b += 4
                }
            }
            else -> {
                var b = 0
                for (i in 0 until sampleCount) {
                    val v = (src[i].coerceIn(-1f, 1f) * 32_767.0f).roundToInt().coerceIn(-32_768, 32_767)
                    out[b] = (v and 0xFF).toByte()
                    out[b + 1] = ((v ushr 8) and 0xFF).toByte()
                    b += 2
                }
            }
        }
    }

    private fun notifyQualityChanged(quality: AudioQualityInfo) {
        mainHandler.post {
            if (released.get()) return@post
            for (listener in listeners) {
                listener.onQualityChanged(quality)
            }
        }
    }

    private fun notifyPosition(pos: Long, dur: Long) {
        mainHandler.post {
            if (released.get()) return@post
            for (listener in listeners) {
                listener.onPositionUpdated(pos, dur)
            }
        }
    }

    private fun notifyQueueCompleted() {
        mainHandler.post {
            if (released.get()) return@post
            for (listener in listeners) {
                listener.onQueueCompleted()
            }
        }
    }

    private fun ensureByteCapacity(required: Int) {
        if (pcmByteBuffer.size < required) {
            pcmByteBuffer = ByteArray(required)
        }
    }

    private fun clearPrebufferedNext() {
        prebufferedNextSession?.release()
        prebufferedNextSession = null
        prebufferedNextOrderIndex = -1
    }

    private fun releaseAudioTrack() {
        audioTrack?.let { trackObj ->
            runCatching { trackObj.pause() }
            runCatching { trackObj.flush() }
            runCatching { trackObj.release() }
        }
        audioTrack = null
    }

    private fun releaseFallbackPlayer() {
        fallbackPrepared = false
        fallbackPlayer?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        fallbackPlayer = null
    }

    private fun cleanupPlaybackResources() {
        clearPrebufferedNext()
        activeSession?.release()
        activeSession = null
        releaseAudioTrack()
        releaseFallbackPlayer()
        usingFallback = false
    }

    companion object {
        private const val CODEC_TIMEOUT_US = 8_000L
        private const val POSITION_UPDATE_INTERVAL_MS = 200L
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 3_000L
        private const val SLEEP_FADE_WINDOW_MS = 5_000L
        private const val SLEEP_TICK_INTERVAL_MS = 100L
        internal const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        internal const val WAKE_LOCK_RENEW_INTERVAL_MS = 8 * 60_000L

        internal fun computeReplayGainLinear(replayGainDb: Float?): Float {
            if (replayGainDb == null || !replayGainDb.isFinite()) return 1.0f
            return 10.0.pow((replayGainDb / 20.0).toDouble()).toFloat().coerceIn(0.1f, 2.0f)
        }

        private fun mimeToCodecLabel(mime: String): String {
            return when (mime.lowercase()) {
                "audio/flac" -> "FLAC"
                "audio/alac" -> "ALAC"
                "audio/opus" -> "OPUS"
                "audio/vorbis" -> "OGG"
                "audio/mp4a-latm" -> "AAC"
                "audio/mpeg" -> "MP3"
                "audio/raw" -> "WAV"
                "audio/3gpp" -> "AMR-NB"
                "audio/amr-wb" -> "AMR-WB"
                else -> mime.removePrefix("audio/").uppercase().ifBlank { "UNKNOWN" }
            }
        }

        private fun resolveBitDepth(format: MediaFormat, pcmEncoding: Int, defaultDepth: Int): Int {
            if (format.containsKey("bits-per-sample")) {
                val bps = format.getInteger("bits-per-sample")
                if (bps > 0) return bps
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (pcmEncoding) {
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> return 24
                    AudioFormat.ENCODING_PCM_32BIT -> return 32
                }
            }
            return when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 8
                AudioFormat.ENCODING_PCM_16BIT -> 16
                AudioFormat.ENCODING_PCM_FLOAT -> 32
                else -> defaultDepth
            }
        }

        private fun bytesPerSample(pcmEncoding: Int): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (pcmEncoding) {
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> return 3
                    AudioFormat.ENCODING_PCM_32BIT -> return 4
                }
            }
            return when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
        }

        private fun channelCountToMask(channelCount: Int): Int {
            return when (channelCount) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                4 -> AudioFormat.CHANNEL_OUT_QUAD
                6 -> AudioFormat.CHANNEL_OUT_5POINT1
                8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                else -> AudioFormat.CHANNEL_OUT_STEREO
            }
        }
    }
}
