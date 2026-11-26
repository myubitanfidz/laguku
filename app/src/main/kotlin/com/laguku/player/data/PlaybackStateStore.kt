package com.laguku.player.data

import com.laguku.player.audio.TenBandBiquadEq
import com.laguku.player.model.AudioDspConfig
import com.laguku.player.model.Track
import com.laguku.player.service.MorningAlarmConfig
import java.io.File
import java.util.Calendar

data class TrackResumeEntry(
    val path: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long
)

data class ListeningCountEntry(
    val key: String,
    val title: String,
    val subtitle: String,
    val playCount: Int,
    val listenedMs: Long
)

data class ListeningInsights(
    val totalListenedMs: Long,
    val topTracks: List<ListeningCountEntry>,
    val topArtists: List<ListeningCountEntry>
)

/**
 * Lightweight file-backed store for:
 * - Resume memory (100-entry LRU; auto-resumes tracks > 3 min or containing chapters)
 * - Per-track user lyric sync offsets
 * - Offline listening insights (< 50 KB bounded JSON, sessions >= 30s)
 * - Pinned/blacklisted folders, min-duration filter, AMOLED night mode, morning alarm, & DSP config
 */
class PlaybackStateStore(private val storageFile: File) {

    private val lock = Any()

    private val resumeEntries = LinkedHashMap<String, TrackResumeEntry>()
    private val lyricOffsetsMs = LinkedHashMap<String, Long>()

    private var totalListenedMs: Long = 0L
    private var currentMonthKey: String = currentMonthBucket(System.currentTimeMillis())
    private val trackStats = LinkedHashMap<String, MutableTrackStat>()
    private val artistStats = LinkedHashMap<String, MutableArtistStat>()

    private val pinnedFolders = LinkedHashSet<String>()
    private val blacklistedFolders = LinkedHashSet<String>()
    private var minDurationFilterEnabled: Boolean = true
    private var amoledNightMode: Boolean = false
    private var lyricHighlightFraction: Float = LYRIC_ANCHOR_OPTIMAL
    private var glassTransparencyLevel: Float = DEFAULT_GLASS_TRANSPARENCY
    private var morningAlarm: MorningAlarmConfig = MorningAlarmConfig()
    private var dspConfig: AudioDspConfig = AudioDspConfig()

    private data class MutableTrackStat(
        val path: String,
        var title: String,
        var artist: String,
        var totalCount: Int,
        var monthlyCount: Int,
        var listenedMs: Long,
        var lastPlayedMs: Long
    )

    private data class MutableArtistStat(
        val artist: String,
        var playCount: Int,
        var listenedMs: Long,
        var lastPlayedMs: Long
    )

    init {
        load()
    }

    // --- Resume Memory ---

    fun saveResumePosition(track: Track, positionMs: Long, nowMs: Long = System.currentTimeMillis()) {
        val key = trackKey(track) ?: return
        val dur = track.durationMs.coerceAtLeast(0L)
        if (dur > 0L && positionMs >= dur - 4_000L) {
            synchronized(lock) {
                resumeEntries.remove(key)
                saveLocked()
            }
            return
        }
        synchronized(lock) {
            resumeEntries[key] = TrackResumeEntry(
                path = key,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = dur,
                updatedAtMs = nowMs
            )
            trimResumeLruLocked()
            saveLocked()
        }
    }

    fun getSavedPositionMs(trackPath: String): Long? = synchronized(lock) {
        resumeEntries[trackPath]?.positionMs
    }

    fun shouldAutoResume(track: Track): Long? {
        val key = trackKey(track) ?: return null
        val eligible = track.durationMs > AUTO_RESUME_MIN_DURATION_MS || track.chapters.isNotEmpty()
        if (!eligible) return null
        synchronized(lock) {
            val entry = resumeEntries[key] ?: return null
            val dur = if (track.durationMs > 0L) track.durationMs else entry.durationMs
            if (entry.positionMs < 5_000L) return null
            if (dur > 10_000L && entry.positionMs >= dur - 5_000L) return null
            return entry.positionMs
        }
    }

    fun getResumeCount(): Int = synchronized(lock) { resumeEntries.size }

    // --- Lyric Offsets ---

