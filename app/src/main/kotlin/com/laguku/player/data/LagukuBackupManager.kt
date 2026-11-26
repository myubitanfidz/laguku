package com.laguku.player.data

import com.laguku.player.audio.TenBandBiquadEq
import com.laguku.player.model.Playlist
import com.laguku.player.model.Track
import com.laguku.player.service.AudioDeviceProfile
import com.laguku.player.service.BluetoothProfileManager
import com.laguku.player.service.BluetoothSettings
import com.laguku.player.service.DeviceRouteType
import com.laguku.player.service.MorningAlarmConfig
import com.laguku.player.service.MultiClickMediaMapper
import java.io.File

data class BackupImportResult(
    val success: Boolean,
    val profilesImported: Int = 0,
    val playlistsImported: Int = 0,
    val rejectedPathsCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Single-file JSON backup & restore covering per-device EQ profiles, Bluetooth settings,
 * lyric sync offsets, pinned/blacklisted folders, TWS multi-click map, morning alarm,
 * listening insights, and M3U8 playlist references (with canonicalPath + real-path traversal guard).
 */
object LagukuBackupManager {

    const val SCHEMA_VERSION = 1
    const val MAX_BACKUP_BYTES = 2 * 1024 * 1024

    fun exportToJson(
        stateStore: PlaybackStateStore,
        btManager: BluetoothProfileManager,
        clickMapper: MultiClickMediaMapper,
        playlists: List<Playlist>,
        allowedRootDir: File
    ): String {
        val rootCanonical = runCatching { allowedRootDir.canonicalFile }.getOrNull() ?: allowedRootDir
        val btSettings = btManager.getSettings()

        val root = LinkedHashMap<String, Any?>()
        root["schemaVersion"] = SCHEMA_VERSION
        root["exportedAtMs"] = System.currentTimeMillis()
        root["bluetoothSettings"] = mapOf(
            "autoPlayOnConnect" to btSettings.autoPlayOnConnect,
            "autoPauseOnDisconnect" to btSettings.autoPauseOnDisconnect,
            "defaultBtLatencyOffsetMs" to btSettings.defaultBtLatencyOffsetMs,
            "btPreRollEnabled" to btSettings.btPreRollEnabled
        )
        root["deviceProfiles"] = btManager.getAllProfiles().map { p ->
            mapOf(
                "deviceKey" to p.deviceKey,
                "displayName" to p.displayName,
                "deviceType" to p.deviceType.name,
                "eqEnabled" to p.eqEnabled,
                "eqBandsDb" to p.eqBandsDb,
                "crossfeedLevel" to p.crossfeedLevel,
                "safeStartVolumePercent" to p.safeStartVolumePercent,
                "lyricLatencyOffsetMs" to p.lyricLatencyOffsetMs
            )
        }
        root["lyricOffsets"] = stateStore.getAllLyricOffsets()
        root["pinnedFolders"] = stateStore.getPinnedFolders().filter { isPathSafe(it, rootCanonical) }
        root["blacklistedFolders"] = stateStore.getBlacklistedFolders().filter { isPathSafe(it, rootCanonical) }
        root["minDurationFilterEnabled"] = stateStore.isMinDurationFilterEnabled()
        root["amoledNightMode"] = stateStore.isAmoledNightMode()
        val alarm = stateStore.getMorningAlarm()
        root["morningAlarm"] = mapOf(
            "enabled" to alarm.enabled,
            "hour" to alarm.hour,
            "minute" to alarm.minute,
            "fadeInSeconds" to alarm.fadeInSeconds
        )
        root["twsClickMappings"] = clickMapper.exportStringMap()
        root["insights"] = stateStore.exportInsightsSnapshot()
        root["playlists"] = playlists
            .filter { !it.filePath.startsWith("smart://") }
            .map { pl ->
                mapOf(
                    "name" to pl.name,
                    "trackPaths" to pl.tracks.map { it.path }.filter { isPathSafe(it, rootCanonical) }
                )
            }
        return SimpleJson.stringify(root)
    }

    fun importFromJson(
        jsonText: String,
        stateStore: PlaybackStateStore,
        btManager: BluetoothProfileManager,
        clickMapper: MultiClickMediaMapper,
        playlistOutputDir: File,
        allowedRootDir: File
    ): BackupImportResult {
        if (jsonText.isBlank() || jsonText.toByteArray(Charsets.UTF_8).size > MAX_BACKUP_BYTES) {
            return BackupImportResult(success = false, errorMessage = "Invalid or oversized backup JSON")
        }
        val root = runCatching { SimpleJson.parseObject(jsonText) }.getOrNull()
            ?: return BackupImportResult(success = false, errorMessage = "Malformed JSON")

        val version = SimpleJson.asInt(root["schemaVersion"], -1)
        if (version != SCHEMA_VERSION) {
            return BackupImportResult(success = false, errorMessage = "Unsupported schemaVersion: $version")
        }

        val rootCanonical = runCatching { allowedRootDir.canonicalFile }.getOrNull() ?: allowedRootDir
        var rejectedCount = 0

        // Bluetooth settings & profiles
        val btMap = root["bluetoothSettings"] as? Map<*, *>
        val btSettings = if (btMap != null) {
            BluetoothSettings(
                autoPlayOnConnect = SimpleJson.asBoolean(btMap["autoPlayOnConnect"], false),
                autoPauseOnDisconnect = SimpleJson.asBoolean(btMap["autoPauseOnDisconnect"], true),
                defaultBtLatencyOffsetMs = SimpleJson.asLong(btMap["defaultBtLatencyOffsetMs"], -180L),
                btPreRollEnabled = SimpleJson.asBoolean(btMap["btPreRollEnabled"], true)
            )
        } else {
            btManager.getSettings()
        }

        val profiles = ArrayList<AudioDeviceProfile>()
        (root["deviceProfiles"] as? List<*>)?.forEach { item ->
            val m = item as? Map<*, *> ?: return@forEach
            val key = m["deviceKey"]?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
            val name = m["displayName"]?.toString()?.ifBlank { key } ?: key
            val type = runCatching { DeviceRouteType.valueOf(m["deviceType"]?.toString().orEmpty()) }
                .getOrDefault(DeviceRouteType.SPEAKER)
            val bands = FloatArray(TenBandBiquadEq.BAND_COUNT)
            (m["eqBandsDb"] as? List<*>)?.let { list ->
                for (i in 0 until minOf(bands.size, list.size)) {
                    bands[i] = SimpleJson.asDouble(list[i], 0.0).toFloat()
                        .coerceIn(TenBandBiquadEq.MIN_GAIN_DB, TenBandBiquadEq.MAX_GAIN_DB)
                }
            }
            profiles.add(
                AudioDeviceProfile(
                    deviceKey = key,
                    displayName = name,
                    deviceType = type,
                    eqEnabled = SimpleJson.asBoolean(m["eqEnabled"], false),
                    eqBandsDb = bands,
                    crossfeedLevel = SimpleJson.asDouble(m["crossfeedLevel"], 0.0).toFloat().coerceIn(0f, 1f),
                    safeStartVolumePercent = SimpleJson.asInt(m["safeStartVolumePercent"], -1),
                    lyricLatencyOffsetMs = SimpleJson.asLong(m["lyricLatencyOffsetMs"], 0L)
                )
            )
        }
        btManager.replaceAll(btSettings, profiles)

        // Lyric offsets
        val offsets = LinkedHashMap<String, Long>()
        (root["lyricOffsets"] as? Map<*, *>)?.forEach { (k, v) ->
            val path = k?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (isPathSafe(path, rootCanonical)) {
                offsets[path] = SimpleJson.asLong(v, 0L)
            } else {
                rejectedCount++
            }
        }
        stateStore.replaceAllLyricOffsets(offsets)

        // Pinned / Blacklisted folders
        val pinned = LinkedHashSet<String>()
        (root["pinnedFolders"] as? List<*>)?.forEach { v ->
            val p = v?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (isPathSafe(p, rootCanonical)) pinned.add(p) else rejectedCount++
        }
        val blacklisted = LinkedHashSet<String>()
        (root["blacklistedFolders"] as? List<*>)?.forEach { v ->
            val p = v?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
            if (isPathSafe(p, rootCanonical)) blacklisted.add(p) else rejectedCount++
        }
        stateStore.replaceFolders(pinned, blacklisted)

        stateStore.setMinDurationFilterEnabled(SimpleJson.asBoolean(root["minDurationFilterEnabled"], true))
        stateStore.setAmoledNightMode(SimpleJson.asBoolean(root["amoledNightMode"], false))

        (root["morningAlarm"] as? Map<*, *>)?.let { am ->
            stateStore.setMorningAlarm(
                MorningAlarmConfig(
                    enabled = SimpleJson.asBoolean(am["enabled"], false),
                    hour = SimpleJson.asInt(am["hour"], 7).coerceIn(0, 23),
                    minute = SimpleJson.asInt(am["minute"], 0).coerceIn(0, 59),
                    fadeInSeconds = SimpleJson.asInt(am["fadeInSeconds"], 30).coerceIn(5, 120)
                )
            )
        }

        (root["twsClickMappings"] as? Map<*, *>)?.let { clickMapper.importStringMap(it) }
        (root["insights"] as? Map<*, *>)?.let { stateStore.importInsightsSnapshot(it) }

        var playlistsCount = 0
        playlistOutputDir.mkdirs()
        (root["playlists"] as? List<*>)?.forEach { item ->
            val m = item as? Map<*, *> ?: return@forEach
            val rawName = m["name"]?.toString()?.trim().orEmpty()
            val safeName = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "Playlist" }
            val safeTracks = ArrayList<Track>()
            (m["trackPaths"] as? List<*>)?.forEach { rawPath ->
                val p = rawPath?.toString()?.trim().orEmpty()
                if (p.isNotEmpty() && isPathSafe(p, rootCanonical)) {
                    safeTracks.add(Track(0L, File(p).nameWithoutExtension, "", "", path = p))
                } else if (p.isNotEmpty()) {
                    rejectedCount++
                }
            }
            if (safeTracks.isNotEmpty()) {
                val outFile = File(playlistOutputDir, "$safeName.m3u8")
                LibraryScanner.writeM3u8(outFile, safeTracks, rootCanonical)
                playlistsCount++
            }
        }

        return BackupImportResult(
            success = true,
            profilesImported = profiles.size,
            playlistsImported = playlistsCount,
            rejectedPathsCount = rejectedCount
        )
    }

    private fun isPathSafe(rawPath: String, rootCanonical: File): Boolean {
        if (rawPath.contains("://") || rawPath.contains("..")) return false
        val candidate = File(rawPath)
        val candCanonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        val rootPath = rootCanonical.canonicalPath
        val targetPath = candCanonical.canonicalPath
        if (targetPath == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return targetPath.startsWith(prefix)
    }
}
