package com.laguku.player.service

import android.media.AudioDeviceInfo
import android.os.Build
import com.laguku.player.audio.TenBandBiquadEq
import com.laguku.player.data.SimpleJson
import java.io.File

enum class DeviceRouteType {
    SPEAKER,
    WIRED_USB,
    BLUETOOTH
}

data class ActiveOutputRoute(
    val deviceKey: String,
    val displayName: String,
    val type: DeviceRouteType
)

data class AudioDeviceProfile(
    val deviceKey: String,
    val displayName: String,
    val deviceType: DeviceRouteType,
    val eqEnabled: Boolean = false,
    val eqBandsDb: FloatArray = FloatArray(TenBandBiquadEq.BAND_COUNT),
    val crossfeedLevel: Float = 0f,
    val safeStartVolumePercent: Int = -1,
    val lyricLatencyOffsetMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioDeviceProfile) return false
        return deviceKey == other.deviceKey &&
            displayName == other.displayName &&
            deviceType == other.deviceType &&
            eqEnabled == other.eqEnabled &&
            eqBandsDb.contentEquals(other.eqBandsDb) &&
            crossfeedLevel == other.crossfeedLevel &&
            safeStartVolumePercent == other.safeStartVolumePercent &&
            lyricLatencyOffsetMs == other.lyricLatencyOffsetMs
    }

    override fun hashCode(): Int {
        var result = deviceKey.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + eqEnabled.hashCode()
        result = 31 * result + eqBandsDb.contentHashCode()
        result = 31 * result + crossfeedLevel.hashCode()
        result = 31 * result + safeStartVolumePercent
        result = 31 * result + lyricLatencyOffsetMs.hashCode()
        return result
    }
}

data class BluetoothSettings(
    val autoPlayOnConnect: Boolean = false,
    val autoPauseOnDisconnect: Boolean = true,
    val defaultBtLatencyOffsetMs: Long = -180L,
    val btPreRollEnabled: Boolean = true
)

/**
 * Manages per-device EQ/crossfeed/safe-volume/lyric-latency profiles keyed by
 * [AudioDeviceInfo.getProductName] with route-type fallbacks (SPEAKER / WIRED_USB / BLUETOOTH).
 */
class BluetoothProfileManager(private val storageFile: File) {

    private val lock = Any()
    private val profiles = LinkedHashMap<String, AudioDeviceProfile>()
    private var settings = BluetoothSettings()

    init {
        loadFromDisk()
    }

    fun getSettings(): BluetoothSettings = synchronized(lock) { settings }

    fun updateSettings(newSettings: BluetoothSettings) {
        synchronized(lock) {
            settings = newSettings
            saveToDiskLocked()
        }
    }

    fun saveProfile(profile: AudioDeviceProfile) {
        synchronized(lock) {
            val normalizedBands = FloatArray(TenBandBiquadEq.BAND_COUNT)
            for (i in normalizedBands.indices) {
                normalizedBands[i] = if (i < profile.eqBandsDb.size) {
                    profile.eqBandsDb[i].coerceIn(TenBandBiquadEq.MIN_GAIN_DB, TenBandBiquadEq.MAX_GAIN_DB)
                } else 0f
            }
            profiles[profile.deviceKey] = profile.copy(
                eqBandsDb = normalizedBands,
                crossfeedLevel = profile.crossfeedLevel.coerceIn(0f, 1f),
                safeStartVolumePercent = if (profile.safeStartVolumePercent in 0..100) profile.safeStartVolumePercent else -1
            )
            saveToDiskLocked()
        }
    }

    fun getProfileForRoute(route: ActiveOutputRoute): AudioDeviceProfile {
        synchronized(lock) {
            val exact = profiles[route.deviceKey]
            if (exact != null) return copyProfile(exact)

            val fallbackKey = fallbackKeyForType(route.type)
            val fallback = profiles[fallbackKey]
            if (fallback != null) {
                return copyProfile(
                    fallback.copy(
                        deviceKey = route.deviceKey,
                        displayName = route.displayName,
                        deviceType = route.type
                    )
                )
            }

            val defaultLatency = if (route.type == DeviceRouteType.BLUETOOTH) {
                settings.defaultBtLatencyOffsetMs
            } else {
                0L
            }
            return AudioDeviceProfile(
                deviceKey = route.deviceKey,
                displayName = route.displayName,
                deviceType = route.type,
                lyricLatencyOffsetMs = defaultLatency
            )
        }
    }

    fun getAllProfiles(): List<AudioDeviceProfile> = synchronized(lock) {
        profiles.values.map { copyProfile(it) }
    }

    fun replaceAll(newSettings: BluetoothSettings, newProfiles: List<AudioDeviceProfile>) {
        synchronized(lock) {
            settings = newSettings
            profiles.clear()
            for (p in newProfiles) {
                profiles[p.deviceKey] = copyProfile(p)
            }
            saveToDiskLocked()
        }
    }

    /**
     * Effective lyric latency offset for [route]: applies profile's Bluetooth latency
     * offset when output is Bluetooth, and strictly returns 0 ms on wired/speaker routes.
     */
    fun getEffectiveLyricLatencyOffsetMs(route: ActiveOutputRoute): Long {
        if (route.type != DeviceRouteType.BLUETOOTH) return 0L
        return getProfileForRoute(route).lyricLatencyOffsetMs
    }

