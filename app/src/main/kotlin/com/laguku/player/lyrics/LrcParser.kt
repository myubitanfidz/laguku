package com.laguku.player.lyrics

import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.model.LyricLine
import com.laguku.player.model.LyricSource
import com.laguku.player.model.ParsedLyrics
import com.laguku.player.model.WordToken
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
     * Loads lyrics in strict priority order:
     * 1) Synced lyrics embedded in audio binary tags ([LyricSource.EMBEDDED_TAG])
     * 2) Local sidecar `.lrc` next to the audio file ([LyricSource.SIDECAR_LRC], mandatory fallback for WAV)
     * 3) App internal fallback `.lrc` ([LyricSource.INTERNAL_FALLBACK])
     * 4) Embedded unsynced plain-text lyrics ([LyricSource.EMBEDDED_TAG])
     */
    fun loadLyrics(
        audioFile: File,
        internalFallbackDir: File,
        embeddedLyrics: String? = BinaryTagReader.readEmbedded(audioFile).lyrics
    ): ParsedLyrics {
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
}
