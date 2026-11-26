package com.laguku.player.model

data class AudioQualityInfo(
    val codec: String = "UNKNOWN",
    val bitDepth: Int = 16,
    val sampleRateHz: Int = 44100,
    val bitrateKbps: Int = 0
) {
    fun formatBadge(): String {
        val label = codec.trim().uppercase().ifEmpty { "AUDIO" }
        val isLossless = label in LOSSLESS_CODECS || bitrateKbps <= 0
        if (!isLossless && bitrateKbps > 0) {
            return "$label \u2022 $bitrateKbps kbps"
        }
        val parts = ArrayList<String>(3)
        parts.add(label)
        if (bitDepth > 0) {
            parts.add("$bitDepth-bit")
        }
        val rate = formatSampleRate(sampleRateHz)
        if (rate.isNotEmpty()) {
            parts.add(rate)
        }
        return parts.joinToString(" \u2022 ")
    }

    private fun formatSampleRate(hz: Int): String {
        if (hz <= 0) return ""
        val khz = hz / 1000
        val rem = hz % 1000
        if (rem == 0) return "$khz kHz"
        val tenths = (rem + 50) / 100
        return if (tenths >= 10) "${khz + 1} kHz" else "$khz.$tenths kHz"
    }

    private companion object {
        val LOSSLESS_CODECS = setOf("FLAC", "ALAC", "WAV", "PCM", "AIFF", "DSD")
    }
}

enum class LosslessAuditStatus { NOT_APPLICABLE, PENDING, GENUINE_LOSSLESS, SUSPECTED_UPSCALE }

data class ChapterMarker(val startMs: Long, val title: String)

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0L,
    val trackNumber: Int = 0,
    val durationMs: Long = 0L,
    val path: String,
    val contentUri: String = "",
    val folderPath: String = "",
    val replayGainDb: Float? = null,
    val quality: AudioQualityInfo = AudioQualityInfo(),
    val dateAddedSec: Long = 0L,
    val chapters: List<ChapterMarker> = emptyList()
)

data class AlbumGroup(val id: Long, val name: String, val artist: String, val tracks: List<Track>)
data class FolderNode(val path: String, val name: String, val tracks: List<Track>)
data class ArtistGroup(val name: String, val tracks: List<Track>)
data class Playlist(val name: String, val filePath: String, val tracks: List<Track>)
data class WordToken(val startMs: Long, val text: String)

enum class LyricSource(val label: String) {
    NONE(""),
    EMBEDDED_TAG("Tag Audio"),
    SIDECAR_LRC("File .lrc"),
    INTERNAL_FALLBACK("Lokal")
}

data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val words: List<WordToken> = emptyList(),
    val translation: String? = null,
    val romanizedText: String? = null
) {
    val primaryText: String get() = text
    val translatedText: String? get() = translation
}

data class ParsedLyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val offsetMs: Long = 0L,
    val source: LyricSource = LyricSource.NONE
)
data class CoverPalette(val deepBackdrop: Int, val accentGlow: Int, val glassTint: Int)

data class AudioDspConfig(
    val eqEnabled: Boolean = false,
    val eqBandsDb: FloatArray = FloatArray(10),
    val crossfeedLevel: Float = 0f,
    val smartLoudnessEnabled: Boolean = false,
    val autoTrimSilenceEnabled: Boolean = false,
    val balance: Float = 0f,
    val monoDownmix: Boolean = false,
    val shuffleCrossfadeEnabled: Boolean = false,
    val btPreRollEnabled: Boolean = true,
    val passthroughSampleRateEnabled: Boolean = true,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioDspConfig) return false
        return eqEnabled == other.eqEnabled &&
            eqBandsDb.contentEquals(other.eqBandsDb) &&
            crossfeedLevel == other.crossfeedLevel &&
            smartLoudnessEnabled == other.smartLoudnessEnabled &&
            autoTrimSilenceEnabled == other.autoTrimSilenceEnabled &&
            balance == other.balance &&
            monoDownmix == other.monoDownmix &&
            shuffleCrossfadeEnabled == other.shuffleCrossfadeEnabled &&
            btPreRollEnabled == other.btPreRollEnabled &&
            passthroughSampleRateEnabled == other.passthroughSampleRateEnabled &&
            speed == other.speed &&
            pitch == other.pitch
    }

    override fun hashCode(): Int {
        var result = eqEnabled.hashCode()
        result = 31 * result + eqBandsDb.contentHashCode()
        result = 31 * result + crossfeedLevel.hashCode()
        result = 31 * result + smartLoudnessEnabled.hashCode()
        result = 31 * result + autoTrimSilenceEnabled.hashCode()
        result = 31 * result + balance.hashCode()
        result = 31 * result + monoDownmix.hashCode()
        result = 31 * result + shuffleCrossfadeEnabled.hashCode()
        result = 31 * result + btPreRollEnabled.hashCode()
        result = 31 * result + passthroughSampleRateEnabled.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + pitch.hashCode()
        return result
    }
}

data class TagMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val replayGainDb: Float? = null,
    val lyrics: String? = null,
    val artworkBytes: ByteArray? = null,
    val chapters: List<ChapterMarker> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TagMetadata) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            trackNumber == other.trackNumber &&
            replayGainDb == other.replayGainDb &&
            lyrics == other.lyrics &&
            artworkBytes.contentEquals(other.artworkBytes)
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (trackNumber ?: 0)
        result = 31 * result + (replayGainDb?.hashCode() ?: 0)
        result = 31 * result + (lyrics?.hashCode() ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        return result
    }
}

enum class RepeatMode { OFF, ALL, ONE }