    fun getLyricOffsetMs(trackPath: String): Long = synchronized(lock) {
        lyricOffsetsMs[trackPath] ?: 0L
    }

    fun setLyricOffsetMs(trackPath: String, offsetMs: Long) {
        if (trackPath.isBlank()) return
        synchronized(lock) {
            if (offsetMs == 0L) {
                lyricOffsetsMs.remove(trackPath)
            } else {
                lyricOffsetsMs[trackPath] = offsetMs.coerceIn(-30_000L, 30_000L)
            }
            saveLocked()
        }
    }

    fun getAllLyricOffsets(): Map<String, Long> = synchronized(lock) { LinkedHashMap(lyricOffsetsMs) }

    fun replaceAllLyricOffsets(offsets: Map<String, Long>) {
        synchronized(lock) {
            lyricOffsetsMs.clear()
            for ((k, v) in offsets) {
                if (k.isNotBlank() && v != 0L) {
                    lyricOffsetsMs[k] = v.coerceIn(-30_000L, 30_000L)
                }
            }
            saveLocked()
        }
    }

    // --- Listening Insights ---

    fun recordPlaySession(track: Track, listenedMs: Long, nowEpochMs: Long = System.currentTimeMillis()) {
        if (listenedMs < MIN_INSIGHT_SESSION_MS) return
        val key = trackKey(track) ?: return
        val monthBucket = currentMonthBucket(nowEpochMs)

        synchronized(lock) {
            if (monthBucket != currentMonthKey) {
                currentMonthKey = monthBucket
                for (st in trackStats.values) {
                    st.monthlyCount = 0
                }
            }

            totalListenedMs += listenedMs

            val existingTrack = trackStats[key]
            if (existingTrack != null) {
                existingTrack.title = track.title
                existingTrack.artist = track.artist
                existingTrack.totalCount++
                existingTrack.monthlyCount++
                existingTrack.listenedMs += listenedMs
                existingTrack.lastPlayedMs = nowEpochMs
            } else {
                trackStats[key] = MutableTrackStat(
                    path = key,
                    title = track.title,
                    artist = track.artist,
                    totalCount = 1,
                    monthlyCount = 1,
                    listenedMs = listenedMs,
                    lastPlayedMs = nowEpochMs
                )
            }

            val artistName = track.artist.trim().ifBlank { "Unknown Artist" }
            val artistKey = artistName.lowercase()
            val existingArtist = artistStats[artistKey]
            if (existingArtist != null) {
                existingArtist.playCount++
                existingArtist.listenedMs += listenedMs
                existingArtist.lastPlayedMs = nowEpochMs
            } else {
                artistStats[artistKey] = MutableArtistStat(
                    artist = artistName,
                    playCount = 1,
                    listenedMs = listenedMs,
                    lastPlayedMs = nowEpochMs
                )
            }

            enforceStatsCapLocked()
            saveLocked()
        }
    }

    fun getInsightsSummary(): ListeningInsights = synchronized(lock) {
        val topTracks = trackStats.values
            .sortedWith(compareByDescending<MutableTrackStat> { it.totalCount }.thenByDescending { it.listenedMs })
            .take(5)
            .map {
                ListeningCountEntry(
                    key = it.path,
                    title = it.title,
                    subtitle = it.artist,
                    playCount = it.totalCount,
                    listenedMs = it.listenedMs
                )
            }
        val topArtists = artistStats.values
            .sortedWith(compareByDescending<MutableArtistStat> { it.playCount }.thenByDescending { it.listenedMs })
            .take(5)
            .map {
                ListeningCountEntry(
                    key = it.artist,
                    title = it.artist,
                    subtitle = "${it.playCount} putar",
                    playCount = it.playCount,
                    listenedMs = it.listenedMs
                )
            }
        ListeningInsights(
            totalListenedMs = totalListenedMs,
            topTracks = topTracks,
            topArtists = topArtists
        )
    }

    fun getTotalPlayCountsMap(): Map<String, Int> = synchronized(lock) {
        val map = HashMap<String, Int>(trackStats.size)
        for ((k, v) in trackStats) map[k] = v.totalCount
        map
    }

    fun getMonthlyPlayCountsMap(): Map<String, Int> = synchronized(lock) {
        val map = HashMap<String, Int>(trackStats.size)
        for ((k, v) in trackStats) {
            if (v.monthlyCount > 0) map[k] = v.monthlyCount
        }
        map
    }

