package com.laguku.player.metadata

import com.laguku.player.model.ChapterMarker
import com.laguku.player.model.TagMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Base64

/**
 * Pure Kotlin binary metadata reader supporting FLAC, OGG/Opus, MP3/WAV (ID3v2.3 & ID3v2.4),
 * and MP4/M4A/ALAC atoms with strict bounds and allocation limits.
 */
object BinaryTagReader {
    const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
    const val MAX_TEXT_BYTES = 512 * 1024

    // Caps assembled Ogg comment packet so base64-encoded 8MB artwork fits without unbounded OOM.
    private const val MAX_OGG_COMMENT_PACKET_BYTES = 12 * 1024 * 1024
    private const val PICTURE_TYPE_FRONT_COVER = 3

    fun read(file: File): TagMetadata {
        val embedded = readEmbedded(file)

        val parent = file.parentFile ?: return embedded
        var lyrics = embedded.lyrics
        var artwork = embedded.artworkBytes

        if (lyrics == null) {
            lyrics = readSidecarLrc(file)
        }
        if (artwork == null) {
            artwork = readArtworkSidecar(parent)
        }

        return if (lyrics !== embedded.lyrics || artwork !== embedded.artworkBytes) {
            embedded.copy(lyrics = lyrics, artworkBytes = artwork)
        } else {
            embedded
        }
    }

