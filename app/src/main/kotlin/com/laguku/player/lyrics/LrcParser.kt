package com.laguku.player.lyrics

import android.util.Xml
import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.model.LyricLine
import com.laguku.player.model.LyricSource
import com.laguku.player.model.ParsedLyrics
import com.laguku.player.model.WordTiming
import com.laguku.player.model.WordToken
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.roundToLong

/**
 * Parser for standard, multi-timestamp, [offset:], and karaoke (<mm:ss.xx>) LRC lyrics,
 * with static text fallback and O(log N) active-line binary search.
 */
object LrcParser {

    private val LINE_TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIMESTAMP_REGEX = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?>""")
    private val OFFSET_TAG_REGEX = Regex("""^\[offset\s*:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)
    private val METADATA_TAG_REGEX = Regex("""^\[[a-zA-Z]+\s*:.*]$""")

    /**
     * Parses raw LRC or plain lyrics text.
     *
     * Offset semantics follow standard LRC: a positive `[offset:+N]` shifts lyrics earlier
     * by subtracting `N` milliseconds from each timestamp (clamped to >= 0 ms); a negative
     * `[offset:-N]` delays lyrics by adding `N` milliseconds.
     */
    fun parse(raw: String): ParsedLyrics {
        if (raw.isBlank()) {
            return ParsedLyrics(lines = emptyList(), isSynced = false, offsetMs = 0L)
        }
        val trimmedStart = raw.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (trimmedStart.startsWith("<?xml", ignoreCase = true) ||
            trimmedStart.startsWith("<tt", ignoreCase = true)
        ) {
            return parseTtml(raw)
        }

        val normalizedLines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var offsetMs = 0L
        for (line in normalizedLines) {
            val trimmed = line.trim()
            val offsetMatch = OFFSET_TAG_REGEX.matchEntire(trimmed)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
            }
        }

        val syncedLines = ArrayList<LyricLine>()
        val fallbackStaticLines = ArrayList<LyricLine>()

        for (rawLine in normalizedLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val timestampMatches = LINE_TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (timestampMatches.isNotEmpty()) {
                val timestamps = ArrayList<Long>(timestampMatches.size)
                var lastMatchEnd = 0
                for (match in timestampMatches) {
                    if (match.range.first > lastMatchEnd &&
                        trimmed.substring(lastMatchEnd, match.range.first).isNotBlank()
                    ) {
                        // Ignore inline timestamps embedded in the middle of lyric text here.
                        break
                    }
                    val rawTs = parseTimestampMs(
                        minStr = match.groupValues[1],
                        secStr = match.groupValues[2],
                        fracStr = match.groupValues[3]
                    )
                    timestamps.add((rawTs - offsetMs).coerceAtLeast(0L))
                    lastMatchEnd = match.range.last + 1
                }

                if (timestamps.isNotEmpty()) {
                    val body = trimmed.substring(lastMatchEnd).trim()
                    val (cleanText, wordTokens) = parseKaraokeBody(body, timestamps[0], offsetMs)
                    for (ts in timestamps) {
                        val lineWords = if (wordTokens.isEmpty() || ts == timestamps[0]) {
                            wordTokens
                        } else {
                            // Shift word tokens relative to repeated timestamp for multi-timestamp lines.
                            val delta = ts - timestamps[0]
                            wordTokens.map { it.copy(startMs = (it.startMs + delta).coerceAtLeast(0L)) }
                        }
                        syncedLines.add(LyricLine(timestampMs = ts, text = cleanText, words = lineWords))
                    }
                    continue
                }
            }

            if (!METADATA_TAG_REGEX.matches(trimmed)) {
                fallbackStaticLines.add(LyricLine(timestampMs = 0L, text = trimmed))
            }
        }

