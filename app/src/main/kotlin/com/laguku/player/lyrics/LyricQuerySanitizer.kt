package com.laguku.player.lyrics

data class CleanLyricQuery(
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int
)

/**
 * Cleans track metadata and downloader filenames into accurate search terms for LRCLIB.
 */
object LyricQuerySanitizer {

    private val EXTENSION_REGEX = Regex("""\.(mp3|flac|m4a|ogg|opus|wav|aac|alac|wma)$""", RegexOption.IGNORE_CASE)
    private val BRACKET_NOISE_REGEX = Regex(
        """[\(\[]\s*(?:official\s*(?:music\s*)?(?:video|audio|lyric\s*video|lyrics?|visualizer|mv)|lyrics?|lyric\s*video|audio|visualizer|hd|4k|hq|mv|\d{2,3}\s*kbps|flac|mp3|explicit|clean|remastered(?:\s*\d{4})?)\s*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val BITRATE_TAG_REGEX = Regex("""\b\d{2,3}\s*kbps\b""", RegexOption.IGNORE_CASE)
    private val FEAT_BRACKET_REGEX = Regex("""[\(\[]\s*(?:feat\.?|ft\.?|featuring)\s+[^\)\]]+[\)\]]""", RegexOption.IGNORE_CASE)
    private val FEAT_INLINE_REGEX = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.+$""", RegexOption.IGNORE_CASE)
    private val CHANNEL_SUFFIX_REGEX = Regex("""\s*(?:-\s*topic|\bvevo\b|\bofficial\b)$""", RegexOption.IGNORE_CASE)
    private val LEADING_TRACK_NUM_REGEX = Regex("""^\d{1,3}\s*[.\-)]\s+""")
    private val MULTI_SPACE_REGEX = Regex("""\s{2,}""")

    private val UNKNOWN_ARTISTS = setOf(
        "", "<unknown>", "unknown", "unknown artist", "artis tidak diketahui", "various artists"
    )

    fun sanitize(
        rawTitle: String,
        rawArtist: String,
        rawAlbum: String = "",
        durationMs: Long = 0L,
        fileNameFallback: String = ""
    ): CleanLyricQuery {
        val baseTitle = rawTitle.trim().ifBlank { fileNameFallback.trim() }
        var cleanTitle = stripNoise(baseTitle, stripChannel = false)
            .replace(LEADING_TRACK_NUM_REGEX, "")
            .trim()
        var cleanArtist = stripNoise(rawArtist, stripChannel = true)

        if (cleanArtist.lowercase() in UNKNOWN_ARTISTS) {
            cleanArtist = ""
        }

        if (cleanArtist.isEmpty() && cleanTitle.contains(" - ")) {
            val parts = cleanTitle.split(" - ", limit = 2)
            val candidateArtist = stripNoise(parts[0], stripChannel = true)
            val candidateTitle = stripNoise(parts[1], stripChannel = false)
            if (candidateArtist.isNotEmpty() && candidateTitle.isNotEmpty()) {
                cleanArtist = candidateArtist
                cleanTitle = candidateTitle
            }
        }

        val cleanAlbum = stripNoise(rawAlbum, stripChannel = false)
        val durationSec = if (durationMs > 0L) ((durationMs + 500L) / 1000L).toInt().coerceAtLeast(1) else 0

        return CleanLyricQuery(
            title = cleanTitle,
            artist = cleanArtist,
            album = cleanAlbum,
            durationSec = durationSec
        )
    }

    private fun stripNoise(input: String, stripChannel: Boolean): String {
        if (input.isBlank()) return ""
        var s = input.trim()
        s = EXTENSION_REGEX.replace(s, "")
        s = s.replace('_', ' ')
        s = BRACKET_NOISE_REGEX.replace(s, " ")
        s = FEAT_BRACKET_REGEX.replace(s, " ")
        s = FEAT_INLINE_REGEX.replace(s, "")
        s = BITRATE_TAG_REGEX.replace(s, " ")
        if (stripChannel) {
            s = CHANNEL_SUFFIX_REGEX.replace(s, "")
        }
        s = MULTI_SPACE_REGEX.replace(s, " ").trim()
        return s.trim('-', ' ')
    }
}