    /**
     * Reads only embedded binary tags from [file] (no sidecar lookups), enforcing strict bounds.
     */
    fun readEmbedded(file: File): TagMetadata {
        if (!file.exists() || !file.isFile || !file.canRead()) return TagMetadata()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                readFromSource(RafSource(raf), file.extension.lowercase())
            }
        } catch (_: Exception) {
            TagMetadata()
        }
    }

    /**
     * Reads only embedded lyrics from [file] by streaming container headers and skipping
     * non-lyric blocks such as FLAC PICTURE or ID3 APIC frames.
     */
    fun readEmbeddedLyricsStreaming(file: File): String? {
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                readFromSource(RafSource(raf), file.extension.lowercase(), lyricsOnly = true)
                    .lyrics?.ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readEmbeddedLyricsFromBytes(data: ByteArray, extension: String = ""): String? {
        if (data.isEmpty()) return null
        return try {
            readFromSource(ByteArraySource(data), extension.lowercase(), lyricsOnly = true)
                .lyrics?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun readSidecarTtml(audioFile: File): String? {
        val parent = audioFile.parentFile ?: return null
        return readTextSidecar(File(parent, "${audioFile.nameWithoutExtension}.ttml"))
    }

    fun readSidecarLrc(audioFile: File): String? {
        val parent = audioFile.parentFile ?: return null
        return readTextSidecar(File(parent, "${audioFile.nameWithoutExtension}.lrc"))
    }

    fun readFromBytes(data: ByteArray, extension: String = ""): TagMetadata {
        if (data.isEmpty()) return TagMetadata()
        return try {
            readFromSource(ByteArraySource(data), extension.lowercase(), lyricsOnly = false)
        } catch (_: Exception) {
            TagMetadata()
        }
    }

    private fun readTextSidecar(file: File): String? {
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        val len = file.length()
        if (len <= 0L || len > MAX_TEXT_BYTES.toLong()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size > MAX_TEXT_BYTES) return null
            val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            text.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun readArtworkSidecar(dir: File): ByteArray? {
        val candidates = arrayOf("cover.jpg", "folder.jpg", "Cover.jpg", "Folder.jpg")
        for (name in candidates) {
            val f = File(dir, name)
            if (f.exists() && f.isFile && f.canRead()) {
                val len = f.length()
                if (len in 1L..MAX_ARTWORK_BYTES.toLong()) {
                    try {
                        val bytes = f.readBytes()
                        if (bytes.size in 1..MAX_ARTWORK_BYTES) return bytes
                    } catch (_: Exception) {
                        // Try next candidate.
                    }
                }
            }
        }
        return null
    }

    private fun readFromSource(source: BinarySource, extension: String, lyricsOnly: Boolean = false): TagMetadata {
        val len = source.length
        if (len < 4L) return TagMetadata()

        val header4 = source.readAscii(0L, 4) ?: return TagMetadata()
        val acc = TagAccumulator()

        when {
            header4 == "fLaC" -> parseFlac(source, acc, lyricsOnly)
            header4 == "OggS" -> parseOgg(source, acc)
            header4.startsWith("ID3") -> parseId3v2(source, 0L, len, acc, lyricsOnly)
            header4 == "RIFF" && len >= 12L && source.readAscii(8L, 4) == "WAVE" -> parseWav(source, acc, lyricsOnly)
            len >= 8L && source.readAscii(4L, 4) == "ftyp" -> parseMp4(source, acc)
            extension == "mp3" -> {
                // No ID3v2 at start; nothing to parse cleanly.
            }
        }
        return acc.toMetadata()
    }

    // --- FLAC ---

    private fun parseFlac(source: BinarySource, acc: TagAccumulator, lyricsOnly: Boolean = false) {
        var cursor = 4L
        val len = source.length
        while (cursor + 4L <= len) {
            val header = source.readBytes(cursor, 4) ?: break
            val first = header[0].toInt() and 0xFF
            val isLast = (first and 0x80) != 0
            val blockType = first and 0x7F
            val blockSize = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            val dataStart = cursor + 4L
            if (blockSize < 0 || dataStart + blockSize > len) break

            if (blockType == 4 && blockSize > 0 && blockSize <= MAX_TEXT_BYTES) {
                val blockBytes = source.readBytes(dataStart, blockSize)
                if (blockBytes != null) {
                    parseVorbisComments(blockBytes, 0, blockBytes.size, acc, lyricsOnly)
                }
                if (lyricsOnly) break
            } else if (!lyricsOnly && blockType == 6 && blockSize > 0 && blockSize <= MAX_ARTWORK_BYTES) {
                val blockBytes = source.readBytes(dataStart, blockSize)
                if (blockBytes != null) {
                    parseFlacPictureBlock(blockBytes, 0, blockBytes.size, acc)
                }
            }

            cursor = dataStart + blockSize
            if (isLast) break
        }
    }

    private fun parseVorbisComments(
        data: ByteArray,
        start: Int,
        end: Int,
        acc: TagAccumulator,
        lyricsOnly: Boolean = false
    ) {
        var pos = start
        if (pos + 4 > end) return
        val vendorLen = readUInt32LE(data, pos)
        pos += 4
        if (vendorLen < 0 || vendorLen > end - pos) return
        pos += vendorLen.toInt()

        if (pos + 4 > end) return
        val count = readUInt32LE(data, pos)
        pos += 4
        if (count <= 0L) return

        var i = 0L
        while (i < count && pos + 4 <= end) {
            val entryLen = readUInt32LE(data, pos)
            pos += 4
            if (entryLen < 0 || entryLen > end - pos) break
            val lenInt = entryLen.toInt()
            if (lenInt == 0) {
                i++
                continue
            }

            val entryStart = pos
            pos += lenInt

            // Fast check for METADATA_BLOCK_PICTURE before allocating full string.
            val eqIdx = indexOfByte(data, entryStart, entryStart + lenInt, '='.code.toByte())
            if (eqIdx <= entryStart) {
                i++
                continue
            }
            val keyLen = eqIdx - entryStart
            val valStart = eqIdx + 1
            val valLen = (entryStart + lenInt) - valStart
            if (keyLen > MAX_TEXT_BYTES) {
                i++
                continue
            }
            val key = String(data, entryStart, keyLen, Charsets.UTF_8).uppercase()

            if (lyricsOnly) {
                if ((key == "LYRICS" || key == "SYNCEDLYRICS" || key == "UNSYNCEDLYRICS") && valLen <= MAX_TEXT_BYTES) {
                    val value = String(data, valStart, valLen, Charsets.UTF_8)
                    applyVorbisTag(key, value, acc)
                }
            } else if (key == "METADATA_BLOCK_PICTURE") {
                val maxBase64Len = (MAX_ARTWORK_BYTES.toLong() * 4L) / 3L + 1024L
                if (valLen in 1..maxBase64Len.toInt()) {
                    try {
                        val b64 = String(data, valStart, valLen, Charsets.US_ASCII).trim()
                        val decoded = Base64.getDecoder().decode(b64)
                        if (decoded.size in 1..MAX_ARTWORK_BYTES) {
                            parseFlacPictureBlock(decoded, 0, decoded.size, acc)
                        }
                    } catch (_: Exception) {
                        // Ignore malformed base64 picture.
                    }
                }
            } else if (valLen <= MAX_TEXT_BYTES) {
                val value = String(data, valStart, valLen, Charsets.UTF_8)
                applyVorbisTag(key, value, acc)
            }
            i++
        }
    }

    private fun applyVorbisTag(key: String, value: String, acc: TagAccumulator) {
        if (value.isEmpty()) return
        when (key) {
            "TITLE" -> if (acc.title == null) acc.title = value
            "ARTIST" -> if (acc.artist == null) acc.artist = value
            "ALBUM" -> if (acc.album == null) acc.album = value
            "TRACKNUMBER" -> if (acc.trackNumber == null) acc.trackNumber = parseTrackNumber(value)
            "REPLAYGAIN_TRACK_GAIN" -> if (acc.replayGainDb == null) acc.replayGainDb = parseReplayGainDb(value)
            "LYRICS", "SYNCEDLYRICS" -> acc.lyrics = value
            "UNSYNCEDLYRICS" -> if (acc.lyrics == null) acc.lyrics = value
            else -> {
                if (key.startsWith("CHAPTER")) {
                    val suffix = key.removePrefix("CHAPTER")
                    if (suffix.endsWith("NAME")) {
                        val id = suffix.removeSuffix("NAME")
                        if (id.isNotEmpty() && id.all { it.isDigit() }) {
                            acc.chapterNames[id] = value
                        }
                    } else if (suffix.isNotEmpty() && suffix.all { it.isDigit() }) {
                        parseChapterTimestampMs(value)?.let { ms ->
                            acc.chapterStarts[suffix] = ms
                        }
                    }
                }
            }
        }
    }

    private fun parseFlacPictureBlock(data: ByteArray, start: Int, end: Int, acc: TagAccumulator) {
        var pos = start
        if (pos + 4 > end) return
        val pictureType = readInt32BE(data, pos)
        pos += 4

        if (pos + 4 > end) return
        val mimeLen = readInt32BE(data, pos)
        pos += 4
        if (mimeLen < 0 || mimeLen > end - pos) return
        pos += mimeLen

        if (pos + 4 > end) return
        val descLen = readInt32BE(data, pos)
        pos += 4
        if (descLen < 0 || descLen > end - pos) return
        pos += descLen

        // width (4), height (4), depth (4), colors (4)
        if (pos + 16 > end) return
        pos += 16

        if (pos + 4 > end) return
        val dataLen = readInt32BE(data, pos)
        pos += 4
        if (dataLen <= 0 || dataLen > MAX_ARTWORK_BYTES || dataLen > end - pos) return

        val art = data.copyOfRange(pos, pos + dataLen)
        acc.offerArtwork(art, isFrontCover = (pictureType == PICTURE_TYPE_FRONT_COVER))
    }

    // --- OGG / Opus ---

    private fun parseOgg(source: BinarySource, acc: TagAccumulator) {
        var cursor = 0L
        val len = source.length
        var packetIndex = 0
        var packetOut = ByteArrayOutputStream()
        var packetBytesAccumulated = 0

        while (cursor + 27L <= len) {
            val header = source.readBytes(cursor, 27) ?: break
            if (header[0] != 'O'.code.toByte() ||
                header[1] != 'g'.code.toByte() ||
                header[2] != 'g'.code.toByte() ||
                header[3] != 'S'.code.toByte()
            ) {
                break
            }

            val headerType = header[5].toInt() and 0xFF
            val isContinuation = (headerType and 0x01) != 0
            if (!isContinuation && packetOut.size() > 0) {
                // Previous packet wasn't closed cleanly; reset.
                packetOut.reset()
                packetBytesAccumulated = 0
            }

            val pageSegments = header[26].toInt() and 0xFF
            val segTableStart = cursor + 27L
            if (segTableStart + pageSegments > len) break
            val segTable = if (pageSegments > 0) {
                source.readBytes(segTableStart, pageSegments) ?: break
            } else {
                ByteArray(0)
            }

            var payloadTotal = 0
            for (b in segTable) {
                payloadTotal += b.toInt() and 0xFF
            }
            val payloadStart = segTableStart + pageSegments
            if (payloadStart + payloadTotal > len) break

            var segOffset = payloadStart
            for (b in segTable) {
                val segLen = b.toInt() and 0xFF
                if (segLen > 0) {
                    if (packetBytesAccumulated + segLen <= MAX_OGG_COMMENT_PACKET_BYTES) {
                        val chunk = source.readBytes(segOffset, segLen) ?: return
                        packetOut.write(chunk)
                    }
                    packetBytesAccumulated += segLen
                    segOffset += segLen
                }

                if (segLen < 255) {
                    // Packet complete.
                    if (packetBytesAccumulated <= MAX_OGG_COMMENT_PACKET_BYTES) {
                        val packet = packetOut.toByteArray()
                        val handled = handleOggPacket(packet, packetIndex, acc)
                        if (handled == OggPacketResult.STOP) return
                    } else if (packetIndex >= 1) {
                        return
                    }
                    packetIndex++
                    packetOut.reset()
                    packetBytesAccumulated = 0
                }
            }

            cursor = payloadStart + payloadTotal
            if (packetIndex > 2) break
        }
    }

    private enum class OggPacketResult { CONTINUE, STOP }

    private fun handleOggPacket(packet: ByteArray, packetIndex: Int, acc: TagAccumulator): OggPacketResult {
        if (packet.size >= 8 &&
            packet[0] == 'O'.code.toByte() &&
            packet[1] == 'p'.code.toByte() &&
            packet[2] == 'u'.code.toByte() &&
            packet[3] == 's'.code.toByte() &&
            packet[4] == 'T'.code.toByte() &&
            packet[5] == 'a'.code.toByte() &&
            packet[6] == 'g'.code.toByte() &&
            packet[7] == 's'.code.toByte()
        ) {
            parseVorbisComments(packet, 8, packet.size, acc)
            return OggPacketResult.STOP
        }
        if (packet.size >= 7 &&
            packet[0] == 0x03.toByte() &&
            packet[1] == 'v'.code.toByte() &&
            packet[2] == 'o'.code.toByte() &&
            packet[3] == 'r'.code.toByte() &&
            packet[4] == 'b'.code.toByte() &&
            packet[5] == 'i'.code.toByte() &&
            packet[6] == 's'.code.toByte()
        ) {
            parseVorbisComments(packet, 7, packet.size, acc)
            return OggPacketResult.STOP
        }
        if (packetIndex >= 2) return OggPacketResult.STOP
        return OggPacketResult.CONTINUE
    }

    // --- WAV (RIFF) ---

    private fun parseWav(source: BinarySource, acc: TagAccumulator, lyricsOnly: Boolean = false) {
        var cursor = 12L
        val len = source.length
        while (cursor + 8L <= len) {
            val header = source.readBytes(cursor, 8) ?: break
            val chunkId = String(header, 0, 4, Charsets.US_ASCII)
            val chunkSize = readUInt32LE(header, 4)
            val dataStart = cursor + 8L
            if (chunkSize < 0L || dataStart + chunkSize > len) break

            if (chunkId.equals("id3 ", ignoreCase = true)) {
                parseId3v2(source, dataStart, dataStart + chunkSize, acc, lyricsOnly)
                return
            }

            val padded = chunkSize + (chunkSize and 1L)
            cursor = dataStart + padded
        }
    }

    // --- ID3v2.3 / ID3v2.4 ---

    private fun parseId3v2(
        source: BinarySource,
        startOffset: Long,
        maxEnd: Long,
        acc: TagAccumulator,
        lyricsOnly: Boolean = false
    ) {
        if (startOffset + 10L > maxEnd) return
        val header = source.readBytes(startOffset, 10) ?: return
        if (header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return
        }

        val majorVersion = header[3].toInt() and 0xFF
        if (majorVersion !in 3..4) return
        val tagFlags = header[5].toInt() and 0xFF
        val tagSize = readSyncsafeInt(header, 6)
        if (tagSize <= 0) return

        val tagBodyStart = startOffset + 10L
        val tagEnd = minOf(tagBodyStart + tagSize.toLong(), maxEnd)
        if (tagBodyStart >= tagEnd) return

        val globalUnsync = (tagFlags and 0x80) != 0
        val hasExtHeader = (tagFlags and 0x40) != 0

        if (globalUnsync && majorVersion == 3) {
            val boundedLen = (tagEnd - tagBodyStart).coerceAtMost((MAX_ARTWORK_BYTES + MAX_TEXT_BYTES).toLong()).toInt()
            val raw = source.readBytes(tagBodyStart, boundedLen) ?: return
            val deunsynced = removeUnsync(raw)
            parseId3Frames(ByteArraySource(deunsynced), 0L, deunsynced.size.toLong(), majorVersion, hasExtHeader, acc, lyricsOnly)
        } else {
            parseId3Frames(source, tagBodyStart, tagEnd, majorVersion, hasExtHeader, acc, lyricsOnly)
        }
    }

    private fun parseId3Frames(
        source: BinarySource,
        bodyStart: Long,
        tagEnd: Long,
        majorVersion: Int,
        hasExtHeader: Boolean,
        acc: TagAccumulator,
        lyricsOnly: Boolean = false
    ) {
        var cursor = bodyStart
        if (hasExtHeader) {
            if (cursor + 4L > tagEnd) return
            val extHeaderBytes = source.readBytes(cursor, 4) ?: return
            if (majorVersion == 4) {
                val extSize = readSyncsafeInt(extHeaderBytes, 0)
                if (extSize < 6 || cursor + extSize > tagEnd) return
                cursor += extSize
            } else {
                val extSize = readUInt32BE(extHeaderBytes, 0)
                if (extSize < 0L || cursor + 4L + extSize > tagEnd) return
                cursor += 4L + extSize
            }
        }

        while (cursor + 10L <= tagEnd) {
            val frameHdr = source.readBytes(cursor, 10) ?: break
            if (frameHdr[0] == 0.toByte()) break // Padding reached.

            val frameId = String(frameHdr, 0, 4, Charsets.US_ASCII)
            val rawSize = if (majorVersion >= 4) {
                readSyncsafeInt(frameHdr, 4).toLong()
            } else {
                readUInt32BE(frameHdr, 4)
            }
            val dataStart = cursor + 10L
            if (rawSize <= 0L || dataStart + rawSize > tagEnd) break

            val wantFrame = !lyricsOnly || frameId == "SYLT" || frameId == "USLT" || frameId == "TXXX"
            if (wantFrame) {
                val formatFlags = frameHdr[9].toInt() and 0xFF
                val isCompressed = (majorVersion == 3 && (frameHdr[9].toInt() and 0x80) != 0) ||
                    (majorVersion >= 4 && (formatFlags and 0x08) != 0)
                val isEncrypted = (majorVersion == 3 && (frameHdr[9].toInt() and 0x40) != 0) ||
                    (majorVersion >= 4 && (formatFlags and 0x04) != 0)

                val maxAllowed = if (frameId == "APIC") MAX_ARTWORK_BYTES else MAX_TEXT_BYTES
                if (!isCompressed && !isEncrypted && rawSize <= maxAllowed.toLong()) {
                    val rawPayload = source.readBytes(dataStart, rawSize.toInt())
                    if (rawPayload != null) {
                        val payload = decodeId3FramePayload(rawPayload, majorVersion, formatFlags)
                        if (payload != null && payload.isNotEmpty()) {
                            handleId3Frame(frameId, payload, acc)
                        }
                    }
                }
            }

            cursor = dataStart + rawSize
        }
    }

    private fun decodeId3FramePayload(raw: ByteArray, majorVersion: Int, formatFlags: Int): ByteArray? {
        if (majorVersion < 4) return raw
        var offset = 0
        val hasDataLenIndicator = (formatFlags and 0x01) != 0
        val isUnsync = (formatFlags and 0x02) != 0
        if (hasDataLenIndicator) {
            if (raw.size < 4) return null
            offset = 4
        }
        val slice = if (offset == 0) raw else raw.copyOfRange(offset, raw.size)
        return if (isUnsync) removeUnsync(slice) else slice
    }

    private fun removeUnsync(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            val b = data[i]
            out.write(b.toInt())
            if (b == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    private fun handleId3Frame(frameId: String, payload: ByteArray, acc: TagAccumulator) {
        when (frameId) {
            "TIT2" -> decodeId3TextFrame(payload)?.let { if (acc.title == null) acc.title = it }
            "TPE1" -> decodeId3TextFrame(payload)?.let { if (acc.artist == null) acc.artist = it }
            "TALB" -> decodeId3TextFrame(payload)?.let { if (acc.album == null) acc.album = it }
            "TRCK" -> decodeId3TextFrame(payload)?.let {
                if (acc.trackNumber == null) acc.trackNumber = parseTrackNumber(it)
            }
            "TXXX" -> parseId3Txxx(payload, acc)
            "USLT" -> parseId3Uslt(payload, acc)
            "SYLT" -> parseId3Sylt(payload, acc)
            "APIC" -> parseId3Apic(payload, acc)
        }
    }

    private fun decodeId3TextFrame(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        return decodeId3String(payload, 1, payload.size, encoding)?.trimEnd('\u0000')?.ifEmpty { null }
    }

    private fun parseId3Txxx(payload: ByteArray, acc: TagAccumulator) {
        if (payload.size < 2) return
        val encoding = payload[0].toInt() and 0xFF
        val descEnd = findId3Terminator(payload, 1, payload.size, encoding)
        val desc = decodeId3String(payload, 1, descEnd, encoding)?.trim() ?: return
        val valStart = advancePastTerminator(descEnd, payload.size, encoding)
        val value = decodeId3String(payload, valStart, payload.size, encoding)?.trimEnd('\u0000') ?: return
        if (value.isEmpty()) return

        when (desc.uppercase()) {
            "REPLAYGAIN_TRACK_GAIN" -> if (acc.replayGainDb == null) acc.replayGainDb = parseReplayGainDb(value)
            "LYRICS", "UNSYNCEDLYRICS" -> if (acc.lyrics == null) acc.lyrics = value
        }
    }

    private fun parseId3Uslt(payload: ByteArray, acc: TagAccumulator) {
        if (payload.size < 5) return
        val encoding = payload[0].toInt() and 0xFF
        // bytes 1..3: language
        val descEnd = findId3Terminator(payload, 4, payload.size, encoding)
        val textStart = advancePastTerminator(descEnd, payload.size, encoding)
        val text = decodeId3String(payload, textStart, payload.size, encoding)?.trimEnd('\u0000') ?: return
        if (text.isNotBlank() && acc.lyrics == null) {
            acc.lyrics = text
        }
    }

    private fun parseId3Sylt(payload: ByteArray, acc: TagAccumulator) {
        // encoding(1) + language(3) + timeStampFormat(1) + contentType(1) + descriptor + entries
        if (payload.size < 7) return
        val encoding = payload[0].toInt() and 0xFF
        val tsFormat = payload[4].toInt() and 0xFF
        val descEnd = findId3Terminator(payload, 6, payload.size, encoding)
        var pos = advancePastTerminator(descEnd, payload.size, encoding)

        val sb = StringBuilder()
        while (pos < payload.size) {
            val segEnd = findId3Terminator(payload, pos, payload.size, encoding)
            val text = decodeId3String(payload, pos, segEnd, encoding) ?: ""
            val afterTerm = advancePastTerminator(segEnd, payload.size, encoding)
            if (afterTerm + 4 > payload.size) break
            val timestamp = readUInt32BE(payload, afterTerm)
            pos = afterTerm + 4

            val clean = text.replace("\r", "").replace("\n", " ").trim()
            if (tsFormat == 2) {
                sb.append(formatLrcTimestamp(timestamp)).append(clean).append('\n')
            } else if (clean.isNotEmpty()) {
                sb.append(clean).append('\n')
            }
        }

        val result = sb.toString().trimEnd('\n')
        if (result.isNotBlank()) {
            // Prefer synced SYLT over unsynced lyrics.
            acc.lyrics = result
        }
    }

    private fun parseId3Apic(payload: ByteArray, acc: TagAccumulator) {
        if (payload.size < 4) return
        val encoding = payload[0].toInt() and 0xFF
        // MIME type is always ISO-8859-1 null-terminated.
        val mimeEnd = findId3Terminator(payload, 1, payload.size, 0)
        val typePos = mimeEnd + 1
        if (typePos >= payload.size) return
        val pictureType = payload[typePos].toInt() and 0xFF
        val descStart = typePos + 1
        if (descStart > payload.size) return
        val descEnd = findId3Terminator(payload, descStart, payload.size, encoding)
        val imgStart = advancePastTerminator(descEnd, payload.size, encoding)
        val imgLen = payload.size - imgStart
        if (imgLen <= 0 || imgLen > MAX_ARTWORK_BYTES) return

        val art = payload.copyOfRange(imgStart, payload.size)
        acc.offerArtwork(art, isFrontCover = (pictureType == PICTURE_TYPE_FRONT_COVER))
    }

    private fun findId3Terminator(data: ByteArray, start: Int, end: Int, encoding: Int): Int {
        if (start >= end) return end
        return if (encoding == 1 || encoding == 2) {
            var i = start
            while (i + 1 < end) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i
                i += 2
            }
            end
        } else {
            var i = start
            while (i < end) {
                if (data[i] == 0.toByte()) return i
                i++
            }
            end
        }
    }

    private fun advancePastTerminator(termPos: Int, end: Int, encoding: Int): Int {
        val termWidth = if (encoding == 1 || encoding == 2) 2 else 1
        return minOf(termPos + termWidth, end)
    }

    private fun decodeId3String(data: ByteArray, start: Int, end: Int, encoding: Int): String? {
        if (start >= end) return ""
        val len = end - start
        return when (encoding) {
            0 -> String(data, start, len, Charsets.ISO_8859_1)
            1 -> decodeUtf16WithBom(data, start, len)
            2 -> String(data, start, len, Charsets.UTF_16BE)
            3 -> String(data, start, len, Charsets.UTF_8)
            else -> String(data, start, len, Charsets.UTF_8)
        }
    }

    private fun decodeUtf16WithBom(data: ByteArray, start: Int, len: Int): String {
        if (len >= 2) {
            val b0 = data[start].toInt() and 0xFF
            val b1 = data[start + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(data, start + 2, len - 2, Charsets.UTF_16LE)
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(data, start + 2, len - 2, Charsets.UTF_16BE)
            }
        }
        return String(data, start, len, Charsets.UTF_16)
    }

    // --- MP4 / M4A / ALAC ---

    private fun parseMp4(source: BinarySource, acc: TagAccumulator) {
        val len = source.length
        val moov = findMp4Box(source, 0L, len, "moov") ?: return
        val udta = findMp4Box(source, moov.payloadStart, moov.end, "udta") ?: return
        findMp4Box(source, udta.payloadStart, udta.end, "chpl")?.let { chpl ->
            parseMp4Chpl(source, chpl, acc)
        }
        val meta = findMp4Box(source, udta.payloadStart, udta.end, "meta") ?: return

        // meta is a FullBox (4 bytes version/flags), though some writers omit it; check +4 first.
        val ilst = if (meta.payloadStart + 4L <= meta.end) {
            findMp4Box(source, meta.payloadStart + 4L, meta.end, "ilst")
                ?: findMp4Box(source, meta.payloadStart, meta.end, "ilst")
        } else {
            null
        } ?: return

        var cursor = ilst.payloadStart
        while (cursor + 8L <= ilst.end) {
            val item = readMp4BoxHeader(source, cursor, ilst.end) ?: break
            parseMp4IlstItem(source, item, acc)
            cursor = item.end
        }
    }

    private data class Mp4Box(val typeBytes: ByteArray, val type: String, val payloadStart: Long, val end: Long)

    private fun readMp4BoxHeader(source: BinarySource, offset: Long, parentEnd: Long): Mp4Box? {
        if (offset + 8L > parentEnd) return null
        val hdr = source.readBytes(offset, 8) ?: return null
        val size32 = readUInt32BE(hdr, 0)
        val typeBytes = hdr.copyOfRange(4, 8)
        val type = String(typeBytes, Charsets.ISO_8859_1)

        val totalSize: Long
        val headerLen: Long
        when (size32) {
            0L -> {
                totalSize = parentEnd - offset
                headerLen = 8L
            }
            1L -> {
                if (offset + 16L > parentEnd) return null
                val ext = source.readBytes(offset + 8L, 8) ?: return null
                val extSize = readInt64BE(ext, 0)
                if (extSize < 16L) return null
                totalSize = extSize
                headerLen = 16L
            }
            in 2L..7L -> return null
            else -> {
                totalSize = size32
                headerLen = 8L
            }
        }

        if (totalSize < headerLen || offset + totalSize > parentEnd) return null
        return Mp4Box(typeBytes, type, offset + headerLen, offset + totalSize)
    }

    private fun findMp4Box(source: BinarySource, start: Long, end: Long, targetType: String): Mp4Box? {
        var cursor = start
        while (cursor + 8L <= end) {
            val box = readMp4BoxHeader(source, cursor, end) ?: return null
            if (box.type == targetType) return box
            cursor = box.end
        }
        return null
    }

    private fun parseMp4IlstItem(source: BinarySource, item: Mp4Box, acc: TagAccumulator) {
        var mean: String? = null
        var name: String? = null
        var dataType = -1
        var dataPayload: ByteArray? = null

        val isArtwork = item.type == "covr"
        val maxPayload = if (isArtwork) MAX_ARTWORK_BYTES else MAX_TEXT_BYTES

        var cursor = item.payloadStart
        while (cursor + 8L <= item.end) {
            val child = readMp4BoxHeader(source, cursor, item.end) ?: break
            when (child.type) {
                "mean" -> {
                    val strLen = child.end - (child.payloadStart + 4L)
                    if (strLen in 1L..MAX_TEXT_BYTES.toLong()) {
                        source.readBytes(child.payloadStart + 4L, strLen.toInt())?.let {
                            mean = String(it, Charsets.UTF_8)
                        }
                    }
                }
                "name" -> {
                    val strLen = child.end - (child.payloadStart + 4L)
                    if (strLen in 1L..MAX_TEXT_BYTES.toLong()) {
                        source.readBytes(child.payloadStart + 4L, strLen.toInt())?.let {
                            name = String(it, Charsets.UTF_8)
                        }
                    }
                }
                "data" -> {
                    if (child.payloadStart + 8L <= child.end) {
                        val typeHdr = source.readBytes(child.payloadStart, 8)
                        if (typeHdr != null) {
                            dataType = readInt32BE(typeHdr, 0) and 0x00FFFFFF
                            val pStart = child.payloadStart + 8L
                            val pLen = child.end - pStart
                            if (pLen in 1L..maxPayload.toLong()) {
                                dataPayload = source.readBytes(pStart, pLen.toInt())
                            }
                        }
                    }
                }
            }
            cursor = child.end
        }

        val payload = dataPayload ?: return
        val b0 = item.typeBytes[0].toInt() and 0xFF
        val suffix = String(item.typeBytes, 1, 3, Charsets.US_ASCII)

        if (b0 == 0xA9) {
            val text = decodeMp4Text(payload, dataType) ?: return
            when (suffix) {
                "nam" -> if (acc.title == null) acc.title = text
                "ART" -> if (acc.artist == null) acc.artist = text
                "alb" -> if (acc.album == null) acc.album = text
                "lyr" -> if (acc.lyrics == null) acc.lyrics = text
            }
        } else {
            when (item.type) {
                "trkn" -> {
                    if (payload.size >= 4 && acc.trackNumber == null) {
                        val trk = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
                        if (trk > 0) acc.trackNumber = trk
                    }
                }
                "covr" -> {
                    acc.offerArtwork(payload, isFrontCover = true)
                }
                "----" -> {
                    if (mean.equals("com.apple.iTunes", ignoreCase = true)) {
                        val text = decodeMp4Text(payload, dataType) ?: return
                        when (name?.uppercase()) {
                            "REPLAYGAIN_TRACK_GAIN" ->
                                if (acc.replayGainDb == null) acc.replayGainDb = parseReplayGainDb(text)
                            "LYRICS", "UNSYNCEDLYRICS" ->
                                if (acc.lyrics == null) acc.lyrics = text
                        }
                    }
                }
            }
        }
    }

    private fun decodeMp4Text(payload: ByteArray, dataType: Int): String? {
        val charset: Charset = when (dataType) {
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(payload, charset).trimEnd('\u0000').ifEmpty { null }
    }

    private fun parseMp4Chpl(source: BinarySource, chpl: Mp4Box, acc: TagAccumulator) {
        val boxLen = (chpl.end - chpl.payloadStart).toInt()
        if (boxLen < 5 || boxLen > MAX_TEXT_BYTES) return
        val data = source.readBytes(chpl.payloadStart, boxLen) ?: return
        val version = data[0].toInt() and 0xFF
        var pos = 4 // version(1) + flags(3)
        val count: Int
        if (version >= 1) {
            if (pos + 4 > data.size) return
            // 1 reserved byte + 4-byte count or 4-byte count
            pos += 1
            if (pos + 4 > data.size) return
            count = readInt32BE(data, pos).coerceIn(0, 500)
            pos += 4
        } else {
            if (pos >= data.size) return
            count = data[pos].toInt() and 0xFF
            pos++
        }
        for (i in 0 until count) {
            if (pos + 9 > data.size) break
            val start100Ns = readInt64BE(data, pos)
            pos += 8
            val titleLen = data[pos].toInt() and 0xFF
            pos++
            if (pos + titleLen > data.size) break
            val title = String(data, pos, titleLen, Charsets.UTF_8).trim()
            pos += titleLen
            val startMs = (start100Ns / 10_000L).coerceAtLeast(0L)
            acc.directChapters.add(ChapterMarker(startMs, title.ifBlank { "Chapter ${i + 1}" }))
        }
    }

    private fun parseChapterTimestampMs(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secParts = parts[2].split('.')
        val s = secParts[0].toLongOrNull() ?: return null
        val msStr = secParts.getOrNull(1).orEmpty()
        val ms = when (msStr.length) {
            0 -> 0L
            1 -> (msStr.toLongOrNull() ?: 0L) * 100L
            2 -> (msStr.toLongOrNull() ?: 0L) * 10L
            else -> msStr.substring(0, 3).toLongOrNull() ?: 0L
        }
        return ((h * 3600L + m * 60L + s) * 1000L + ms).coerceAtLeast(0L)
    }

    // --- Helpers & Binary Sources ---

    private class TagAccumulator {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: Int? = null
        var replayGainDb: Float? = null
        var lyrics: String? = null
        private var artwork: ByteArray? = null
        private var hasFrontCover = false
        val chapterStarts = LinkedHashMap<String, Long>()
        val chapterNames = LinkedHashMap<String, String>()
        val directChapters = ArrayList<ChapterMarker>()

        fun offerArtwork(bytes: ByteArray, isFrontCover: Boolean) {
            if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) return
            if (artwork == null || (isFrontCover && !hasFrontCover)) {
                artwork = bytes
                hasFrontCover = isFrontCover
            }
        }

        fun toMetadata(): TagMetadata {
            val chapters = if (directChapters.isNotEmpty()) {
                directChapters.sortedBy { it.startMs }
            } else if (chapterStarts.isNotEmpty()) {
                chapterStarts.entries
                    .mapIndexed { idx, (id, ms) ->
                        ChapterMarker(ms, chapterNames[id]?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}")
                    }
                    .sortedBy { it.startMs }
            } else {
                emptyList()
            }
            return TagMetadata(
                title = title,
                artist = artist,
                album = album,
                trackNumber = trackNumber,
                replayGainDb = replayGainDb,
                lyrics = lyrics,
                artworkBytes = artwork,
                chapters = chapters
            )
        }
    }

    private interface BinarySource {
        val length: Long
        fun readBytes(offset: Long, count: Int): ByteArray?
        fun readAscii(offset: Long, count: Int): String? {
            val bytes = readBytes(offset, count) ?: return null
            return String(bytes, Charsets.US_ASCII)
        }
    }

    private class ByteArraySource(private val data: ByteArray) : BinarySource {
        override val length: Long = data.size.toLong()

        override fun readBytes(offset: Long, count: Int): ByteArray? {
            if (count < 0 || offset < 0L || offset + count > length) return null
            if (count == 0) return ByteArray(0)
            val start = offset.toInt()
            return data.copyOfRange(start, start + count)
        }
    }

    private class RafSource(private val raf: RandomAccessFile) : BinarySource {
        override val length: Long = raf.length()

        override fun readBytes(offset: Long, count: Int): ByteArray? {
            if (count < 0 || offset < 0L || offset + count > length) return null
            if (count == 0) return ByteArray(0)
            val buf = ByteArray(count)
            raf.seek(offset)
            raf.readFully(buf)
            return buf
        }
    }

    private fun parseTrackNumber(raw: String): Int? {
        val token = raw.substringBefore('/').trim()
        val num = token.toIntOrNull() ?: return null
        return if (num > 0) num else null
    }

    private fun parseReplayGainDb(raw: String): Float? {
        val cleaned = raw.trim()
            .replace("db", "", ignoreCase = true)
            .trim()
        val value = cleaned.toFloatOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    private fun formatLrcTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSec = clamped / 1000L
        val min = totalSec / 60L
        val sec = totalSec % 60L
        val centis = (clamped % 1000L) / 10L
        return "[${pad2(min)}:${pad2(sec)}.${pad2(centis)}]"
    }

    private fun pad2(v: Long): String = if (v < 10L) "0$v" else v.toString()

    private fun indexOfByte(data: ByteArray, start: Int, end: Int, target: Byte): Int {
        for (i in start until end) {
            if (data[i] == target) return i
        }
        return -1
    }

    private fun readSyncsafeInt(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)
    }

    private fun readUInt32BE(b: ByteArray, offset: Int): Long {
        return ((b[offset].toLong() and 0xFFL) shl 24) or
            ((b[offset + 1].toLong() and 0xFFL) shl 16) or
            ((b[offset + 2].toLong() and 0xFFL) shl 8) or
            (b[offset + 3].toLong() and 0xFFL)
    }

    private fun readInt32BE(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)
    }

    private fun readInt64BE(b: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (b[offset + i].toLong() and 0xFFL)
        }
        return result
    }

    private fun readUInt32LE(b: ByteArray, offset: Int): Long {
        return (b[offset].toLong() and 0xFFL) or
            ((b[offset + 1].toLong() and 0xFFL) shl 8) or
            ((b[offset + 2].toLong() and 0xFFL) shl 16) or
            ((b[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