    // --- Folder Pinning / Blacklist / Settings ---

    fun getPinnedFolders(): Set<String> = synchronized(lock) { LinkedHashSet(pinnedFolders) }

    fun togglePinnedFolder(folderPath: String): Boolean = synchronized(lock) {
        val added = if (pinnedFolders.contains(folderPath)) {
            pinnedFolders.remove(folderPath)
            false
        } else {
            pinnedFolders.add(folderPath)
            true
        }
        saveLocked()
        added
    }

    fun getBlacklistedFolders(): Set<String> = synchronized(lock) { LinkedHashSet(blacklistedFolders) }

    fun addBlacklistedFolder(folderPath: String) {
        synchronized(lock) {
            blacklistedFolders.add(folderPath)
            pinnedFolders.remove(folderPath)
            saveLocked()
        }
    }

    fun removeBlacklistedFolder(folderPath: String) {
        synchronized(lock) {
            blacklistedFolders.remove(folderPath)
            saveLocked()
        }
    }

    fun replaceFolders(pinned: Set<String>, blacklisted: Set<String>) {
        synchronized(lock) {
            pinnedFolders.clear()
            pinnedFolders.addAll(pinned)
            blacklistedFolders.clear()
            blacklistedFolders.addAll(blacklisted)
            saveLocked()
        }
    }

    fun isMinDurationFilterEnabled(): Boolean = synchronized(lock) { minDurationFilterEnabled }

    fun setMinDurationFilterEnabled(enabled: Boolean) {
        synchronized(lock) {
            minDurationFilterEnabled = enabled
            saveLocked()
        }
    }

    fun isAmoledNightMode(): Boolean = synchronized(lock) { amoledNightMode }

    fun setAmoledNightMode(enabled: Boolean) {
        synchronized(lock) {
            amoledNightMode = enabled
            saveLocked()
        }
    }

    fun getLyricHighlightFraction(): Float = synchronized(lock) { lyricHighlightFraction }

    fun setLyricHighlightFraction(fraction: Float) {
        synchronized(lock) {
            lyricHighlightFraction = fraction.coerceIn(0.20f, 0.60f)
            saveLocked()
        }
    }

    fun getGlassTransparencyLevel(): Float = synchronized(lock) { glassTransparencyLevel }

    fun setGlassTransparencyLevel(level: Float) {
        synchronized(lock) {
            glassTransparencyLevel = level.coerceIn(0f, 1f)
            saveLocked()
        }
    }

    fun getMorningAlarm(): MorningAlarmConfig = synchronized(lock) { morningAlarm }

    fun setMorningAlarm(config: MorningAlarmConfig) {
        synchronized(lock) {
            morningAlarm = config
            saveLocked()
        }
    }

    fun getDspConfig(): AudioDspConfig = synchronized(lock) {
        dspConfig.copy(eqBandsDb = dspConfig.eqBandsDb.copyOf())
    }

    fun setDspConfig(config: AudioDspConfig) {
        synchronized(lock) {
            dspConfig = config.copy(eqBandsDb = config.eqBandsDb.copyOf())
            saveLocked()
        }
    }

    fun exportInsightsSnapshot(): Map<String, Any?> = synchronized(lock) {
        mapOf(
            "totalListenedMs" to totalListenedMs,
            "monthKey" to currentMonthKey,
            "tracks" to trackStats.values.map {
                mapOf(
                    "path" to it.path,
                    "title" to it.title,
                    "artist" to it.artist,
                    "totalCount" to it.totalCount,
                    "monthlyCount" to it.monthlyCount,
                    "listenedMs" to it.listenedMs,
                    "lastPlayedMs" to it.lastPlayedMs
                )
            },
            "artists" to artistStats.values.map {
                mapOf(
                    "artist" to it.artist,
                    "playCount" to it.playCount,
                    "listenedMs" to it.listenedMs,
                    "lastPlayedMs" to it.lastPlayedMs
                )
            }
        )
    }