    fun resolveOutputRoute(devices: Array<AudioDeviceInfo>): ActiveOutputRoute {
        var btRoute: ActiveOutputRoute? = null
        var wiredRoute: ActiveOutputRoute? = null

        for (info in devices) {
            if (!info.isSink) continue
            val type = info.type
            val rawName = info.productName?.toString()?.trim().orEmpty()
            when {
                type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        type == AudioDeviceInfo.TYPE_BLE_BROADCAST) -> {
                    val name = rawName.ifBlank { "Bluetooth Audio" }
                    btRoute = ActiveOutputRoute("BT:$name", name, DeviceRouteType.BLUETOOTH)
                    break
                }
                type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    type == AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                    if (wiredRoute == null) {
                        val name = rawName.ifBlank { "Wired / USB DAC" }
                        wiredRoute = ActiveOutputRoute("USB:$name", name, DeviceRouteType.WIRED_USB)
                    }
                }
            }
        }

        return btRoute ?: wiredRoute ?: ActiveOutputRoute(
            deviceKey = fallbackKeyForType(DeviceRouteType.SPEAKER),
            displayName = "Speaker",
            type = DeviceRouteType.SPEAKER
        )
    }

    private fun copyProfile(p: AudioDeviceProfile): AudioDeviceProfile {
        return p.copy(eqBandsDb = p.eqBandsDb.copyOf())
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            if (!storageFile.isFile || !storageFile.canRead()) return
            val parsed = runCatching {
                SimpleJson.parseObject(storageFile.readText(Charsets.UTF_8))
            }.getOrNull() ?: return

            @Suppress("UNCHECKED_CAST")
            val settingsMap = parsed["settings"] as? Map<String, Any?>
            if (settingsMap != null) {
                settings = BluetoothSettings(
                    autoPlayOnConnect = SimpleJson.asBoolean(settingsMap["autoPlayOnConnect"], false),
                    autoPauseOnDisconnect = SimpleJson.asBoolean(settingsMap["autoPauseOnDisconnect"], true),
                    defaultBtLatencyOffsetMs = SimpleJson.asLong(settingsMap["defaultBtLatencyOffsetMs"], -180L),
                    btPreRollEnabled = SimpleJson.asBoolean(settingsMap["btPreRollEnabled"], true)
                )
            }

            @Suppress("UNCHECKED_CAST")
            val profileList = parsed["profiles"] as? List<Any?> ?: return
            profiles.clear()
            for (item in profileList) {
                val map = item as? Map<*, *> ?: continue
                val key = map["deviceKey"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val name = map["displayName"]?.toString()?.ifBlank { key } ?: key
                val typeStr = map["deviceType"]?.toString().orEmpty()
                val type = runCatching { DeviceRouteType.valueOf(typeStr) }.getOrDefault(DeviceRouteType.SPEAKER)
                val bands = FloatArray(TenBandBiquadEq.BAND_COUNT)
                val rawBands = map["eqBandsDb"] as? List<*>
                if (rawBands != null) {
                    for (i in 0 until minOf(bands.size, rawBands.size)) {
                        bands[i] = SimpleJson.asDouble(rawBands[i], 0.0).toFloat()
                            .coerceIn(TenBandBiquadEq.MIN_GAIN_DB, TenBandBiquadEq.MAX_GAIN_DB)
                    }
                }
                profiles[key] = AudioDeviceProfile(
                    deviceKey = key,
                    displayName = name,
                    deviceType = type,
                    eqEnabled = SimpleJson.asBoolean(map["eqEnabled"], false),
                    eqBandsDb = bands,
                    crossfeedLevel = SimpleJson.asDouble(map["crossfeedLevel"], 0.0).toFloat().coerceIn(0f, 1f),
                    safeStartVolumePercent = SimpleJson.asInt(map["safeStartVolumePercent"], -1),
                    lyricLatencyOffsetMs = SimpleJson.asLong(map["lyricLatencyOffsetMs"], 0L)
                )
            }
        }
    }

    private fun saveToDiskLocked() {
        runCatching {
            storageFile.parentFile?.mkdirs()
            val root = LinkedHashMap<String, Any?>()
            root["settings"] = mapOf(
                "autoPlayOnConnect" to settings.autoPlayOnConnect,
                "autoPauseOnDisconnect" to settings.autoPauseOnDisconnect,
                "defaultBtLatencyOffsetMs" to settings.defaultBtLatencyOffsetMs,
                "btPreRollEnabled" to settings.btPreRollEnabled
            )
            root["profiles"] = profiles.values.map { p ->
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
            val tmp = File(storageFile.parentFile, "${storageFile.name}.tmp")
            tmp.writeText(SimpleJson.stringify(root), Charsets.UTF_8)
            if (storageFile.exists()) storageFile.delete()
            if (!tmp.renameTo(storageFile)) {
                storageFile.writeText(SimpleJson.stringify(root), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    companion object {
        fun fallbackKeyForType(type: DeviceRouteType): String = "TYPE:${type.name}"

        fun routeKeyForDeviceName(type: DeviceRouteType, productName: String): String {
            val clean = productName.trim()
            return when (type) {
                DeviceRouteType.BLUETOOTH -> if (clean.isNotEmpty()) "BT:$clean" else fallbackKeyForType(type)
                DeviceRouteType.WIRED_USB -> if (clean.isNotEmpty()) "USB:$clean" else fallbackKeyForType(type)
                DeviceRouteType.SPEAKER -> fallbackKeyForType(DeviceRouteType.SPEAKER)
            }
        }
    }
}