        return if (syncedLines.isNotEmpty()) {
            syncedLines.sortBy { it.timestampMs }
            val merged = mergeMultiLayerLines(syncedLines)
            ParsedLyrics(lines = merged, isSynced = true, offsetMs = offsetMs)
        } else {
            ParsedLyrics(lines = fallbackStaticLines, isSynced = false, offsetMs = offsetMs)
        }
    }

    /**
     * Parses TTML timed-text lyrics including `<p begin="..." end="...">` and
     * word/syllable `<span begin="..." end="...">` timings. Uses Android's
     * [XmlPullParser] at runtime with a pure-Kotlin pull fallback for JVM unit tests.
     */
    fun parseTtml(raw: String): ParsedLyrics {
        if (raw.isBlank()) {
            return ParsedLyrics(lines = emptyList(), isSynced = false, offsetMs = 0L)
        }
        if (raw.length > MAX_LRC_BYTES) {
            return ParsedLyrics(lines = emptyList(), isSynced = false, offsetMs = 0L)
        }
        val builder = TtmlBuilder()
        val platformParser = runCatching { Xml.newPullParser() }.getOrNull()
        if (platformParser != null) {
            runCatching {
                platformParser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                platformParser.setInput(StringReader(raw.removePrefix("\uFEFF")))
                var event = platformParser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            val tag = localTagName(platformParser.name)
                            builder.onStartTag(tag) { attrName ->
                                getAttrIgnoreNamespace(platformParser, attrName)
                            }
                        }
                        XmlPullParser.TEXT -> builder.onText(platformParser.text.orEmpty())
                        XmlPullParser.END_TAG -> builder.onEndTag(localTagName(platformParser.name))
                    }
                    event = platformParser.next()
                }
            }
        } else {
            runLightweightXmlPull(raw.removePrefix("\uFEFF"), builder)
        }
        return builder.build()
    }

    /**
     * Loads lyrics with pipeline priority:
     * 1) Sidecar `.ttml` next to the audio file
     * 2) Sidecar `.lrc` next to the audio file
     * 3) App internal fallback `.lrc` (when [internalFallbackDir] is provided)
     * 4) Embedded FLAC/MP3 lyrics read via streaming header parser
     */
    fun loadTrackLyrics(
        audioFile: File,
        internalFallbackDir: File? = null
    ): ParsedLyrics {
        val sidecarTtml = BinaryTagReader.readSidecarTtml(audioFile)
        if (!sidecarTtml.isNullOrBlank()) {
            val parsedTtml = parseTtml(sidecarTtml)
            if (parsedTtml.lines.isNotEmpty()) {
                return parsedTtml.copy(source = LyricSource.SIDECAR_TTML)
            }
        }

        val sidecarRaw = BinaryTagReader.readSidecarLrc(audioFile)
        if (!sidecarRaw.isNullOrBlank()) {
            val sidecarParsed = parse(sidecarRaw)
            if (sidecarParsed.lines.isNotEmpty()) {
                return sidecarParsed.copy(source = LyricSource.SIDECAR_LRC)
            }
        }

        if (internalFallbackDir != null) {
            val internalRaw = loadFallbackLrc(audioFile, internalFallbackDir)
            if (!internalRaw.isNullOrBlank()) {
                val internalParsed = parse(internalRaw)
                if (internalParsed.lines.isNotEmpty()) {
                    return internalParsed.copy(source = LyricSource.INTERNAL_FALLBACK)
                }
            }
        }

        val streamedRaw = BinaryTagReader.readEmbeddedLyricsStreaming(audioFile)
            ?: BinaryTagReader.readEmbedded(audioFile).lyrics
        if (!streamedRaw.isNullOrBlank()) {
            val embeddedParsed = parse(streamedRaw)
            if (embeddedParsed.lines.isNotEmpty()) {
                return embeddedParsed.copy(source = LyricSource.EMBEDDED_TAG)
            }
        }

        return ParsedLyrics(lines = emptyList(), isSynced = false, offsetMs = 0L, source = LyricSource.NONE)
    }

    /**
     * Loads lyrics in strict priority order:
     * 1) Sidecar `.ttml` when present (if no embedded synced override is requested)
     * 2) Synced lyrics embedded in audio binary tags ([LyricSource.EMBEDDED_TAG])
     * 3) Local sidecar `.lrc` next to the audio file ([LyricSource.SIDECAR_LRC], mandatory fallback for WAV)
     * 4) App internal fallback `.lrc` ([LyricSource.INTERNAL_FALLBACK])
     * 5) Embedded unsynced plain-text lyrics ([LyricSource.EMBEDDED_TAG])
     */
    fun loadLyrics(
        audioFile: File,
        internalFallbackDir: File,
        embeddedLyrics: String? = BinaryTagReader.readEmbeddedLyricsStreaming(audioFile)
            ?: BinaryTagReader.readEmbedded(audioFile).lyrics
    ): ParsedLyrics {
        val sidecarTtml = BinaryTagReader.readSidecarTtml(audioFile)
        if (!sidecarTtml.isNullOrBlank()) {
            val ttmlParsed = parseTtml(sidecarTtml)
            if (ttmlParsed.lines.isNotEmpty()) {
                return ttmlParsed.copy(source = LyricSource.SIDECAR_TTML)
            }
        }

        val embeddedParsed = embeddedLyrics?.takeIf { it.isNotBlank() }?.let { parse(it) }
        if (embeddedParsed != null && embeddedParsed.isSynced && embeddedParsed.lines.isNotEmpty()) {
            return embeddedParsed.copy(source = LyricSource.EMBEDDED_TAG)
        }

        val sidecarRaw = BinaryTagReader.readSidecarLrc(audioFile)
        if (!sidecarRaw.isNullOrBlank()) {
            val sidecarParsed = parse(sidecarRaw)
            if (sidecarParsed.lines.isNotEmpty()) {
                return sidecarParsed.copy(source = LyricSource.SIDECAR_LRC)
            }
        }

        val internalRaw = loadFallbackLrc(audioFile, internalFallbackDir)
        if (!internalRaw.isNullOrBlank()) {
            val internalParsed = parse(internalRaw)
            if (internalParsed.lines.isNotEmpty()) {
                return internalParsed.copy(source = LyricSource.INTERNAL_FALLBACK)
            }
        }

        if (embeddedParsed != null && embeddedParsed.lines.isNotEmpty()) {
            return embeddedParsed.copy(source = LyricSource.EMBEDDED_TAG)
        }

        return ParsedLyrics(lines = emptyList(), isSynced = false, offsetMs = 0L, source = LyricSource.NONE)
    }

    private fun mergeMultiLayerLines(sorted: List<LyricLine>): List<LyricLine> {
        if (sorted.size < 2) return sorted
        val out = ArrayList<LyricLine>(sorted.size)
        var groupCount = 0
        for (line in sorted) {
            val prev = out.lastOrNull()
            if (prev != null &&
                prev.timestampMs == line.timestampMs &&
                prev.text.isNotBlank() &&
                line.text.isNotBlank() &&
                groupCount < 3
            ) {
                if (groupCount == 1) {
                    out[out.lastIndex] = prev.copy(translation = line.text)
                    groupCount = 2
                } else {
                    out[out.lastIndex] = prev.copy(romanizedText = prev.translation, translation = line.text)
                    groupCount = 3
                }
            } else {
                out.add(line)
                groupCount = 1
            }
        }
        return out
    }

    /**
     * Finds the active lyric line index for [positionMs] in O(log N) time using binary search.
     * Assumes [lines] is sorted in ascending order of [LyricLine.timestampMs].
     * Returns -1 if [lines] is empty or [positionMs] precedes the first line timestamp.
     */
    fun findActiveLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty() || positionMs < lines[0].timestampMs) return -1

        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestampMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    fun findActiveLineIndex(
        lines: List<LyricLine>,
        positionMs: Long,
        userOffsetMs: Long,
        bluetoothLatencyOffsetMs: Long
    ): Int {
        val effectivePos = (positionMs + userOffsetMs + bluetoothLatencyOffsetMs).coerceAtLeast(0L)
        return findActiveLineIndex(lines, effectivePos)
    }

    /**
     * Safely writes or updates the `[offset:+/-N]` tag in a sidecar `.lrc` file,
     * validating [canonicalPath] and real symlink path remain inside [allowedRootDir].
     */
    fun writeOffsetToLrcFile(lrcFile: File, offsetMs: Long, allowedRootDir: File): Boolean {
        val rootCanonical = runCatching { allowedRootDir.canonicalFile }.getOrNull() ?: return false
        val targetCanonical = runCatching { lrcFile.canonicalFile }.getOrNull() ?: return false
        val rootReal = runCatching { rootCanonical.toPath().toRealPath() }.getOrNull() ?: rootCanonical.toPath()
        val targetReal = resolveRealPathSafe(targetCanonical) ?: return false
        if (!isWithinRoot(targetCanonical, rootCanonical) || !targetReal.normalize().startsWith(rootReal.normalize())) {
            return false
        }
        if (!targetCanonical.isFile || !targetCanonical.canRead()) return false

        return runCatching {
            val original = targetCanonical.readText(Charsets.UTF_8)
            val lineSep = if (original.contains("\r\n")) "\r\n" else "\n"
            val lines = original.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
            val sign = if (offsetMs >= 0L) "+" else ""
            val tag = "[offset:$sign$offsetMs]"
            var replaced = false
            for (i in lines.indices) {
                if (OFFSET_TAG_REGEX.matches(lines[i].trim())) {
                    lines[i] = tag
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                lines.add(0, tag)
            }
            val tmp = File(targetCanonical.parentFile, "${targetCanonical.name}.tmp")
            tmp.writeText(lines.joinToString(lineSep), Charsets.UTF_8)
            if (targetCanonical.exists()) targetCanonical.delete()
            if (!tmp.renameTo(targetCanonical)) {
                targetCanonical.writeText(lines.joinToString(lineSep), Charsets.UTF_8)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Saves [lrcContent] to the sidecar `<audioName>.lrc` inside [allowedRootDir],
     * falling back to [internalFallbackDir] if the sidecar path is read-only or outside root.
     */
    fun saveLrcFileSafely(
        audioFile: File,
        lrcContent: String,
        allowedRootDir: File,
        internalFallbackDir: File
    ): File? {
        if (lrcContent.isBlank()) return null
        val bytes = lrcContent.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_LRC_BYTES) return null

        val parentDir = audioFile.parentFile
        if (parentDir != null) {
            val sidecar = File(parentDir, "${audioFile.nameWithoutExtension}.lrc")
            if (writeInsideValidatedRoot(sidecar, bytes, allowedRootDir)) {
                return sidecar
            }
        }

        internalFallbackDir.mkdirs()
        val fallbackFile = File(internalFallbackDir, fallbackFileName(audioFile))
        return if (writeInsideValidatedRoot(fallbackFile, bytes, internalFallbackDir)) {
            fallbackFile
        } else {
            null
        }
    }

    fun loadFallbackLrc(audioFile: File, internalFallbackDir: File): String? {
        val fallbackFile = File(internalFallbackDir, fallbackFileName(audioFile))
        val rootCanonical = runCatching { internalFallbackDir.canonicalFile }.getOrNull() ?: return null
        val targetCanonical = runCatching { fallbackFile.canonicalFile }.getOrNull() ?: return null
        if (!isWithinRoot(targetCanonical, rootCanonical)) return null
        if (!targetCanonical.isFile || !targetCanonical.canRead()) return null
        if (targetCanonical.length() !in 1L..MAX_LRC_BYTES.toLong()) return null
        return runCatching {
            targetCanonical.readText(Charsets.UTF_8).removePrefix("\uFEFF").ifBlank { null }
        }.getOrNull()
    }

    private fun writeInsideValidatedRoot(targetFile: File, bytes: ByteArray, allowedRootDir: File): Boolean {
        val rootCanonical = runCatching { allowedRootDir.canonicalFile }.getOrNull() ?: return false
        val targetCanonical = runCatching { targetFile.canonicalFile }.getOrNull() ?: return false
        if (!isWithinRoot(targetCanonical, rootCanonical)) return false

        val parentCanonical = targetCanonical.parentFile ?: return false
        val rootReal = runCatching { rootCanonical.toPath().toRealPath() }.getOrNull() ?: return false
        val parentReal = resolveRealPathSafe(parentCanonical) ?: return false
        if (!parentReal.normalize().startsWith(rootReal.normalize())) return false

        val targetPath = targetCanonical.toPath()
        if (Files.isSymbolicLink(targetPath)) return false

        return runCatching {
            val tmp = File(parentCanonical, "${targetCanonical.name}.tmp")
            tmp.writeBytes(bytes)
            if (targetCanonical.exists()) targetCanonical.delete()
            if (!tmp.renameTo(targetCanonical)) {
                targetCanonical.writeBytes(bytes)
                tmp.delete()
            }
            targetCanonical.isFile && targetCanonical.canRead()
        }.getOrDefault(false)
    }

    private fun fallbackFileName(audioFile: File): String {
        val key = runCatching { audioFile.canonicalPath }.getOrDefault(audioFile.absolutePath)
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX_CHARS[v ushr 4])
            hex.append(HEX_CHARS[v and 0x0F])
        }
        return "$hex.lrc"
    }

    private const val MAX_LRC_BYTES = 512 * 1024
    private const val HEX_CHARS = "0123456789abcdef"

    private fun resolveRealPathSafe(file: File): Path? {
        val p = file.toPath()
        if (Files.isSymbolicLink(p)) return null
        return if (Files.exists(p)) runCatching { p.toRealPath() }.getOrNull() else null
    }

    private fun isWithinRoot(targetCanonical: File, rootCanonical: File): Boolean {
        val targetPath = targetCanonical.canonicalPath
        val rootPath = rootCanonical.canonicalPath
        if (targetPath == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return targetPath.startsWith(prefix)
    }

    private fun parseKaraokeBody(
        body: String,
        lineTimestampMs: Long,
        offsetMs: Long
    ): Pair<String, List<WordToken>> {
        val wordMatches = WORD_TIMESTAMP_REGEX.findAll(body).toList()
        if (wordMatches.isEmpty()) {
            return body to emptyList()
        }

        val tokens = ArrayList<WordToken>(wordMatches.size + 1)
        val cleanBuilder = StringBuilder(body.length)

        val firstStart = wordMatches[0].range.first
        if (firstStart > 0) {
            val prefix = body.substring(0, firstStart)
            cleanBuilder.append(prefix)
            if (prefix.isNotEmpty()) {
                tokens.add(WordToken(startMs = lineTimestampMs, text = prefix))
            }
        }

        for (i in wordMatches.indices) {
            val match = wordMatches[i]
            val rawWordMs = parseTimestampMs(
                minStr = match.groupValues[1],
                secStr = match.groupValues[2],
                fracStr = match.groupValues[3]
            )
            val wordMs = (rawWordMs - offsetMs).coerceAtLeast(0L)
            val segStart = match.range.last + 1
            val segEnd = if (i + 1 < wordMatches.size) wordMatches[i + 1].range.first else body.length
            val segment = body.substring(segStart, segEnd)
            cleanBuilder.append(segment)
            if (segment.isNotEmpty()) {
                tokens.add(WordToken(startMs = wordMs, text = segment))
            }
        }

        return cleanBuilder.toString().trim() to tokens
    }

    private fun parseTimestampMs(minStr: String, secStr: String, fracStr: String): Long {
        val minutes = minStr.toLongOrNull() ?: 0L
        val seconds = secStr.toLongOrNull() ?: 0L
        val millis = when (fracStr.length) {
            0 -> 0L
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            3 -> fracStr.toLongOrNull() ?: 0L
            else -> fracStr.substring(0, 3).toLongOrNull() ?: 0L
        }
        return (minutes * 60_000L) + (seconds * 1_000L) + millis
    }

    private fun localTagName(rawName: String?): String {
        val name = rawName?.trim().orEmpty()
        val colon = name.indexOf(':')
        return (if (colon >= 0) name.substring(colon + 1) else name).lowercase()
    }

    private fun getAttrIgnoreNamespace(parser: XmlPullParser, localKey: String): String? {
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i) ?: continue
            if (attrName.equals(localKey, ignoreCase = true) ||
                attrName.endsWith(":$localKey", ignoreCase = true)
            ) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    internal fun parseTtmlTimestampMs(raw: String?, tickRate: Long = 1000L): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.endsWith("ms", ignoreCase = true)) {
            return s.dropLast(2).toDoubleOrNull()?.roundToLong()?.coerceAtLeast(0L)
        }
        if (s.endsWith("s", ignoreCase = true)) {
            return (s.dropLast(1).toDoubleOrNull()?.times(1000.0))?.roundToLong()?.coerceAtLeast(0L)
        }
        if (s.endsWith("m", ignoreCase = true)) {
            return (s.dropLast(1).toDoubleOrNull()?.times(60_000.0))?.roundToLong()?.coerceAtLeast(0L)
        }
        if (s.endsWith("t", ignoreCase = true)) {
            val ticks = s.dropLast(1).toDoubleOrNull() ?: return null
            val rate = tickRate.coerceAtLeast(1L).toDouble()
            return (ticks * 1000.0 / rate).roundToLong().coerceAtLeast(0L)
        }
        val parts = s.split(':')
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: return null
                val m = parts[1].toLongOrNull() ?: return null
                val secMs = parseSecondsFractionMs(parts[2]) ?: return null
                (h * 3_600_000L + m * 60_000L + secMs).coerceAtLeast(0L)
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: return null
                val secMs = parseSecondsFractionMs(parts[1]) ?: return null
                (m * 60_000L + secMs).coerceAtLeast(0L)
            }
            1 -> parseSecondsFractionMs(parts[0])
            else -> null
        }
    }

    private fun parseSecondsFractionMs(token: String): Long? {
        val clean = token.trim()
        val dot = clean.indexOf('.')
        return if (dot >= 0) {
            val sec = clean.substring(0, dot).toLongOrNull() ?: return null
            val frac = clean.substring(dot + 1)
            val ms = when (frac.length) {
                0 -> 0L
                1 -> (frac.toLongOrNull() ?: 0L) * 100L
                2 -> (frac.toLongOrNull() ?: 0L) * 10L
                3 -> frac.toLongOrNull() ?: 0L
                else -> frac.substring(0, 3).toLongOrNull() ?: 0L
            }
            sec * 1_000L + ms
        } else {
            val sec = clean.toLongOrNull() ?: return null
            sec * 1_000L
        }
    }

    private fun decodeXmlEntities(input: String): String {
        if (!input.contains('&')) return input
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] == '&') {
                val semi = input.indexOf(';', i + 1)
                if (semi in (i + 2)..(i + 12)) {
                    val entity = input.substring(i + 1, semi)
                    val decoded = when {
                        entity == "amp" -> "&"
                        entity == "lt" -> "<"
                        entity == "gt" -> ">"
                        entity == "quot" -> "\""
                        entity == "apos" -> "'"
                        entity.startsWith("#x", ignoreCase = true) ->
                            entity.substring(2).toIntOrNull(16)?.toChar()?.toString()
                        entity.startsWith("#") ->
                            entity.substring(1).toIntOrNull()?.toChar()?.toString()
                        else -> null
                    }
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }

    private fun runLightweightXmlPull(xml: String, builder: TtmlBuilder) {
        val n = xml.length
        var i = 0
        while (i < n) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) {
                if (i < n) builder.onText(decodeXmlEntities(xml.substring(i)))
                break
            }
            if (lt > i) {
                builder.onText(decodeXmlEntities(xml.substring(i, lt)))
            }
            if (xml.startsWith("<!--", lt)) {
                val endComment = xml.indexOf("-->", lt + 4)
                i = if (endComment >= 0) endComment + 3 else n
                continue
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                val endCdata = xml.indexOf("]]>", lt + 9)
                if (endCdata >= 0) {
                    builder.onText(xml.substring(lt + 9, endCdata))
                    i = endCdata + 3
                } else {
                    i = n
                }
                continue
            }
            if (xml.startsWith("<?", lt) || xml.startsWith("<!", lt)) {
                val gt = xml.indexOf('>', lt + 2)
                i = if (gt >= 0) gt + 1 else n
                continue
            }
            val gt = findTagCloseOutsideQuotes(xml, lt + 1)
            if (gt < 0) break
            val tagBody = xml.substring(lt + 1, gt).trim()
            i = gt + 1
            if (tagBody.isEmpty()) continue

            if (tagBody.startsWith("/")) {
                builder.onEndTag(localTagName(tagBody.substring(1)))
            } else {
                val selfClosing = tagBody.endsWith("/")
                val cleanBody = if (selfClosing) tagBody.dropLast(1).trim() else tagBody
                val nameEnd = cleanBody.indexOfFirst { it.isWhitespace() }
                val rawTagName = if (nameEnd < 0) cleanBody else cleanBody.substring(0, nameEnd)
                val attrText = if (nameEnd < 0) "" else cleanBody.substring(nameEnd + 1)
                val tag = localTagName(rawTagName)
                builder.onStartTag(tag) { key -> extractXmlAttribute(attrText, key) }
                if (selfClosing) {
                    builder.onEndTag(tag)
                }
            }
        }
    }

    private fun findTagCloseOutsideQuotes(xml: String, start: Int): Int {
        var quote: Char = '\u0000'
        for (idx in start until xml.length) {
            val ch = xml[idx]
            if (quote == '\u0000') {
                if (ch == '"' || ch == '\'') quote = ch else if (ch == '>') return idx
            } else if (ch == quote) {
                quote = '\u0000'
            }
        }
        return -1
    }

    private fun extractXmlAttribute(attrText: String, localKey: String): String? {
        if (attrText.isEmpty()) return null
        var idx = 0
        val len = attrText.length
        while (idx < len) {
            while (idx < len && attrText[idx].isWhitespace()) idx++
            if (idx >= len) break
            val eq = attrText.indexOf('=', idx)
            if (eq < 0) break
            val rawName = attrText.substring(idx, eq).trim()
            var vStart = eq + 1
            while (vStart < len && attrText[vStart].isWhitespace()) vStart++
            if (vStart >= len) break
            val quote = attrText[vStart]
            val value: String
            if (quote == '"' || quote == '\'') {
                val vEnd = attrText.indexOf(quote, vStart + 1)
                if (vEnd < 0) break
                value = attrText.substring(vStart + 1, vEnd)
                idx = vEnd + 1
            } else {
                val vEnd = attrText.indexOfFirstFrom(vStart) { it.isWhitespace() }
                value = if (vEnd < 0) attrText.substring(vStart) else attrText.substring(vStart, vEnd)
                idx = if (vEnd < 0) len else vEnd + 1
            }
            if (rawName.equals(localKey, ignoreCase = true) ||
                rawName.endsWith(":$localKey", ignoreCase = true)
            ) {
                return decodeXmlEntities(value)
            }
        }
        return null
    }

    private inline fun String.indexOfFirstFrom(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in startIndex until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    private class TtmlBuilder {
        private var tickRate: Long = 1000L
        private val outLines = ArrayList<LyricLine>()

        private var inParagraph = false
        private var pBeginMs: Long? = null
        private var pEndMs: Long? = null
        private var pRole: String? = null
        private val pPrimaryBuf = StringBuilder()
        private val pTranslationBuf = StringBuilder()
        private val pRomanBuf = StringBuilder()
        private val pWords = ArrayList<WordTiming>()
        private val pendingGap = StringBuilder()

        private var inSpan = false
        private var spanBeginMs: Long? = null
        private var spanEndMs: Long? = null
        private var spanRole: String? = null
        private val spanBuf = StringBuilder()

        fun onStartTag(tag: String, attr: (String) -> String?) {
            when (tag) {
                "tt" -> {
                    tickRate = attr("tickRate")?.toLongOrNull()?.coerceAtLeast(1L) ?: 1000L
                }
                "p" -> {
                    inParagraph = true
                    pBeginMs = parseTtmlTimestampMs(attr("begin"), tickRate)
                    pEndMs = parseTtmlTimestampMs(attr("end"), tickRate)
                        ?: run {
                            val dur = parseTtmlTimestampMs(attr("dur"), tickRate)
                            if (pBeginMs != null && dur != null) pBeginMs!! + dur else null
                        }
                    pRole = attr("role")
                    pPrimaryBuf.setLength(0)
                    pTranslationBuf.setLength(0)
                    pRomanBuf.setLength(0)
                    pWords.clear()
                    pendingGap.setLength(0)
                    inSpan = false
                }
                "span" -> {
                    if (inParagraph) {
                        inSpan = true
                        spanBeginMs = parseTtmlTimestampMs(attr("begin"), tickRate)
                        spanEndMs = parseTtmlTimestampMs(attr("end"), tickRate)
                            ?: run {
                                val dur = parseTtmlTimestampMs(attr("dur"), tickRate)
                                if (spanBeginMs != null && dur != null) spanBeginMs!! + dur else null
                            }
                        spanRole = attr("role")
                        spanBuf.setLength(0)
                    }
                }
                "br" -> {
                    if (inParagraph) {
                        if (inSpan) spanBuf.append(' ') else pPrimaryBuf.append(' ')
                    }
                }
            }
        }

        fun onText(text: String) {
            if (!inParagraph || text.isEmpty()) return
            if (inSpan) {
                spanBuf.append(text)
            } else if (text.isBlank()) {
                if (pPrimaryBuf.isNotEmpty() || pWords.isNotEmpty()) {
                    pendingGap.append(' ')
                }
            } else {
                if (pendingGap.isNotEmpty()) {
                    appendPendingGap()
                }
                pPrimaryBuf.append(text)
            }
        }

        fun onEndTag(tag: String) {
            if (!inParagraph) return
            when (tag) {
                "span" -> {
                    if (!inSpan) return
                    val rawSpanText = spanBuf.toString()
                    val role = spanRole?.lowercase()
                    when {
                        role == "x-translation" || role == "translation" -> {
                            val tr = rawSpanText.trim()
                            if (tr.isNotEmpty()) {
                                if (pTranslationBuf.isNotEmpty()) pTranslationBuf.append(' ')
                                pTranslationBuf.append(tr)
                            }
                        }
                        role == "x-roman" || role == "romanization" || role == "transliteration" -> {
                            val rom = rawSpanText.trim()
                            if (rom.isNotEmpty()) {
                                if (pRomanBuf.isNotEmpty()) pRomanBuf.append(' ')
                                pRomanBuf.append(rom)
                            }
                        }
                        else -> {
                            if (pendingGap.isNotEmpty()) {
                                appendPendingGap()
                            }
                            if (rawSpanText.isNotEmpty()) {
                                val wStart = spanBeginMs ?: pBeginMs
                                if (wStart != null) {
                                    pWords.add(WordToken(startMs = wStart, text = rawSpanText, endMs = spanEndMs))
                                }
                                pPrimaryBuf.append(rawSpanText)
                            }
                        }
                    }
                    inSpan = false
                    spanBeginMs = null
                    spanEndMs = null
                    spanRole = null
                    spanBuf.setLength(0)
                }
                "p" -> {
                    val text = normalizeSpaces(pPrimaryBuf.toString())
                    val role = pRole?.lowercase()
                    if (role != "x-translation" && role != "x-roman" && (text.isNotEmpty() || pBeginMs != null)) {
                        val lineStart = pBeginMs ?: pWords.firstOrNull()?.startMs ?: 0L
                        val lineEnd = pEndMs ?: pWords.lastOrNull()?.endMs
                        val translation = normalizeSpaces(pTranslationBuf.toString()).ifEmpty { null }
                        val roman = normalizeSpaces(pRomanBuf.toString()).ifEmpty { null }
                        val wordsSnapshot = if (pWords.isNotEmpty()) ArrayList(pWords) else emptyList()
                        outLines.add(
                            LyricLine(
                                timestampMs = lineStart,
                                text = text,
                                words = wordsSnapshot,
                                translation = translation,
                                romanizedText = roman,
                                endMs = lineEnd
                            )
                        )
                    }
                    inParagraph = false
                }
            }
        }

        fun build(): ParsedLyrics {
            if (outLines.isEmpty()) {
                return ParsedLyrics(emptyList(), isSynced = false, offsetMs = 0L)
            }
            outLines.sortBy { it.timestampMs }
            val hasTimestamps = outLines.any { it.timestampMs > 0L || it.words.isNotEmpty() || it.endMs != null }
            return ParsedLyrics(lines = outLines, isSynced = hasTimestamps, offsetMs = 0L)
        }

        private fun appendPendingGap() {
            if (pPrimaryBuf.isNotEmpty() && !pPrimaryBuf.last().isWhitespace()) {
                pPrimaryBuf.append(' ')
                if (pWords.isNotEmpty()) {
                    val lastIdx = pWords.lastIndex
                    val last = pWords[lastIdx]
                    if (!last.text.endsWith(' ')) {
                        pWords[lastIdx] = last.copy(text = last.text + " ")
                    }
                }
            }
            pendingGap.setLength(0)
        }

        private fun normalizeSpaces(raw: String): String {
            return raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
                .replace(MULTI_SPACE_REGEX, " ")
                .trim()
        }

        private companion object {
            val MULTI_SPACE_REGEX = Regex(" {2,}")
        }
    }
}
