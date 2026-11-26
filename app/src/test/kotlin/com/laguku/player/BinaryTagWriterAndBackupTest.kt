package com.laguku.player

import com.laguku.player.data.LagukuBackupManager
import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.metadata.BinaryTagWriter
import com.laguku.player.metadata.TagEditRequest
import com.laguku.player.model.Playlist
import com.laguku.player.model.Track
import com.laguku.player.service.ActiveOutputRoute
import com.laguku.player.service.AudioDeviceProfile
import com.laguku.player.service.BluetoothProfileManager
import com.laguku.player.service.ClickGesture
import com.laguku.player.service.DeviceRouteType
import com.laguku.player.service.MediaAction
import com.laguku.player.service.MorningAlarmConfig
import com.laguku.player.service.MultiClickMediaMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class BinaryTagWriterAndBackupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun writesFlacAndMp3TagsPreservingAudioPayloadAndExtraTags() {
        // 1. FLAC with STREAMINFO + Vorbis(REPLAYGAIN, LYRICS, CHAPTER) + fake audio frames
        val flacFile = File(tempFolder.root, "sample.flac")
        val streamInfo = ByteArray(34) { 0x11 }
        val vorbis = buildVorbisBlock(
            "LagukuTest",
            listOf(
                "TITLE=Old Title",
                "REPLAYGAIN_TRACK_GAIN=-4.50 dB",
                "LYRICS=[00:01.00]Kept lyrics",
                "CHAPTER001=00:01:05.500",
                "CHAPTER001NAME=Solo"
            )
        )
        val audioPayload = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x12, 0x34, 0x56, 0x78)
        val flacOut = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            writeFlacBlock(this, false, 0, streamInfo)
            writeFlacBlock(this, true, 4, vorbis)
            write(audioPayload)
        }
        flacFile.writeBytes(flacOut.toByteArray())

        val newArt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        assertTrue(
            BinaryTagWriter.writeTags(
                flacFile,
                TagEditRequest(title = "New Glass", artist = "Hana", album = "Prism", trackNumber = 7, artworkBytes = newArt, replaceArtwork = true)
            )
        )
        val flacRead = BinaryTagReader.read(flacFile)
        assertEquals("New Glass", flacRead.title)
        assertEquals("Hana", flacRead.artist)
        assertEquals("Prism", flacRead.album)
        assertEquals(7, flacRead.trackNumber)
        assertEquals(-4.50f, flacRead.replayGainDb!!, 0.01f)
        assertEquals("[00:01.00]Kept lyrics", flacRead.lyrics)
        assertArrayEquals(newArt, flacRead.artworkBytes)
        assertEquals(1, flacRead.chapters.size)
        assertEquals(65_500L, flacRead.chapters[0].startMs)
        assertEquals("Solo", flacRead.chapters[0].title)
        val finalFlacBytes = flacFile.readBytes()
        assertArrayEquals(audioPayload, finalFlacBytes.copyOfRange(finalFlacBytes.size - audioPayload.size, finalFlacBytes.size))

        // 2. MP3
        val mp3File = File(tempFolder.root, "sample.mp3")
        val mp3Audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 9, 8, 7)
        val id3Body = ByteArrayOutputStream()
        writeId3v23Frame(id3Body, "TIT2", byteArrayOf(3) + "Old MP3".toByteArray(Charsets.UTF_8))
        val mp3Out = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.US_ASCII))
            write(3); write(0); write(0)
            writeSyncsafe(this, id3Body.size())
            write(id3Body.toByteArray())
            write(mp3Audio)
        }
        mp3File.writeBytes(mp3Out.toByteArray())
        assertTrue(BinaryTagWriter.writeTags(mp3File, TagEditRequest(title = "Clean MP3", artist = "Ren", album = "Wave", trackNumber = 3)))
        val mp3Read = BinaryTagReader.read(mp3File)
        assertEquals("Clean MP3", mp3Read.title)
        assertEquals("Ren", mp3Read.artist)
        assertEquals("Wave", mp3Read.album)
        assertEquals(3, mp3Read.trackNumber)
        val finalMp3Bytes = mp3File.readBytes()
        assertArrayEquals(mp3Audio, finalMp3Bytes.copyOfRange(finalMp3Bytes.size - mp3Audio.size, finalMp3Bytes.size))
    }

    @Test
    fun writesM4aTagsWithMoovBeforeMdatUpdatingStcoChunkOffsetsAndReadsChpl() {
        val m4aFile = File(tempFolder.root, "chapters.m4a")
        val ftyp = wrapMp4Box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8))

        // Build moov with stco pointing to mdat payload (placeholder offset patched after knowing moov size)
        val stcoPlaceholderOffset = 0
        val stcoPayload = ByteArrayOutputStream().apply {
            write(ByteArray(4)) // ver/flags
            writeInt32BE(this, 1) // 1 chunk
            writeInt32BE(this, stcoPlaceholderOffset)
        }.toByteArray()
        val stco = wrapMp4Box("stco", stcoPayload)
        val stbl = wrapMp4Box("stbl", stco)
        val minf = wrapMp4Box("minf", stbl)
        val mdia = wrapMp4Box("mdia", minf)
        val trak = wrapMp4Box("trak", mdia)

        // chpl box inside udta
        val chplPayload = ByteArrayOutputStream().apply {
            write(byteArrayOf(1, 0, 0, 0, 0)) // version 1 + flags + reserved
            writeInt32BE(this, 1) // 1 chapter
            writeInt64BE(this, 15_000L * 10_000L) // 15_000 ms in 100ns units
            val titleBytes = "Intro".toByteArray(Charsets.UTF_8)
            write(titleBytes.size)
            write(titleBytes)
        }.toByteArray()
        val chpl = wrapMp4Box("chpl", chplPayload)
        val ilst = wrapMp4Box("ilst", wrapMp4TextAtom(0xA9, "nam", "Short"))
        val meta = wrapMp4Box("meta", ByteArray(4) + ilst)
        val udta = wrapMp4Box("udta", chpl + meta)
        val moovRaw = wrapMp4Box("moov", trak + udta)

        val mdatStartBefore = ftyp.size + moovRaw.size + 8
        // Patch stco in moovRaw to point exactly to mdatStartBefore
        val stcoMarker = "stco".toByteArray(Charsets.US_ASCII)
        val stcoIdx = indexOfBytes(moovRaw, stcoMarker)
        writeInt32BETo(moovRaw, stcoIdx + 12, mdatStartBefore)

        val mdat = wrapMp4Box("mdat", byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        m4aFile.writeBytes(ftyp + moovRaw + mdat)

        val beforeRead = BinaryTagReader.read(m4aFile)
        assertEquals(1, beforeRead.chapters.size)
        assertEquals(15_000L, beforeRead.chapters[0].startMs)
        assertEquals("Intro", beforeRead.chapters[0].title)

        // Write a much longer title so moov grows and mdat shifts
        assertTrue(BinaryTagWriter.writeTags(m4aFile, TagEditRequest(title = "Much Longer Album Track Title For Offset Test", artist = "Yuna")))
        val afterRead = BinaryTagReader.read(m4aFile)
        assertEquals("Much Longer Album Track Title For Offset Test", afterRead.title)
        assertEquals("Yuna", afterRead.artist)
        assertEquals(1, afterRead.chapters.size)

        // Verify stco was patched to the new mdat payload start!
        val updatedBytes = m4aFile.readBytes()
        val newStcoIdx = indexOfBytes(updatedBytes, stcoMarker)
        val newMdatDataStart = indexOfBytes(updatedBytes, "mdat".toByteArray(Charsets.US_ASCII)) + 4
        val patchedStcoVal = readInt32BE(updatedBytes, newStcoIdx + 12)
        assertEquals(newMdatDataStart, patchedStcoVal)
    }

    @Test
    fun writesM4aStreamingCopyForFastStartAndTrailingMoovUpdatingStcoAndCo64() {
        val mdatPayload = ByteArray(260 * 1024) { i -> ((i * 31 + 7) and 0xFF).toByte() }
        val ftyp = wrapMp4Box("ftyp", "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        val mdatBox = wrapMp4Box("mdat", mdatPayload)

        fun buildMoov(stcoOffset: Int, co64Offset: Long, title: String): ByteArray {
            val stco = wrapMp4Box(
                "stco",
                ByteArrayOutputStream().apply {
                    write(ByteArray(4))
                    writeInt32BE(this, 1)
                    writeInt32BE(this, stcoOffset)
                }.toByteArray()
            )
            val co64 = wrapMp4Box(
                "co64",
                ByteArrayOutputStream().apply {
                    write(ByteArray(4))
                    writeInt32BE(this, 1)
                    writeInt64BE(this, co64Offset)
                }.toByteArray()
            )
            val stbl = wrapMp4Box("stbl", stco + co64)
            val trak = wrapMp4Box("trak", wrapMp4Box("mdia", wrapMp4Box("minf", stbl)))
            val ilst = wrapMp4Box("ilst", wrapMp4TextAtom(0xA9, "nam", title))
            val udta = wrapMp4Box("udta", wrapMp4Box("meta", ByteArray(4) + ilst))
            return wrapMp4Box("moov", trak + udta)
        }

        // Case 1: Fast-start (moov BEFORE mdat)
        val fastFile = File(tempFolder.root, "fast_start.m4a")
        val initialMoov = buildMoov(0, 0L, "Short")
        val fastMdatDataStartBefore = ftyp.size + initialMoov.size + 8
        val patchedInitMoov = buildMoov(fastMdatDataStartBefore, fastMdatDataStartBefore.toLong() + 1024L, "Short")
        fastFile.writeBytes(ftyp + patchedInitMoov + mdatBox)

        assertTrue(BinaryTagWriter.writeTags(fastFile, TagEditRequest(title = "Streaming Fast-Start Extended Title", artist = "Sora")))
        val fastMeta = BinaryTagReader.read(fastFile)
        assertEquals("Streaming Fast-Start Extended Title", fastMeta.title)
        assertEquals("Sora", fastMeta.artist)

        val fastBytes = fastFile.readBytes()
        val fastNewMdatIdx = indexOfBytes(fastBytes, "mdat".toByteArray(Charsets.US_ASCII)) + 4
        val fastStcoIdx = indexOfBytes(fastBytes, "stco".toByteArray(Charsets.US_ASCII))
        val fastCo64Idx = indexOfBytes(fastBytes, "co64".toByteArray(Charsets.US_ASCII))
        assertEquals(fastNewMdatIdx, readInt32BE(fastBytes, fastStcoIdx + 12))
        assertEquals(fastNewMdatIdx.toLong() + 1024L, readInt64BE(fastBytes, fastCo64Idx + 12))
        assertArrayEquals(mdatPayload, fastBytes.copyOfRange(fastNewMdatIdx, fastNewMdatIdx + mdatPayload.size))

        // Case 2: Trailing moov (mdat BEFORE moov) -> stco & co64 must remain untouched
        val trailingFile = File(tempFolder.root, "trailing_moov.m4a")
        val trailMdatDataStart = ftyp.size + 8
        val trailMoov = buildMoov(trailMdatDataStart, trailMdatDataStart.toLong() + 2048L, "Short")
        trailingFile.writeBytes(ftyp + mdatBox + trailMoov)

        assertTrue(BinaryTagWriter.writeTags(trailingFile, TagEditRequest(title = "Streaming Trailing Moov Extended Title", album = "Prism")))
        val trailMeta = BinaryTagReader.read(trailingFile)
        assertEquals("Streaming Trailing Moov Extended Title", trailMeta.title)
        assertEquals("Prism", trailMeta.album)

        val trailBytes = trailingFile.readBytes()
        val trailNewMdatIdx = indexOfBytes(trailBytes, "mdat".toByteArray(Charsets.US_ASCII)) + 4
        val trailStcoIdx = indexOfBytes(trailBytes, "stco".toByteArray(Charsets.US_ASCII))
        val trailCo64Idx = indexOfBytes(trailBytes, "co64".toByteArray(Charsets.US_ASCII))
        assertEquals(trailMdatDataStart, readInt32BE(trailBytes, trailStcoIdx + 12))
        assertEquals(trailMdatDataStart.toLong() + 2048L, readInt64BE(trailBytes, trailCo64Idx + 12))
        assertArrayEquals(mdatPayload, trailBytes.copyOfRange(trailNewMdatIdx, trailNewMdatIdx + mdatPayload.size))
    }

    @Test
    fun backupExportImportRoundTripAndRejectsPathTraversal() {
        val root = tempFolder.newFolder("music_root")
        val okDir = File(root, "Rock").apply { mkdirs() }
        val okSong = File(okDir, "song.flac").apply { writeBytes(byteArrayOf(0)) }
        val outsideDir = tempFolder.newFolder("outside")
        val outsideSong = File(outsideDir, "secret.mp3").apply { writeBytes(byteArrayOf(0)) }

        val stateStore = PlaybackStateStore(File(tempFolder.root, "state.json"))
        stateStore.setLyricOffsetMs(okSong.absolutePath, 200L)
        stateStore.togglePinnedFolder(okDir.absolutePath)
        stateStore.setAmoledNightMode(true)
        stateStore.setMorningAlarm(MorningAlarmConfig(enabled = true, hour = 6, minute = 45, fadeInSeconds = 30))

        val btManager = BluetoothProfileManager(File(tempFolder.root, "bt.json"))
        btManager.saveProfile(
            AudioDeviceProfile(
                deviceKey = "BT:Buds",
                displayName = "Buds",
                deviceType = DeviceRouteType.BLUETOOTH,
                eqEnabled = true,
                eqBandsDb = FloatArray(10) { 3f },
                crossfeedLevel = 0.3f,
                safeStartVolumePercent = 50,
                lyricLatencyOffsetMs = -180L
            )
        )
        val clickMapper = MultiClickMediaMapper().apply {
            setMapping(ClickGesture.DOUBLE, MediaAction.SEEK_FORWARD_10S)
        }
        val playlists = listOf(
            Playlist("Mix", File(root, "mix.m3u8").absolutePath, listOf(Track(1L, "Song", "Art", "Alb", path = okSong.absolutePath)))
        )

        val json = LagukuBackupManager.exportToJson(stateStore, btManager, clickMapper, playlists, root)

        // Inject malicious path traversal into JSON and restore into fresh instances
        val injectedJson = json.replace(
            "\"pinnedFolders\":[",
            "\"pinnedFolders\":[\"${outsideDir.absolutePath.replace("\\", "\\\\")}\",\"../../etc\","
        )

        val state2 = PlaybackStateStore(File(tempFolder.root, "state2.json"))
        val bt2 = BluetoothProfileManager(File(tempFolder.root, "bt2.json"))
        val mapper2 = MultiClickMediaMapper()
        val plOutDir = File(root, "playlists")

        val res = LagukuBackupManager.importFromJson(injectedJson, state2, bt2, mapper2, plOutDir, root)
        assertTrue(res.success)
        assertEquals(1, res.profilesImported)
        assertEquals(1, res.playlistsImported)
        assertTrue(res.rejectedPathsCount >= 2)
        assertTrue(state2.isAmoledNightMode())
        assertEquals(6, state2.getMorningAlarm().hour)
        assertEquals(MediaAction.SEEK_FORWARD_10S, mapper2.getMapping(ClickGesture.DOUBLE))
        assertEquals(setOf(okDir.absolutePath), state2.getPinnedFolders())
        assertFalse(state2.getPinnedFolders().contains(outsideSong.parent.orEmpty()))
        assertEquals(3f, bt2.getProfileForRoute(ActiveOutputRoute("BT:Buds", "Buds", DeviceRouteType.BLUETOOTH)).eqBandsDb[0], 0.01f)
    }

    private fun buildVorbisBlock(vendor: String, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vb = vendor.toByteArray(Charsets.UTF_8)
        writeInt32LE(out, vb.size); out.write(vb)
        writeInt32LE(out, comments.size)
        for (c in comments) {
            val cb = c.toByteArray(Charsets.UTF_8)
            writeInt32LE(out, cb.size); out.write(cb)
        }
        return out.toByteArray()
    }

    private fun writeFlacBlock(out: ByteArrayOutputStream, isLast: Boolean, type: Int, payload: ByteArray) {
        out.write((if (isLast) 0x80 else 0x00) or (type and 0x7F))
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
    }

    private fun writeId3v23Frame(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        writeInt32BE(out, payload.size)
        out.write(0); out.write(0)
        out.write(payload)
    }

    private fun writeSyncsafe(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 21) and 0x7F); out.write((v ushr 14) and 0x7F)
        out.write((v ushr 7) and 0x7F); out.write(v and 0x7F)
    }

    private fun wrapMp4Box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        writeInt32BE(out, 8 + payload.size)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun wrapMp4TextAtom(firstByte: Int, suffix: String, text: String): ByteArray {
        val data = wrapMp4Box("data", ByteArray(8).apply { this[3] = 1 } + text.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream(8 + data.size)
        writeInt32BE(out, 8 + data.size)
        out.write(firstByte); out.write(suffix.toByteArray(Charsets.US_ASCII))
        out.write(data)
        return out.toByteArray()
    }

    private fun writeInt32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }

    private fun writeInt32BE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeInt64BE(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) out.write(((v ushr ((7 - i) * 8)) and 0xFFL).toInt())
    }

    private fun writeInt32BETo(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte(); b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte(); b[off + 3] = (v and 0xFF).toByte()
    }

    private fun readInt32BE(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)
    }

    private fun readInt64BE(b: ByteArray, off: Int): Long {
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or (b[off + i].toLong() and 0xFFL)
        return r
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        for (i in 0..(haystack.size - needle.size)) {
            var ok = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { ok = false; break }
            }
            if (ok) return i
        }
        return -1
    }
}
