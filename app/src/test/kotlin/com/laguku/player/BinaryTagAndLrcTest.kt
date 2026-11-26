package com.laguku.player

import com.laguku.player.lyrics.LrcParser
import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.model.AudioQualityInfo
import com.laguku.player.model.LyricLine
import com.laguku.player.model.LyricSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class BinaryTagAndLrcTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // --- AudioModels ---

    @Test
    fun formatBadgeFormatsLosslessAndLossyCleanly() {
        val flac = AudioQualityInfo(codec = "flac", bitDepth = 24, sampleRateHz = 48000, bitrateKbps = 0)
        assertEquals("FLAC \u2022 24-bit \u2022 48 kHz", flac.formatBadge())

        val cdFlac = AudioQualityInfo(codec = "FLAC", bitDepth = 16, sampleRateHz = 44100, bitrateKbps = 900)
        assertEquals("FLAC \u2022 16-bit \u2022 44.1 kHz", cdFlac.formatBadge())

        val opus = AudioQualityInfo(codec = "opus", bitDepth = 16, sampleRateHz = 48000, bitrateKbps = 160)
        assertEquals("OPUS \u2022 160 kbps", opus.formatBadge())
    }

    // --- FLAC ---

    @Test
    fun readsFlacVorbisCommentAndFrontCoverPicture() {
        val frontArt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val otherArt = byteArrayOf(9, 8, 7, 6)

        val vorbisPayload = buildVorbisCommentBlock(
            "LagukuTest",
            listOf(
                "TITLE=Midnight Glass",
                "ARTIST=Sora",
                "ALBUM=Refractions",
                "TRACKNUMBER=04/12",
                "REPLAYGAIN_TRACK_GAIN=-6.25 dB",
                "LYRICS=[00:01.00]Glowing edges"
            )
        )
        val picOther = buildFlacPictureBlock(pictureType = 0, mime = "image/png", image = otherArt)
        val picFront = buildFlacPictureBlock(pictureType = 3, mime = "image/jpeg", image = frontArt)

        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        writeFlacMetaBlock(out, isLast = false, type = 4, payload = vorbisPayload)
        writeFlacMetaBlock(out, isLast = false, type = 6, payload = picOther)
        writeFlacMetaBlock(out, isLast = true, type = 6, payload = picFront)

        val meta = BinaryTagReader.readFromBytes(out.toByteArray(), "flac")
        assertEquals("Midnight Glass", meta.title)
        assertEquals("Sora", meta.artist)
        assertEquals("Refractions", meta.album)
        assertEquals(4, meta.trackNumber)
        assertEquals(-6.25f, meta.replayGainDb!!, 0.001f)
        assertEquals("[00:01.00]Glowing edges", meta.lyrics)
        assertArrayEquals(frontArt, meta.artworkBytes)
    }

    // --- OGG / Opus ---

    @Test
    fun readsOggOpusMultiPageCommentsAndBase64Artwork() {
        val artwork = ByteArray(400) { (it % 251).toByte() }
        val picBlock = buildFlacPictureBlock(pictureType = 3, mime = "image/jpeg", image = artwork)
        val b64Pic = Base64.getEncoder().encodeToString(picBlock)

        val opusHead = "OpusHead".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 2, 0, 0, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val opusTagsBody = buildVorbisCommentBlock(
            "libopus",
            listOf(
                "TITLE=Opus Horizon",
                "ARTIST=Kaze",
                "ALBUM=Wind",
                "TRACKNUMBER=2",
                "REPLAYGAIN_TRACK_GAIN=+1.75 dB",
                "UNSYNCEDLYRICS=Static wind lyrics",
                "METADATA_BLOCK_PICTURE=$b64Pic"
            )
        )
        val opusTags = "OpusTags".toByteArray(Charsets.US_ASCII) + opusTagsBody

        val oggBytes = ByteArrayOutputStream()
        // Page 0: OpusHead
        writeOggPage(oggBytes, headerType = 0x02, pageSeq = 0, segments = segmentPacket(opusHead), payload = opusHead)
        // Split OpusTags across two pages to test multi-page continuation!
        val splitPoint = 255
        val part1 = opusTags.copyOfRange(0, splitPoint)
        val part2 = opusTags.copyOfRange(splitPoint, opusTags.size)
        writeOggPage(oggBytes, headerType = 0x00, pageSeq = 1, segments = byteArrayOf(255.toByte()), payload = part1)
        writeOggPage(oggBytes, headerType = 0x01, pageSeq = 2, segments = segmentPacket(part2), payload = part2)

        val meta = BinaryTagReader.readFromBytes(oggBytes.toByteArray(), "opus")
        assertEquals("Opus Horizon", meta.title)
        assertEquals("Kaze", meta.artist)
        assertEquals("Wind", meta.album)
        assertEquals(2, meta.trackNumber)
        assertEquals(1.75f, meta.replayGainDb!!, 0.001f)
        assertEquals("Static wind lyrics", meta.lyrics)
        assertArrayEquals(artwork, meta.artworkBytes)
    }

    @Test
    fun readsOggVorbisComments() {
        val idPacket = byteArrayOf(0x01) + "vorbis".toByteArray(Charsets.US_ASCII) + ByteArray(23)
        val commentBody = buildVorbisCommentBlock(
            "AOtuV",
            listOf("TITLE=Vorbis Dawn", "ARTIST=Aoi", "ALBUM=Sky", "TRACKNUMBER=7")
        )
        val commentPacket = byteArrayOf(0x03) + "vorbis".toByteArray(Charsets.US_ASCII) + commentBody

        val oggBytes = ByteArrayOutputStream()
        writeOggPage(oggBytes, headerType = 0x02, pageSeq = 0, segments = segmentPacket(idPacket), payload = idPacket)
        writeOggPage(oggBytes, headerType = 0x00, pageSeq = 1, segments = segmentPacket(commentPacket), payload = commentPacket)

        val meta = BinaryTagReader.readFromBytes(oggBytes.toByteArray(), "ogg")
        assertEquals("Vorbis Dawn", meta.title)
        assertEquals("Aoi", meta.artist)
        assertEquals("Sky", meta.album)
        assertEquals(7, meta.trackNumber)
    }

    // --- MP3 ID3v2.3 & ID3v2.4 & WAV ---

    @Test
    fun readsId3v23EncodingsAndFrames() {
        val art = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 10, 20, 30)
        val frames = ByteArrayOutputStream()
        // ISO-8859-1
        writeId3Frame(frames, "TIT2", byteArrayOf(0) + "Retro Signal".toByteArray(Charsets.ISO_8859_1), syncsafe = false)
        // UTF-16LE with BOM
        writeId3Frame(
            frames,
            "TPE1",
            byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + "Yuki \u2605".toByteArray(Charsets.UTF_16LE),
            syncsafe = false
        )
        // UTF-16BE without BOM
        writeId3Frame(frames, "TALB", byteArrayOf(2) + "Neon City".toByteArray(Charsets.UTF_16BE), syncsafe = false)
        // UTF-8
        writeId3Frame(frames, "TRCK", byteArrayOf(3) + "09/14".toByteArray(Charsets.UTF_8), syncsafe = false)
        // TXXX REPLAYGAIN_TRACK_GAIN in UTF-16LE
        val txxxPayload = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) +
            "REPLAYGAIN_TRACK_GAIN".toByteArray(Charsets.UTF_16LE) +
            byteArrayOf(0, 0, 0xFF.toByte(), 0xFE.toByte()) +
            "-3.40 dB".toByteArray(Charsets.UTF_16LE)
        writeId3Frame(frames, "TXXX", txxxPayload, syncsafe = false)
        // USLT
        val usltPayload = byteArrayOf(3) + "eng".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
            "Line one\nLine two".toByteArray(Charsets.UTF_8)
        writeId3Frame(frames, "USLT", usltPayload, syncsafe = false)
        // APIC (front cover type = 3)
        val apicPayload = byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 3) + "cover".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + art
        writeId3Frame(frames, "APIC", apicPayload, syncsafe = false)

        val mp3 = wrapId3Tag(versionMajor = 3, body = frames.toByteArray())
        val meta = BinaryTagReader.readFromBytes(mp3, "mp3")
        assertEquals("Retro Signal", meta.title)
        assertEquals("Yuki \u2605", meta.artist)
        assertEquals("Neon City", meta.album)
        assertEquals(9, meta.trackNumber)
        assertEquals(-3.40f, meta.replayGainDb!!, 0.001f)
        assertEquals("Line one\nLine two", meta.lyrics)
        assertArrayEquals(art, meta.artworkBytes)
    }

    @Test
    fun readsId3v24WithSyltAndWavId3Chunk() {
        val art = byteArrayOf(1, 2, 3, 4, 5, 6)
        val frames = ByteArrayOutputStream()
        writeId3Frame(frames, "TIT2", byteArrayOf(3) + "Crystal Wave".toByteArray(Charsets.UTF_8), syncsafe = true)
        writeId3Frame(frames, "TPE1", byteArrayOf(3) + "Haru".toByteArray(Charsets.UTF_8), syncsafe = true)
        writeId3Frame(frames, "TALB", byteArrayOf(3) + "Tides".toByteArray(Charsets.UTF_8), syncsafe = true)
        writeId3Frame(frames, "TRCK", byteArrayOf(3) + "1".toByteArray(Charsets.UTF_8), syncsafe = true)

        // SYLT (encoding=3, lang=eng, tsFormat=2 ms, contentType=1 lyrics, desc="", entries)
        val sylt = ByteArrayOutputStream()
        sylt.write(3)
        sylt.write("eng".toByteArray(Charsets.US_ASCII))
        sylt.write(2) // ms
        sylt.write(1) // lyrics
        sylt.write(0) // empty desc terminator
        sylt.write("First line".toByteArray(Charsets.UTF_8))
        sylt.write(0)
        writeInt32BE(sylt, 12_500)
        sylt.write("Second line".toByteArray(Charsets.UTF_8))
        sylt.write(0)
        writeInt32BE(sylt, 25_000)
        writeId3Frame(frames, "SYLT", sylt.toByteArray(), syncsafe = true)

        val apicPayload = byteArrayOf(3) + "image/png".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, 3, 0) + art
        writeId3Frame(frames, "APIC", apicPayload, syncsafe = true)

        val id3Tag = wrapId3Tag(versionMajor = 4, body = frames.toByteArray())

        // Also wrap in WAV RIFF..WAVE with an odd-sized dummy chunk to test padding alignment!
        val wav = ByteArrayOutputStream()
        wav.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeInt32LE(wav, 0) // size placeholder
        wav.write("WAVE".toByteArray(Charsets.US_ASCII))
        // Odd-sized "fmt " chunk (17 bytes + 1 pad byte)
        wav.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeInt32LE(wav, 17)
        wav.write(ByteArray(17))
        wav.write(0) // RIFF odd-chunk padding
        // "id3 " chunk
        wav.write("id3 ".toByteArray(Charsets.US_ASCII))
        writeInt32LE(wav, id3Tag.size)
        wav.write(id3Tag)

        val meta = BinaryTagReader.readFromBytes(wav.toByteArray(), "wav")
        assertEquals("Crystal Wave", meta.title)
        assertEquals("Haru", meta.artist)
        assertEquals("Tides", meta.album)
        assertEquals(1, meta.trackNumber)
        assertEquals("[00:12.50]First line\n[00:25.00]Second line", meta.lyrics)
        assertArrayEquals(art, meta.artworkBytes)
    }

    // --- MP4 / M4A / ALAC ---

    @Test
    fun readsMp4M4aAtomsAndFreeformReplayGain() {
        val art = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33)

        val ilstChildren = ByteArrayOutputStream()
        ilstChildren.write(mp4IlstTextItem(byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()), "ALAC Velvet"))
        ilstChildren.write(mp4IlstTextItem(byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()), "Mina"))
        ilstChildren.write(mp4IlstTextItem(byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()), "Prism"))
        ilstChildren.write(mp4IlstTextItem(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), "[00:05.00]Apple lossless"))

        // trkn: 8-byte binary data (00 00 | track 00 05 | total 00 0A | 00 00)
        val trknData = mp4DataBox(dataType = 0, payload = byteArrayOf(0, 0, 0, 5, 0, 10, 0, 0))
        ilstChildren.write(mp4Box("trkn".toByteArray(Charsets.US_ASCII), trknData))

        // covr
        val covrData = mp4DataBox(dataType = 13, payload = art)
        ilstChildren.write(mp4Box("covr".toByteArray(Charsets.US_ASCII), covrData))

        // ----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN
        val meanBox = mp4Box("mean".toByteArray(Charsets.US_ASCII), ByteArray(4) + "com.apple.iTunes".toByteArray(Charsets.UTF_8))
        val nameBox = mp4Box("name".toByteArray(Charsets.US_ASCII), ByteArray(4) + "REPLAYGAIN_TRACK_GAIN".toByteArray(Charsets.UTF_8))
        val rgData = mp4DataBox(dataType = 1, payload = "-4.80 dB".toByteArray(Charsets.UTF_8))
        ilstChildren.write(mp4Box("----".toByteArray(Charsets.US_ASCII), meanBox + nameBox + rgData))

        val ilst = mp4Box("ilst".toByteArray(Charsets.US_ASCII), ilstChildren.toByteArray())
        // meta is FullBox: 4 zero bytes before ilst
        val meta = mp4Box("meta".toByteArray(Charsets.US_ASCII), ByteArray(4) + ilst)
        val udta = mp4Box("udta".toByteArray(Charsets.US_ASCII), meta)
        val moov = mp4Box("moov".toByteArray(Charsets.US_ASCII), udta)
        val ftyp = mp4Box("ftyp".toByteArray(Charsets.US_ASCII), "M4A ".toByteArray(Charsets.US_ASCII) + ByteArray(8))
        val mdat = mp4Box("mdat".toByteArray(Charsets.US_ASCII), ByteArray(16))

        val fileBytes = ftyp + mdat + moov
        val parsed = BinaryTagReader.readFromBytes(fileBytes, "m4a")
        assertEquals("ALAC Velvet", parsed.title)
        assertEquals("Mina", parsed.artist)
        assertEquals("Prism", parsed.album)
        assertEquals(5, parsed.trackNumber)
        assertEquals(-4.80f, parsed.replayGainDb!!, 0.001f)
        assertEquals("[00:05.00]Apple lossless", parsed.lyrics)
        assertArrayEquals(art, parsed.artworkBytes)
    }

    // --- Sidecar File Fallback ---

    @Test
    fun fileReadFallsBackToLrcAndCoverOrFolderJpg() {
        val dir = tempFolder.newFolder("album_dir")
        val audioFile = File(dir, "01 - Moonbeam.flac")

        // Write valid FLAC without lyrics or artwork
        val vorbis = buildVorbisCommentBlock("Test", listOf("TITLE=Moonbeam"))
        val flacOut = ByteArrayOutputStream()
        flacOut.write("fLaC".toByteArray(Charsets.US_ASCII))
        writeFlacMetaBlock(flacOut, isLast = true, type = 4, payload = vorbis)
        audioFile.writeBytes(flacOut.toByteArray())

        // Sidecar .lrc and folder.jpg
        File(dir, "01 - Moonbeam.lrc").writeText("\uFEFF[00:02.00]Sidecar lyric", Charsets.UTF_8)
        val folderArt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 8, 9)
        File(dir, "folder.jpg").writeBytes(folderArt)

        val meta1 = BinaryTagReader.read(audioFile)
        assertEquals("Moonbeam", meta1.title)
        assertEquals("[00:02.00]Sidecar lyric", meta1.lyrics)
        assertArrayEquals(folderArt, meta1.artworkBytes)

        // cover.jpg takes precedence over folder.jpg
        val coverArt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        File(dir, "cover.jpg").writeBytes(coverArt)
        val meta2 = BinaryTagReader.read(audioFile)
        assertArrayEquals(coverArt, meta2.artworkBytes)
    }

    // --- Security / Corruption / OOM Resistance ---

    @Test
    fun handlesCorruptedTruncatedAndOversizedPayloadsWithoutOomOrHang() {
        // 1. Truncated headers
        assertEquals(0, BinaryTagReader.readFromBytes(byteArrayOf(), "mp3").let { if (it.title == null) 0 else 1 })
        BinaryTagReader.readFromBytes("fLaC".toByteArray(), "flac")
        BinaryTagReader.readFromBytes("OggS\u0000\u0002".toByteArray(), "ogg")
        BinaryTagReader.readFromBytes("ID3\u0004\u0000\u0000".toByteArray(), "mp3")
        BinaryTagReader.readFromBytes("RIFF\u0010\u0000\u0000\u0000WAVEid3 ".toByteArray(), "wav")
        BinaryTagReader.readFromBytes(byteArrayOf(0, 0, 0, 12, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()), "m4a")

        // 2. FLAC oversized picture (> 8MB) & zero-size block followed by valid Vorbis comment
        val hugeFlac = ByteArrayOutputStream()
        hugeFlac.write("fLaC".toByteArray())
        // Zero-size block (type 1, size 0) -> must not infinite loop!
        writeFlacMetaBlock(hugeFlac, isLast = false, type = 1, payload = ByteArray(0))
        // Claimed 10MB picture block (exceeds MAX_ARTWORK_BYTES and file length)
        hugeFlac.write(byteArrayOf(6, 0x98.toByte(), 0x96.toByte(), 0x80.toByte()))
        val safeMeta = BinaryTagReader.readFromBytes(hugeFlac.toByteArray(), "flac")
        assertNull(safeMeta.artworkBytes)

        // 3. ID3v2.3 claiming >8MB APIC & >512KB TIT2 + zero-size frame
        val badId3Body = ByteArrayOutputStream()
        // Oversized TIT2 (600_000 bytes) followed by valid TPE1
        badId3Body.write("TIT2".toByteArray())
        writeInt32BE(badId3Body, 600_000)
        badId3Body.write(byteArrayOf(0, 0))
        badId3Body.write(ByteArray(600_000)) // present in stream, must be skipped without text alloc!
        // Oversized APIC (9MB declared)
        badId3Body.write("APIC".toByteArray())
        writeInt32BE(badId3Body, 9_000_000)
        badId3Body.write(byteArrayOf(0, 0))
        // Valid TPE1 before truncation, wait: APIC truncated so cursor stops cleanly; test valid TPE1 BEFORE bad APIC
        val mixedId3 = ByteArrayOutputStream()
        writeId3Frame(mixedId3, "TPE1", byteArrayOf(3) + "Safe Artist".toByteArray(), syncsafe = false)
        mixedId3.write(badId3Body.toByteArray())
        val resId3 = BinaryTagReader.readFromBytes(wrapId3Tag(3, mixedId3.toByteArray()), "mp3")
        assertEquals("Safe Artist", resId3.artist)
        assertNull(resId3.title)
        assertNull(resId3.artworkBytes)

        // 4. ID3 zero-size frame loop trap
        val zeroId3 = ByteArrayOutputStream()
        zeroId3.write("TIT2".toByteArray())
        writeInt32BE(zeroId3, 0)
        zeroId3.write(byteArrayOf(0, 0))
        BinaryTagReader.readFromBytes(wrapId3Tag(3, zeroId3.toByteArray()), "mp3")

        // 5. MP4 invalid box sizes (size=2, size=0xFFFFFFFF)
        val badMp4 = mp4Box("ftyp".toByteArray(), ByteArray(4)) +
            byteArrayOf(0, 0, 0, 2, 'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte())
        BinaryTagReader.readFromBytes(badMp4, "m4a")

        // 6. Oversized sidecar files (> MAX_TEXT_BYTES / > MAX_ARTWORK_BYTES) are ignored cleanly
        val dir = tempFolder.newFolder("oversized_sidecar")
        val dummyMp3 = File(dir, "track.mp3")
        dummyMp3.writeBytes(ByteArray(16))
        File(dir, "track.lrc").writeBytes(ByteArray(BinaryTagReader.MAX_TEXT_BYTES + 16) { 'a'.code.toByte() })
        val sidecarRes = BinaryTagReader.read(dummyMp3)
        assertNull(sidecarRes.lyrics)
    }

    // --- LrcParser ---

    @Test
    fun parsesStandardMultiTimestampOffsetAndKaraokeLrc() {
        val raw = """
            [ar:Sora]
            [ti:Glass]
            [offset:+500]
            [00:00.30]Early clamped
            [00:15.00][01:20.00]Chorus line
            [00:10.500]<00:10.50>Light <00:11.000>bends <00:11.60>here
        """.trimIndent()

        val parsed = LrcParser.parse(raw)
        assertTrue(parsed.isSynced)
        assertEquals(500L, parsed.offsetMs)
        assertEquals(4, parsed.lines.size)

        // Sorted order: 0ms, 10000ms, 14500ms, 79500ms
        assertEquals(0L, parsed.lines[0].timestampMs)
        assertEquals("Early clamped", parsed.lines[0].text)

        val karaokeLine = parsed.lines[1]
        assertEquals(10_000L, karaokeLine.timestampMs)
        assertEquals("Light bends here", karaokeLine.text)
        assertEquals(3, karaokeLine.words.size)
        assertEquals(10_000L, karaokeLine.words[0].startMs)
        assertEquals("Light ", karaokeLine.words[0].text)
        assertEquals(10_500L, karaokeLine.words[1].startMs)
        assertEquals("bends ", karaokeLine.words[1].text)
        assertEquals(11_100L, karaokeLine.words[2].startMs)
        assertEquals("here", karaokeLine.words[2].text)

        assertEquals(14_500L, parsed.lines[2].timestampMs)
        assertEquals("Chorus line", parsed.lines[2].text)
        assertEquals(79_500L, parsed.lines[3].timestampMs)
        assertEquals("Chorus line", parsed.lines[3].text)
    }

    @Test
    fun parsesNegativeOffsetAndStaticFallback() {
        val neg = LrcParser.parse("[offset:-250]\n[00:01.00]Delayed")
        assertTrue(neg.isSynced)
        assertEquals(-250L, neg.offsetMs)
        assertEquals(1250L, neg.lines[0].timestampMs)

        val staticLyrics = LrcParser.parse("[ti:Plain]\nFirst static line\n\nSecond static line")
        assertFalse(staticLyrics.isSynced)
        assertEquals(2, staticLyrics.lines.size)
        assertEquals("First static line", staticLyrics.lines[0].text)
        assertEquals("Second static line", staticLyrics.lines[1].text)

        val empty = LrcParser.parse("   \n ")
        assertFalse(empty.isSynced)
        assertTrue(empty.lines.isEmpty())
    }

    @Test
    fun binarySearchFindsActiveLineInLogN() {
        assertEquals(-1, LrcParser.findActiveLineIndex(emptyList(), 5000L))

        val lines = listOf(
            LyricLine(1000L, "A"),
            LyricLine(2000L, "B"),
            LyricLine(2000L, "B2"),
            LyricLine(5000L, "C"),
            LyricLine(9000L, "D")
        )
        assertEquals(-1, LrcParser.findActiveLineIndex(lines, 0L))
        assertEquals(-1, LrcParser.findActiveLineIndex(lines, 999L))
        assertEquals(0, LrcParser.findActiveLineIndex(lines, 1000L))
        assertEquals(0, LrcParser.findActiveLineIndex(lines, 1999L))
        assertEquals(2, LrcParser.findActiveLineIndex(lines, 2000L))
        assertEquals(3, LrcParser.findActiveLineIndex(lines, 7500L))
        assertEquals(4, LrcParser.findActiveLineIndex(lines, 99999L))

        // Large list check
        val large = List(10_000) { idx -> LyricLine((idx + 1) * 100L, "Line $idx") }
        assertNotNull(large)
        assertEquals(-1, LrcParser.findActiveLineIndex(large, 50L))
        assertEquals(0, LrcParser.findActiveLineIndex(large, 100L))
        assertEquals(4999, LrcParser.findActiveLineIndex(large, 500_050L))
        assertEquals(9999, LrcParser.findActiveLineIndex(large, 2_000_000L))
    }

    // --- TTML & Streaming FLAC/MP3 Lyrics Pipeline ---

    @Test
    fun parsesTtmlWordTimingsParagraphsAndEntities() {
        val ttml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:04.500" end="00:08.200">
                    <span begin="00:04.500" end="00:05.100">Light</span>
                    <span begin="00:05.150" end="00:06.000">bends &amp; flows</span>
                    <span ttm:role="x-translation">Cahaya membias</span>
                  </p>
                  <p begin="10.25s" end="14500ms">Second line</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = LrcParser.parseTtml(ttml)
        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)

        val line1 = parsed.lines[0]
        assertEquals(4_500L, line1.timestampMs)
        assertEquals(8_200L, line1.endMs)
        assertEquals("Light bends & flows", line1.text)
        assertEquals("Cahaya membias", line1.translatedText)
        assertEquals(2, line1.words.size)
        assertEquals(4_500L, line1.words[0].startMs)
        assertEquals(5_100L, line1.words[0].endMs)
        assertEquals("Light ", line1.words[0].text)
        assertEquals(5_150L, line1.words[1].startMs)
        assertEquals(6_000L, line1.words[1].endMs)
        assertEquals("bends & flows", line1.words[1].text)

        val line2 = parsed.lines[1]
        assertEquals(10_250L, line2.timestampMs)
        assertEquals(14_500L, line2.endMs)
        assertEquals("Second line", line2.text)
    }

    @Test
    fun streamsFlacAndMp3LyricsWithoutReadingPictureOrApicPayloads() {
        val flacOut = ByteArrayOutputStream()
        flacOut.write("fLaC".toByteArray(Charsets.US_ASCII))
        writeFlacMetaBlock(
            flacOut,
            isLast = false,
            type = 4,
            payload = buildVorbisCommentBlock("Laguku", listOf("LYRICS=[00:02.00]Streamed FLAC"))
        )
        writeFlacMetaBlock(
            flacOut,
            isLast = true,
            type = 6,
            payload = buildFlacPictureBlock(3, "image/jpeg", ByteArray(2048))
        )
        assertEquals(
            "[00:02.00]Streamed FLAC",
            BinaryTagReader.readEmbeddedLyricsFromBytes(flacOut.toByteArray(), "flac")
        )

        val mp3Frames = ByteArrayOutputStream()
        writeId3Frame(
            mp3Frames,
            "USLT",
            byteArrayOf(0) + "eng".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
                "ISO-8859-1 Lyric".toByteArray(Charsets.ISO_8859_1),
            syncsafe = false
        )
        writeId3Frame(
            mp3Frames,
            "APIC",
            byteArrayOf(0) + "image/png".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + ByteArray(512),
            syncsafe = false
        )
        val mp3Bytes = wrapId3Tag(versionMajor = 3, body = mp3Frames.toByteArray())
        assertEquals(
            "ISO-8859-1 Lyric",
            BinaryTagReader.readEmbeddedLyricsFromBytes(mp3Bytes, "mp3")
        )
    }

    @Test
    fun loadTrackLyricsFollowsStrictTtmlThenLrcThenEmbeddedPriority() {
        val dir = tempFolder.newFolder("track_lyrics_priority")
        val flacFile = File(dir, "song.flac").apply {
            val out = ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            writeFlacMetaBlock(
                out,
                isLast = true,
                type = 4,
                payload = buildVorbisCommentBlock("Laguku", listOf("LYRICS=[00:01.00]From Embedded"))
            )
            writeBytes(out.toByteArray())
        }
        val ttmlFile = File(dir, "song.ttml").apply {
            writeText("<tt><body><div><p begin=\"00:02.00\" end=\"00:05.00\">From TTML</p></div></body></tt>")
        }
        val lrcFile = File(dir, "song.lrc").apply {
            writeText("[00:03.00]From LRC")
        }

        // 1. .ttml wins over .lrc & embedded
        val resTtml = LrcParser.loadTrackLyrics(flacFile)
        assertEquals(LyricSource.SIDECAR_TTML, resTtml.source)
        assertEquals("From TTML", resTtml.lines.first().primaryText)

        // 2. .lrc wins over embedded when .ttml absent
        ttmlFile.delete()
        val resLrc = LrcParser.loadTrackLyrics(flacFile)
        assertEquals(LyricSource.SIDECAR_LRC, resLrc.source)
        assertEquals("From LRC", resLrc.lines.first().primaryText)

        // 3. Embedded FLAC wins when sidecars absent
        lrcFile.delete()
        val resEmbedded = LrcParser.loadTrackLyrics(flacFile)
        assertEquals(LyricSource.EMBEDDED_TAG, resEmbedded.source)
        assertEquals("From Embedded", resEmbedded.lines.first().primaryText)
    }

    // --- Synthetic Binary Builders ---

    private fun buildVorbisCommentBlock(vendor: String, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeInt32LE(out, vendorBytes.size)
        out.write(vendorBytes)
        writeInt32LE(out, comments.size)
        for (c in comments) {
            val cb = c.toByteArray(Charsets.UTF_8)
            writeInt32LE(out, cb.size)
            out.write(cb)
        }
        return out.toByteArray()
    }

    private fun buildFlacPictureBlock(pictureType: Int, mime: String, image: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeInt32BE(out, pictureType)
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        writeInt32BE(out, mimeBytes.size)
        out.write(mimeBytes)
        writeInt32BE(out, 0) // description len
        writeInt32BE(out, 500) // width
        writeInt32BE(out, 500) // height
        writeInt32BE(out, 24) // depth
        writeInt32BE(out, 0) // colors
        writeInt32BE(out, image.size)
        out.write(image)
        return out.toByteArray()
    }

    private fun writeFlacMetaBlock(out: ByteArrayOutputStream, isLast: Boolean, type: Int, payload: ByteArray) {
        val first = (if (isLast) 0x80 else 0x00) or (type and 0x7F)
        out.write(first)
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
    }

    private fun segmentPacket(packet: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var rem = packet.size
        while (rem >= 255) {
            out.write(255)
            rem -= 255
        }
        out.write(rem)
        return out.toByteArray()
    }

    private fun writeOggPage(
        out: ByteArrayOutputStream,
        headerType: Int,
        pageSeq: Int,
        segments: ByteArray,
        payload: ByteArray
    ) {
        out.write("OggS".toByteArray(Charsets.US_ASCII))
        out.write(0) // version
        out.write(headerType)
        out.write(ByteArray(8)) // granule
        writeInt32LE(out, 1234) // serial
        writeInt32LE(out, pageSeq)
        writeInt32LE(out, 0) // CRC (not validated by parser)
        out.write(segments.size)
        out.write(segments)
        out.write(payload)
    }

    private fun writeId3Frame(out: ByteArrayOutputStream, id: String, payload: ByteArray, syncsafe: Boolean) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        if (syncsafe) writeSyncsafeInt(out, payload.size) else writeInt32BE(out, payload.size)
        out.write(byteArrayOf(0, 0))
        out.write(payload)
    }

    private fun wrapId3Tag(versionMajor: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(versionMajor)
        out.write(0) // revision
        out.write(0) // flags
        writeSyncsafeInt(out, body.size)
        out.write(body)
        return out.toByteArray()
    }

    private fun mp4Box(type: ByteArray, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        writeInt32BE(out, 8 + payload.size)
        out.write(type)
        out.write(payload)
        return out.toByteArray()
    }

    private fun mp4DataBox(dataType: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        writeInt32BE(out, dataType)
        writeInt32BE(out, 0) // locale
        out.write(payload)
        return mp4Box("data".toByteArray(Charsets.US_ASCII), out.toByteArray())
    }

    private fun mp4IlstTextItem(type: ByteArray, text: String): ByteArray {
        return mp4Box(type, mp4DataBox(dataType = 1, payload = text.toByteArray(Charsets.UTF_8)))
    }

    private fun writeSyncsafeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 21) and 0x7F)
        out.write((v ushr 14) and 0x7F)
        out.write((v ushr 7) and 0x7F)
        out.write(v and 0x7F)
    }

    private fun writeInt32BE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt32LE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