    fun importInsightsSnapshot(raw: Map<*, *>?) {
        if (raw == null) return
        synchronized(lock) {
            totalListenedMs = SimpleJson.asLong(raw["totalListenedMs"], 0L)
            currentMonthKey = raw["monthKey"]?.toString()?.ifBlank { currentMonthKey } ?: currentMonthKey
            trackStats.clear()
            (raw["tracks"] as? List<*>)?.forEach { item ->
                val m = item as? Map<*, *> ?: return@forEach
                val path = m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                trackStats[path] = MutableTrackStat(
                    path = path,
                    title = m["title"]?.toString().orEmpty(),
                    artist = m["artist"]?.toString().orEmpty(),
                    totalCount = SimpleJson.asInt(m["totalCount"], 0),
                    monthlyCount = SimpleJson.asInt(m["monthlyCount"], 0),
                    listenedMs = SimpleJson.asLong(m["listenedMs"], 0L),
                    lastPlayedMs = SimpleJson.asLong(m["lastPlayedMs"], 0L)
                )
            }
            artistStats.clear()
            (raw["artists"] as? List<*>)?.forEach { item ->
                val m = item as? Map<*, *> ?: return@forEach
                val name = m["artist"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                artistStats[name.lowercase()] = MutableArtistStat(
                    artist = name,
                    playCount = SimpleJson.asInt(m["playCount"], 0),
                    listenedMs = SimpleJson.asLong(m["listenedMs"], 0L),
                    lastPlayedMs = SimpleJson.asLong(m["lastPlayedMs"], 0L)
                )
            }
            enforceStatsCapLocked()
            saveLocked()
        }
    }

    fun getSerializedSizeBytes(): Int = if (storageFile.isFile) storageFile.length().toInt() else 0

    private fun trimResumeLruLocked() {
        while (resumeEntries.size > MAX_RESUME_ENTRIES) {
            val oldestKey = resumeEntries.values.minByOrNull { it.updatedAtMs }?.path ?: break
            resumeEntries.remove(oldestKey)
        }
    }

    private fun enforceStatsCapLocked() {
        while (trackStats.size > MAX_TRACK_STATS) {
            val victim = trackStats.values.minWithOrNull(
                compareBy<MutableTrackStat> { it.totalCount }.thenBy { it.lastPlayedMs }
            )?.path ?: break
            trackStats.remove(victim)
        }
        while (artistStats.size > MAX_ARTIST_STATS) {
            val victim = artistStats.values.minWithOrNull(
                compareBy<MutableArtistStat> { it.playCount }.thenBy { it.lastPlayedMs }
            )?.artist?.lowercase() ?: break
            artistStats.remove(victim)
        }
    }

    private fun load() {
        synchronized(lock) {
            if (!storageFile.isFile || !storageFile.canRead()) return
            val root = runCatching {
                SimpleJson.parseObject(storageFile.readText(Charsets.UTF_8))
            }.getOrNull() ?: return

            (root["resume"] as? List<*>)?.forEach { item ->
                val m = item as? Map<*, *> ?: return@forEach
                val path = m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                resumeEntries[path] = TrackResumeEntry(
                    path = path,
                    positionMs = SimpleJson.asLong(m["pos"], 0L),
                    durationMs = SimpleJson.asLong(m["dur"], 0L),
                    updatedAtMs = SimpleJson.asLong(m["ts"], 0L)
                )
            }
            trimResumeLruLocked()

            (root["lyricOffsets"] as? Map<*, *>)?.forEach { (k, v) ->
                val key = k?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                val off = SimpleJson.asLong(v, 0L)
                if (off != 0L) lyricOffsetsMs[key] = off
            }

            (root["insights"] as? Map<*, *>)?.let { ins ->
                totalListenedMs = SimpleJson.asLong(ins["totalListenedMs"], 0L)
                currentMonthKey = ins["monthKey"]?.toString()?.ifBlank { currentMonthKey } ?: currentMonthKey
                (ins["tracks"] as? List<*>)?.forEach { item ->
                    val m = item as? Map<*, *> ?: return@forEach
                    val path = m["path"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                    trackStats[path] = MutableTrackStat(
                        path = path,
                        title = m["title"]?.toString().orEmpty(),
                        artist = m["artist"]?.toString().orEmpty(),
                        totalCount = SimpleJson.asInt(m["totalCount"], 0),
                        monthlyCount = SimpleJson.asInt(m["monthlyCount"], 0),
                        listenedMs = SimpleJson.asLong(m["listenedMs"], 0L),
                        lastPlayedMs = SimpleJson.asLong(m["lastPlayedMs"], 0L)
                    )
                }
                (ins["artists"] as? List<*>)?.forEach { item ->
                    val m = item as? Map<*, *> ?: return@forEach
                    val name = m["artist"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                    artistStats[name.lowercase()] = MutableArtistStat(
                        artist = name,
                        playCount = SimpleJson.asInt(m["playCount"], 0),
                        listenedMs = SimpleJson.asLong(m["listenedMs"], 0L),
                        lastPlayedMs = SimpleJson.asLong(m["lastPlayedMs"], 0L)
                    )
                }
            }

            (root["pinnedFolders"] as? List<*>)?.forEach { v ->
                v?.toString()?.takeIf { it.isNotBlank() }?.let { pinnedFolders.add(it) }
            }
            (root["blacklistedFolders"] as? List<*>)?.forEach { v ->
                v?.toString()?.takeIf { it.isNotBlank() }?.let { blacklistedFolders.add(it) }
            }

            minDurationFilterEnabled = SimpleJson.asBoolean(root["minDurationFilter"], true)
            amoledNightMode = SimpleJson.asBoolean(root["amoledNightMode"], false)
            lyricHighlightFraction = SimpleJson.asDouble(
                root["lyricHighlightFraction"],
                LYRIC_ANCHOR_OPTIMAL.toDouble()
            ).toFloat().coerceIn(0.20f, 0.60f)
            glassTransparencyLevel = SimpleJson.asDouble(
                root["glassTransparencyLevel"],
                DEFAULT_GLASS_TRANSPARENCY.toDouble()
            ).toFloat().coerceIn(0f, 1f)

            (root["morningAlarm"] as? Map<*, *>)?.let { am ->
                morningAlarm = MorningAlarmConfig(
                    enabled = SimpleJson.asBoolean(am["enabled"], false),
                    hour = SimpleJson.asInt(am["hour"], 7).coerceIn(0, 23),
                    minute = SimpleJson.asInt(am["minute"], 0).coerceIn(0, 59),
                    fadeInSeconds = SimpleJson.asInt(am["fadeInSeconds"], 30).coerceIn(5, 120)
                )
            }

            (root["dsp"] as? Map<*, *>)?.let { d ->
                val bands = FloatArray(TenBandBiquadEq.BAND_COUNT)
                (d["eqBandsDb"] as? List<*>)?.let { list ->
                    for (i in 0 until minOf(bands.size, list.size)) {
                        bands[i] = SimpleJson.asDouble(list[i], 0.0).toFloat()
                    }
                }
                dspConfig = AudioDspConfig(
                    eqEnabled = SimpleJson.asBoolean(d["eqEnabled"], false),
                    eqBandsDb = bands,
                    crossfeedLevel = SimpleJson.asDouble(d["crossfeedLevel"], 0.0).toFloat().coerceIn(0f, 1f),
                    smartLoudnessEnabled = SimpleJson.asBoolean(d["smartLoudnessEnabled"], false),
                    autoTrimSilenceEnabled = SimpleJson.asBoolean(d["autoTrimSilenceEnabled"], false),
                    balance = SimpleJson.asDouble(d["balance"], 0.0).toFloat().coerceIn(-1f, 1f),
                    monoDownmix = SimpleJson.asBoolean(d["monoDownmix"], false),
                    shuffleCrossfadeEnabled = SimpleJson.asBoolean(d["shuffleCrossfadeEnabled"], false),
                    btPreRollEnabled = SimpleJson.asBoolean(d["btPreRollEnabled"], true),
                    passthroughSampleRateEnabled = SimpleJson.asBoolean(d["passthroughSampleRateEnabled"], true),
                    speed = SimpleJson.asDouble(d["speed"], 1.0).toFloat().coerceIn(0.5f, 2.0f),
                    pitch = SimpleJson.asDouble(d["pitch"], 1.0).toFloat().coerceIn(0.5f, 2.0f)
                )
            }
        }
    }

    private fun saveLocked() {
        runCatching {
            storageFile.parentFile?.mkdirs()
            var json = serializeLocked()
            // Strictly enforce < 50 KB JSON size ceiling
            while (json.toByteArray(Charsets.UTF_8).size > MAX_STORE_JSON_BYTES && trackStats.size > 10) {
                val oldest = trackStats.values.minByOrNull { it.lastPlayedMs }?.path ?: break
                trackStats.remove(oldest)
                json = serializeLocked()
            }
            val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (storageFile.exists()) storageFile.delete()
            if (!tmp.renameTo(storageFile)) {
                storageFile.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun serializeLocked(): String {
        val root = LinkedHashMap<String, Any?>()
        root["resume"] = resumeEntries.values.map {
            mapOf("path" to it.path, "pos" to it.positionMs, "dur" to it.durationMs, "ts" to it.updatedAtMs)
        }
        root["lyricOffsets"] = lyricOffsetsMs
        root["insights"] = mapOf(
            "totalListenedMs" to totalListenedMs,
            "monthKey" to currentMonthKey,
            "tracks" to trackStats.values.map {
                mapOf(
                    "path" to it.path,
                    "title" to it.title,
                    "artist" to it.artist,
                    "totalCount" to it.totalCount,
                    "monthlyCount" to it.monthlyCount,
                    "listenedMs" to it.listenedMs,
                    "lastPlayedMs" to it.lastPlayedMs
                )
            },
            "artists" to artistStats.values.map {
                mapOf(
                    "artist" to it.artist,
                    "playCount" to it.playCount,
                    "listenedMs" to it.listenedMs,
                    "lastPlayedMs" to it.lastPlayedMs
                )
            }
        )
        root["pinnedFolders"] = pinnedFolders.toList()
        root["blacklistedFolders"] = blacklistedFolders.toList()
        root["minDurationFilter"] = minDurationFilterEnabled
        root["amoledNightMode"] = amoledNightMode
        root["lyricHighlightFraction"] = lyricHighlightFraction
        root["glassTransparencyLevel"] = glassTransparencyLevel
        root["morningAlarm"] = mapOf(
            "enabled" to morningAlarm.enabled,
            "hour" to morningAlarm.hour,
            "minute" to morningAlarm.minute,
            "fadeInSeconds" to morningAlarm.fadeInSeconds
        )
        root["dsp"] = mapOf(
            "eqEnabled" to dspConfig.eqEnabled,
            "eqBandsDb" to dspConfig.eqBandsDb,
            "crossfeedLevel" to dspConfig.crossfeedLevel,
            "smartLoudnessEnabled" to dspConfig.smartLoudnessEnabled,
            "autoTrimSilenceEnabled" to dspConfig.autoTrimSilenceEnabled,
            "balance" to dspConfig.balance,
            "monoDownmix" to dspConfig.monoDownmix,
            "shuffleCrossfadeEnabled" to dspConfig.shuffleCrossfadeEnabled,
            "btPreRollEnabled" to dspConfig.btPreRollEnabled,
            "passthroughSampleRateEnabled" to dspConfig.passthroughSampleRateEnabled,
            "speed" to dspConfig.speed,
            "pitch" to dspConfig.pitch
        )
        return SimpleJson.stringify(root)
    }

    private fun trackKey(track: Track): String? {
        return track.path.takeIf { it.isNotBlank() }
            ?: if (track.id != 0L) "id:${track.id}" else null
    }

    companion object {
        const val MAX_RESUME_ENTRIES = 100
        const val AUTO_RESUME_MIN_DURATION_MS = 180_000L
        const val MIN_INSIGHT_SESSION_MS = 30_000L
        const val MAX_STORE_JSON_BYTES = 48 * 1024
        const val LYRIC_ANCHOR_TOP = 0.28f
        const val LYRIC_ANCHOR_OPTIMAL = 0.36f
        const val LYRIC_ANCHOR_CENTER = 0.48f
        const val DEFAULT_GLASS_TRANSPARENCY = 0.50f
        private const val MAX_TRACK_STATS = 120
        private const val MAX_ARTIST_STATS = 80

        fun currentMonthBucket(epochMs: Long): String {
            val cal = Calendar.getInstance()
            cal.timeInMillis = epochMs
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            return "$year-${month.toString().padStart(2, '0')}"
        }
    }
}
