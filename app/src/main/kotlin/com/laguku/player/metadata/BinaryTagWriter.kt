package com.laguku.player.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

data class TagEditRequest(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val artworkBytes: ByteArray? = null,
    val replaceArtwork: Boolean = false
)

/**
 * Pure Kotlin atomic in-place binary tag writer supporting FLAC, MP3 (ID3v2), and M4A/ALAC
 * without re-encoding audio payloads. Writes to a temporary sibling file, keeps a rollback
 * backup during swap, verifies readability via [BinaryTagReader], and restores on error.
 */
object BinaryTagWriter {

    private val LOSSLESS_TAG_EDIT_SUPPORTED_EXT = setOf("flac", "mp3", "m4a")

    fun isSupportedFile(file: File): Boolean = file.extension.lowercase() in LOSSLESS_TAG_EDIT_SUPPORTED_EXT

    fun writeTags(file: File, request: TagEditRequest): Boolean {
        if (!file.exists() || !file.isFile || !file.canRead()) return false
        val art = request.artworkBytes
        if (art != null && art.size > BinaryTagReader.MAX_ARTWORK_BYTES) return false

        val header = ByteArray(12)
        val headerLen = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val toRead = minOf(12L, raf.length()).toInt()
                if (toRead > 0) raf.readFully(header, 0, toRead)
                toRead
            }
        }.getOrDefault(0)
        if (headerLen < 4) return false

        val magic4 = String(header, 0, 4, Charsets.US_ASCII)
        val ext = file.extension.lowercase()

        val parent = file.parentFile ?: return false
        val tmpFile = File(parent, "${file.name}.laguku.tmp")
        val bakFile = File(parent, "${file.name}.laguku.bak")
        if (tmpFile.exists()) tmpFile.delete()
        if (bakFile.exists()) bakFile.delete()

        val built = runCatching {
            when {
                magic4 == "fLaC" -> writeFlac(file, tmpFile, request)
                magic4.startsWith("ID3") || ext == "mp3" -> writeMp3(file, tmpFile, request)
                headerLen >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" -> writeM4a(file, tmpFile, request)
                else -> false
            }
        }.getOrDefault(false)

        if (!built || !tmpFile.isFile || tmpFile.length() == 0L) {
            tmpFile.delete()
            return false
        }

        // Verify written temp file is cleanly readable before swapping
        val verified = runCatching { BinaryTagReader.read(tmpFile) }.getOrNull()
        if (verified == null) {
            tmpFile.delete()
            return false
        }

        // Atomic swap with rollback safety
        if (!file.renameTo(bakFile)) {
            tmpFile.delete()
            return false
        }
        if (!tmpFile.renameTo(file)) {
            bakFile.renameTo(file)
            tmpFile.delete()
            return false
        }
        bakFile.delete()
        return true
    }

    // --- FLAC ---

    private fun writeFlac(src: File, dst: File, req: TagEditRequest): Boolean {
        var streamInfo: ByteArray? = null
        val otherBlocks = ArrayList<Pair<Int, ByteArray>>()
        val existingComments = LinkedHashMap<String, String>()
        var vendor = "Laguku"
        var audioOffset = 4L
        var totalLen = 0L

        RandomAccessFile(src, "r").use { raf ->
            totalLen = raf.length()
            if (totalLen < 4L) return false
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "fLaC") return false

            var cursor = 4L
            val hdr = ByteArray(4)
            while (cursor + 4L <= totalLen) {
                raf.seek(cursor)
                raf.readFully(hdr)
                val first = hdr[0].toInt() and 0xFF
                val isLast = (first and 0x80) != 0
                val type = first and 0x7F
                val size = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                val dataStart = cursor + 4L
                if (dataStart + size > totalLen || size > BinaryTagReader.MAX_ARTWORK_BYTES + BinaryTagReader.MAX_TEXT_BYTES) {
                    return false
                }

                when (type) {
                    0 -> {
                        val payload = ByteArray(size)
                        raf.readFully(payload)
                        streamInfo = payload
                    }
                    4 -> {
                        if (size > BinaryTagReader.MAX_TEXT_BYTES) return false
                        val payload = ByteArray(size)
                        raf.readFully(payload)
                        val parsed = parseVorbisCommentPairs(payload)
                        vendor = parsed.first
                        existingComments.putAll(parsed.second)
                    }
                    6 -> {
                        if (!req.replaceArtwork) {
                            val payload = ByteArray(size)
                            raf.readFully(payload)
                            otherBlocks.add(type to payload)
                        }
                    }
                    1 -> Unit // skip padding
                    else -> {
                        val payload = ByteArray(size)
                        raf.readFully(payload)
                        otherBlocks.add(type to payload)
                    }
                }
                cursor = dataStart + size
                if (isLast) break
            }
            audioOffset = cursor
        }
        val sInfo = streamInfo ?: return false

        req.title?.let { existingComments["TITLE"] = it }
        req.artist?.let { existingComments["ARTIST"] = it }
        req.album?.let { existingComments["ALBUM"] = it }
        req.trackNumber?.takeIf { it > 0 }?.let { existingComments["TRACKNUMBER"] = it.toString() }

        val vorbisPayload = buildVorbisCommentPayload(vendor, existingComments)
        val blocksToWrite = ArrayList<Pair<Int, ByteArray>>()
        blocksToWrite.add(0 to sInfo)
        blocksToWrite.addAll(otherBlocks)
        blocksToWrite.add(4 to vorbisPayload)
        if (req.replaceArtwork && req.artworkBytes != null && req.artworkBytes.isNotEmpty()) {
            blocksToWrite.add(6 to buildFlacPicturePayload(req.artworkBytes))
        }

        dst.outputStream().use { out ->
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            for (i in blocksToWrite.indices) {
                val (type, payload) = blocksToWrite[i]
                val isLast = i == blocksToWrite.lastIndex
                val firstByte = (if (isLast) 0x80 else 0x00) or (type and 0x7F)
                out.write(firstByte)
                out.write((payload.size ushr 16) and 0xFF)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
                out.write(payload)
            }
            if (audioOffset < totalLen) {
                val copyBuf = ByteArray(STREAM_COPY_BUFFER_BYTES)
                RandomAccessFile(src, "r").use { raf ->
                    streamCopyRange(raf, audioOffset, totalLen - audioOffset, out, copyBuf)
                }
            }
        }
        return true
    }

    private fun parseVorbisCommentPairs(payload: ByteArray): Pair<String, LinkedHashMap<String, String>> {
        val map = LinkedHashMap<String, String>()
        var pos = 0
        if (pos + 4 > payload.size) return "Laguku" to map
        val vendorLen = readInt32LE(payload, pos).coerceIn(0, payload.size - pos - 4)
        pos += 4
        val vendor = String(payload, pos, vendorLen, Charsets.UTF_8).ifBlank { "Laguku" }
        pos += vendorLen
        if (pos + 4 > payload.size) return vendor to map
        val count = readInt32LE(payload, pos).coerceAtLeast(0)
        pos += 4
        for (i in 0 until count) {
            if (pos + 4 > payload.size) break
            val len = readInt32LE(payload, pos)
            pos += 4
            if (len < 0 || pos + len > payload.size) break
            val entry = String(payload, pos, len, Charsets.UTF_8)
            pos += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                val valStr = entry.substring(eq + 1)
                map[key] = valStr
            }
        }
        return vendor to map
    }

    private fun buildVorbisCommentPayload(vendor: String, comments: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeInt32LE(out, vendorBytes.size)
        out.write(vendorBytes)
        writeInt32LE(out, comments.size)
        for ((k, v) in comments) {
            val line = "$k=$v".toByteArray(Charsets.UTF_8)
            writeInt32LE(out, line.size)
            out.write(line)
        }
        return out.toByteArray()
    }

    private fun buildFlacPicturePayload(imageBytes: ByteArray): ByteArray {
        val mime = if (imageBytes.size >= 4 &&
            imageBytes[0] == 0x89.toByte() &&
            imageBytes[1] == 0x50.toByte()
        ) "image/png" else "image/jpeg"
        val out = ByteArrayOutputStream()
        writeInt32BE(out, 3) // Front Cover
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        writeInt32BE(out, mimeBytes.size)
        out.write(mimeBytes)
        writeInt32BE(out, 0) // description
        writeInt32BE(out, 0)
        writeInt32BE(out, 0)
        writeInt32BE(out, 24)
        writeInt32BE(out, 0)
        writeInt32BE(out, imageBytes.size)
        out.write(imageBytes)
        return out.toByteArray()
    }

    // --- MP3 (ID3v2.4) ---

    private fun writeMp3(src: File, dst: File, req: TagEditRequest): Boolean {
        var audioStart = 0L
        var totalLen = 0L
        val preservedFrames = ArrayList<Pair<String, ByteArray>>()

        RandomAccessFile(src, "r").use { raf ->
            totalLen = raf.length()
            if (totalLen >= 10L) {
                val hdr = ByteArray(10)
                raf.readFully(hdr)
                if (hdr[0] == 'I'.code.toByte() &&
                    hdr[1] == 'D'.code.toByte() &&
                    hdr[2] == '3'.code.toByte()
                ) {
                    val major = hdr[3].toInt() and 0xFF
                    val tagSize = readSyncsafeInt(hdr, 6)
                    val tagEnd = minOf(10L + tagSize.toLong(), totalLen)
                    audioStart = tagEnd

                    if (major in 3..4 && tagSize in 1..(BinaryTagReader.MAX_ARTWORK_BYTES + BinaryTagReader.MAX_TEXT_BYTES)) {
                        val tagBody = ByteArray((tagEnd - 10L).toInt())
                        raf.readFully(tagBody)
                        var pos = 0
                        while (pos + 10 <= tagBody.size) {
                            if (tagBody[pos] == 0.toByte()) break
                            val frameId = String(tagBody, pos, 4, Charsets.US_ASCII)
                            val frameSize = if (major >= 4) readSyncsafeInt(tagBody, pos + 4) else readInt32BE(tagBody, pos + 4)
                            val dataStart = pos + 10
                            if (frameSize <= 0 || dataStart + frameSize > tagBody.size) break

                            val skip = (frameId == "TIT2" && req.title != null) ||
                                (frameId == "TPE1" && req.artist != null) ||
                                (frameId == "TALB" && req.album != null) ||
                                (frameId == "TRCK" && req.trackNumber != null) ||
                                (frameId == "APIC" && req.replaceArtwork)
                            if (!skip) {
                                preservedFrames.add(frameId to tagBody.copyOfRange(dataStart, dataStart + frameSize))
                            }
                            pos = dataStart + frameSize
                        }
                    }
                }
            }
        }

        val frameOut = ByteArrayOutputStream()
        for ((id, payload) in preservedFrames) {
            writeId3v24Frame(frameOut, id, payload)
        }
        req.title?.let { writeId3v24Frame(frameOut, "TIT2", byteArrayOf(3) + it.toByteArray(Charsets.UTF_8)) }
        req.artist?.let { writeId3v24Frame(frameOut, "TPE1", byteArrayOf(3) + it.toByteArray(Charsets.UTF_8)) }
        req.album?.let { writeId3v24Frame(frameOut, "TALB", byteArrayOf(3) + it.toByteArray(Charsets.UTF_8)) }
        req.trackNumber?.takeIf { it > 0 }?.let {
            writeId3v24Frame(frameOut, "TRCK", byteArrayOf(3) + it.toString().toByteArray(Charsets.UTF_8))
        }
        if (req.replaceArtwork && req.artworkBytes != null && req.artworkBytes.isNotEmpty()) {
            val mime = if (req.artworkBytes.size >= 4 && req.artworkBytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"
            val apic = ByteArrayOutputStream()
            apic.write(3) // UTF-8
            apic.write(mime.toByteArray(Charsets.ISO_8859_1))
            apic.write(0)
            apic.write(3) // front cover
            apic.write(0) // empty desc
            apic.write(req.artworkBytes)
            writeId3v24Frame(frameOut, "APIC", apic.toByteArray())
        }

        val body = frameOut.toByteArray()
        dst.outputStream().use { out ->
            out.write("ID3".toByteArray(Charsets.US_ASCII))
            out.write(4)
            out.write(0)
            out.write(0)
            val hdrSize = ByteArray(4)
            writeSyncsafeIntTo(hdrSize, 0, body.size)
            out.write(hdrSize)
            out.write(body)
            if (audioStart < totalLen) {
                val copyBuf = ByteArray(STREAM_COPY_BUFFER_BYTES)
                RandomAccessFile(src, "r").use { raf ->
                    streamCopyRange(raf, audioStart, totalLen - audioStart, out, copyBuf)
                }
            }
        }
        return true
    }

    private fun streamCopyRange(
        raf: RandomAccessFile,
        startOffset: Long,
        byteCount: Long,
        out: java.io.OutputStream,
        buf: ByteArray
    ) {
        if (byteCount <= 0L) return
        raf.seek(startOffset)
        var remaining = byteCount
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val n = raf.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n.toLong()
        }
    }

    private fun writeId3v24Frame(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        if (id.length != 4) return
        out.write(id.toByteArray(Charsets.US_ASCII))
        val sizeBuf = ByteArray(4)
        writeSyncsafeIntTo(sizeBuf, 0, payload.size)
        out.write(sizeBuf)
        out.write(0)
        out.write(0)
        out.write(payload)
    }

    // --- MP4 / M4A / ALAC ---

    private const val STREAM_COPY_BUFFER_BYTES = 65_536
    private const val MAX_M4A_MOOV_BYTES = 16 * 1024 * 1024

    private data class Mp4TopBox(val type: String, val start: Long, val headerSize: Int, val end: Long)
    private data class BoxSlice(val type: String, val start: Int, val payloadStart: Int, val end: Int)

    private fun writeM4a(src: File, dst: File, req: TagEditRequest): Boolean {
        val topBoxes: List<Mp4TopBox>
        val moovBox: Mp4TopBox
        val moovBytes: ByteArray

        RandomAccessFile(src, "r").use { raf ->
            val totalLen = raf.length()
            topBoxes = scanTopLevelMp4Boxes(raf, totalLen) ?: return false
            moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return false
            val moovSize = moovBox.end - moovBox.start
            if (moovSize < moovBox.headerSize.toLong() || moovSize > MAX_M4A_MOOV_BYTES.toLong()) {
                return false
            }
            moovBytes = ByteArray(moovSize.toInt())
            raf.seek(moovBox.start)
            raf.readFully(moovBytes)
        }

        val moovSlice = BoxSlice("moov", 0, moovBox.headerSize, moovBytes.size)
        val newMoov = rebuildMoovWithTags(moovBytes, moovSlice, req) ?: return false
        val sizeDelta = newMoov.size.toLong() - moovBytes.size.toLong()

        // If moov precedes mdat (fast-start), patch stco / co64 chunk offsets inside newMoov by exact sizeDelta
        val hasMdatAfterMoov = topBoxes.any { it.type == "mdat" && moovBox.start < it.start }
        if (sizeDelta != 0L && hasMdatAfterMoov) {
            patchMp4ChunkOffsets(newMoov, 8, newMoov.size, sizeDelta)
        }

        val copyBuf = ByteArray(STREAM_COPY_BUFFER_BYTES)
        RandomAccessFile(src, "r").use { raf ->
            dst.outputStream().use { out ->
                for (box in topBoxes) {
                    if (box.type == "moov") {
                        out.write(newMoov)
                    } else {
                        streamCopyRange(raf, box.start, box.end - box.start, out, copyBuf)
                    }
                }
            }
        }
        return true
    }

    private fun scanTopLevelMp4Boxes(raf: RandomAccessFile, totalLen: Long): List<Mp4TopBox>? {
        val list = ArrayList<Mp4TopBox>()
        val hdr8 = ByteArray(8)
        val ext8 = ByteArray(8)
        var cursor = 0L

        while (cursor + 8L <= totalLen) {
            raf.seek(cursor)
            raf.readFully(hdr8)
            val size32 = ((hdr8[0].toLong() and 0xFFL) shl 24) or
                ((hdr8[1].toLong() and 0xFFL) shl 16) or
                ((hdr8[2].toLong() and 0xFFL) shl 8) or
                (hdr8[3].toLong() and 0xFFL)
            val type = String(hdr8, 4, 4, Charsets.ISO_8859_1)

            val totalSize: Long
            val headerLen: Int
            when (size32) {
                0L -> {
                    totalSize = totalLen - cursor
                    headerLen = 8
                }
                1L -> {
                    if (cursor + 16L > totalLen) return null
                    raf.readFully(ext8)
                    val extSize = readInt64BE(ext8, 0)
                    if (extSize < 16L) return null
                    totalSize = extSize
                    headerLen = 16
                }
                in 2L..7L -> return null
                else -> {
                    totalSize = size32
                    headerLen = 8
                }
            }
            if (totalSize < headerLen.toLong() || cursor + totalSize > totalLen) return null
            list.add(Mp4TopBox(type, cursor, headerLen, cursor + totalSize))
            cursor += totalSize
        }
        return if (list.isEmpty()) null else list
    }

    private fun rebuildMoovWithTags(bytes: ByteArray, moovBox: BoxSlice, req: TagEditRequest): ByteArray? {
        val moovChildren = parseMp4Children(bytes, moovBox.payloadStart, moovBox.end) ?: return null
        val udtaBox = moovChildren.firstOrNull { it.type == "udta" }
        val newUdtaBytes = rebuildUdta(bytes, udtaBox, req) ?: return null

        val payloadOut = ByteArrayOutputStream()
        var wroteUdta = false
        for (child in moovChildren) {
            if (child.type == "udta") {
                payloadOut.write(newUdtaBytes)
                wroteUdta = true
            } else {
                payloadOut.write(bytes, child.start, child.end - child.start)
            }
        }
        if (!wroteUdta) {
            payloadOut.write(newUdtaBytes)
        }
        return wrapMp4Box("moov", payloadOut.toByteArray())
    }

    private fun rebuildUdta(bytes: ByteArray, udtaBox: BoxSlice?, req: TagEditRequest): ByteArray? {
        val children = if (udtaBox != null) parseMp4Children(bytes, udtaBox.payloadStart, udtaBox.end) ?: return null else emptyList()
        val metaBox = children.firstOrNull { it.type == "meta" }
        val newMetaBytes = rebuildMeta(bytes, metaBox, req) ?: return null

        val payloadOut = ByteArrayOutputStream()
        var wroteMeta = false
        for (child in children) {
            if (child.type == "meta") {
                payloadOut.write(newMetaBytes)
                wroteMeta = true
            } else {
                payloadOut.write(bytes, child.start, child.end - child.start)
            }
        }
        if (!wroteMeta) payloadOut.write(newMetaBytes)
        return wrapMp4Box("udta", payloadOut.toByteArray())
    }

    private fun rebuildMeta(bytes: ByteArray, metaBox: BoxSlice?, req: TagEditRequest): ByteArray? {
        val preservedIlstItems = ArrayList<ByteArray>()
        val otherMetaChildren = ArrayList<ByteArray>()

        if (metaBox != null) {
            val startAfterFlags = if (metaBox.payloadStart + 4 <= metaBox.end) metaBox.payloadStart + 4 else metaBox.payloadStart
            val children = parseMp4Children(bytes, startAfterFlags, metaBox.end)
                ?: parseMp4Children(bytes, metaBox.payloadStart, metaBox.end)
                ?: return null
            for (child in children) {
                if (child.type == "ilst") {
                    val items = parseMp4Children(bytes, child.payloadStart, child.end) ?: emptyList()
                    for (item in items) {
                        val b0 = bytes[item.start + 4].toInt() and 0xFF
                        val suffix = String(bytes, item.start + 5, 3, Charsets.US_ASCII)
                        val skip = (b0 == 0xA9 && suffix == "nam" && req.title != null) ||
                            (b0 == 0xA9 && suffix == "ART" && req.artist != null) ||
                            (b0 == 0xA9 && suffix == "alb" && req.album != null) ||
                            (item.type == "trkn" && req.trackNumber != null) ||
                            (item.type == "covr" && req.replaceArtwork)
                        if (!skip) {
                            preservedIlstItems.add(bytes.copyOfRange(item.start, item.end))
                        }
                    }
                } else {
                    otherMetaChildren.add(bytes.copyOfRange(child.start, child.end))
                }
            }
        } else {
            // Minimal hdlr atom for valid meta box
            val hdlrPayload = ByteArrayOutputStream()
            hdlrPayload.write(ByteArray(8))
            hdlrPayload.write("mdir".toByteArray(Charsets.US_ASCII))
            hdlrPayload.write("appl".toByteArray(Charsets.US_ASCII))
            hdlrPayload.write(ByteArray(9))
            otherMetaChildren.add(wrapMp4Box("hdlr", hdlrPayload.toByteArray()))
        }

        val ilstOut = ByteArrayOutputStream()
        for (rawItem in preservedIlstItems) ilstOut.write(rawItem)
        req.title?.let { ilstOut.write(buildMp4TextAtom(0xA9, "nam", it)) }
        req.artist?.let { ilstOut.write(buildMp4TextAtom(0xA9, "ART", it)) }
        req.album?.let { ilstOut.write(buildMp4TextAtom(0xA9, "alb", it)) }
        req.trackNumber?.takeIf { it > 0 }?.let { trk ->
            val dataPayload = byteArrayOf(0, 0, ((trk ushr 8) and 0xFF).toByte(), (trk and 0xFF).toByte(), 0, 0, 0, 0)
            ilstOut.write(wrapMp4Box("trkn", buildMp4DataBox(0, dataPayload)))
        }
        if (req.replaceArtwork && req.artworkBytes != null && req.artworkBytes.isNotEmpty()) {
            val typeCode = if (req.artworkBytes.size >= 4 && req.artworkBytes[0] == 0x89.toByte()) 14 else 13
            ilstOut.write(wrapMp4Box("covr", buildMp4DataBox(typeCode, req.artworkBytes)))
        }

        val metaPayload = ByteArrayOutputStream()
        metaPayload.write(ByteArray(4)) // version + flags
        for (other in otherMetaChildren) metaPayload.write(other)
        metaPayload.write(wrapMp4Box("ilst", ilstOut.toByteArray()))
        return wrapMp4Box("meta", metaPayload.toByteArray())
    }

    private fun patchMp4ChunkOffsets(buf: ByteArray, start: Int, end: Int, delta: Long) {
        var cursor = start
        while (cursor + 8 <= end) {
            val size = readInt32BE(buf, cursor)
            if (size < 8 || cursor + size > end) break
            val type = String(buf, cursor + 4, 4, Charsets.ISO_8859_1)
            val payloadStart = cursor + 8
            val boxEnd = cursor + size
            when (type) {
                "stco" -> {
                    if (payloadStart + 8 <= boxEnd) {
                        val count = readInt32BE(buf, payloadStart + 4)
                        var p = payloadStart + 8
                        for (i in 0 until count) {
                            if (p + 4 > boxEnd) break
                            val oldOff = readInt32BE(buf, p).toLong() and 0xFFFFFFFFL
                            val newOff = (oldOff + delta).coerceAtLeast(0L)
                            writeInt32BETo(buf, p, newOff.toInt())
                            p += 4
                        }
                    }
                }
                "co64" -> {
                    if (payloadStart + 8 <= boxEnd) {
                        val count = readInt32BE(buf, payloadStart + 4)
                        var p = payloadStart + 8
                        for (i in 0 until count) {
                            if (p + 8 > boxEnd) break
                            val oldOff = readInt64BE(buf, p)
                            val newOff = (oldOff + delta).coerceAtLeast(0L)
                            writeInt64BETo(buf, p, newOff)
                            p += 8
                        }
                    }
                }
                "trak", "mdia", "minf", "stbl", "edts" -> {
                    patchMp4ChunkOffsets(buf, payloadStart, boxEnd, delta)
                }
            }
            cursor = boxEnd
        }
    }

    private fun parseMp4Children(bytes: ByteArray, start: Int, end: Int): List<BoxSlice>? {
        val list = ArrayList<BoxSlice>()
        var cursor = start
        while (cursor + 8 <= end) {
            val size32 = readInt32BE(bytes, cursor).toLong() and 0xFFFFFFFFL
            val type = String(bytes, cursor + 4, 4, Charsets.ISO_8859_1)
            val totalSize: Int
            val headerLen: Int
            when (size32) {
                0L -> {
                    totalSize = end - cursor
                    headerLen = 8
                }
                1L -> return null // 64-bit box header unsupported for moov tag rewrite
                in 2L..7L -> return null
                else -> {
                    totalSize = size32.toInt()
                    headerLen = 8
                }
            }
            if (totalSize < headerLen || cursor + totalSize > end) return null
            list.add(BoxSlice(type, cursor, cursor + headerLen, cursor + totalSize))
            cursor += totalSize
        }
        return list
    }

    private fun buildMp4TextAtom(firstByte: Int, suffix: String, text: String): ByteArray {
        val data = buildMp4DataBox(1, text.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream(8 + data.size)
        writeInt32BE(out, 8 + data.size)
        out.write(firstByte)
        out.write(suffix.toByteArray(Charsets.US_ASCII))
        out.write(data)
        return out.toByteArray()
    }

    private fun buildMp4DataBox(dataType: Int, payload: ByteArray): ByteArray {
        val inner = ByteArrayOutputStream(8 + payload.size)
        writeInt32BE(inner, dataType)
        writeInt32BE(inner, 0)
        inner.write(payload)
        return wrapMp4Box("data", inner.toByteArray())
    }

    private fun wrapMp4Box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        writeInt32BE(out, 8 + payload.size)
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun readSyncsafeInt(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0x7F) shl 21) or
            ((b[offset + 1].toInt() and 0x7F) shl 14) or
            ((b[offset + 2].toInt() and 0x7F) shl 7) or
            (b[offset + 3].toInt() and 0x7F)
    }

    private fun writeSyncsafeIntTo(b: ByteArray, offset: Int, v: Int) {
        b[offset] = ((v ushr 21) and 0x7F).toByte()
        b[offset + 1] = ((v ushr 14) and 0x7F).toByte()
        b[offset + 2] = ((v ushr 7) and 0x7F).toByte()
        b[offset + 3] = (v and 0x7F).toByte()
    }

    private fun readInt32LE(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeInt32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun readInt32BE(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt32BE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt32BETo(b: ByteArray, offset: Int, v: Int) {
        b[offset] = ((v ushr 24) and 0xFF).toByte()
        b[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        b[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        b[offset + 3] = (v and 0xFF).toByte()
    }

    private fun readInt64BE(b: ByteArray, offset: Int): Long {
        var r = 0L
        for (i in 0 until 8) {
            r = (r shl 8) or (b[offset + i].toLong() and 0xFFL)
        }
        return r
    }

    private fun writeInt64BETo(b: ByteArray, offset: Int, v: Long) {
        for (i in 0 until 8) {
            b[offset + i] = ((v ushr ((7 - i) * 8)) and 0xFFL).toByte()
        }
    }
}
