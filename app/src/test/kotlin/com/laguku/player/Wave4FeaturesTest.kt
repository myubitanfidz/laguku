package com.laguku.player

import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.lyrics.LrcParser
import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.model.LyricSource
import com.laguku.player.ui.motion.SpringAnimatorHelper
import com.laguku.player.ui.player.LyricsRecyclerAdapter
import com.laguku.player.ui.player.NowPlayingView
import com.laguku.player.ui.player.WaveformPillSeekBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class Wave4FeaturesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun binaryTagReader_extractsVorbisId3UsltUtf16AndMp4LyricsSafely() {
        // 1. FLAC case-insensitive Vorbis lyrics
        val vorbisPayload = buildVorbisCommentBlock(
            "Laguku",
            listOf("lyrics=[00:01.00]Vorbis line")
        )
        val flacOut = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            writeFlacMetaBlock(this, isLast = true, type = 4, payload = vorbisPayload)
        }
        assertEquals("[00:01.00]Vorbis line", BinaryTagReader.readFromBytes(flacOut.toByteArray(), "flac").lyrics)

        // 2. ID3v2.3 USLT with UTF-16LE BOM
        val usltUtf16 = byteArrayOf(1) +
            "jpn".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "[00:02.00]夜明け".toByteArray(Charsets.UTF_16LE)
        val id3Frames = ByteArrayOutputStream().apply {
            writeId3Frame(this, "USLT", usltUtf16, syncsafe = false)
        }
        val mp3Bytes = wrapId3Tag(3, id3Frames.toByteArray())
        assertEquals("[00:02.00]夜明け", BinaryTagReader.readFromBytes(mp3Bytes, "mp3").lyrics)

        // 3. ID3v2.4 USLT with UTF-8
        val usltUtf8 = byteArrayOf(3) +
            "ind".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0) +
            "[00:03.00]Pagi cerah".toByteArray(Charsets.UTF_8)
        val id3v4Frames = ByteArrayOutputStream().apply {
            writeId3Frame(this, "USLT", usltUtf8, syncsafe = true)
        }
        assertEquals("[00:03.00]Pagi cerah", BinaryTagReader.readFromBytes(wrapId3Tag(4, id3v4Frames.toByteArray()), "mp3").lyrics)

        // 4. MP4 ©lyr atom
        val lyrType = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val lyrItem = mp4Box(lyrType, mp4DataBox(1, "[00:04.00]MP4 Lyric".toByteArray(Charsets.UTF_8)))
        val moov = mp4Box("moov".toByteArray(), mp4Box("udta".toByteArray(), mp4Box("meta".toByteArray(), ByteArray(4) + mp4Box("ilst".toByteArray(), lyrItem))))
        val m4a = mp4Box("ftyp".toByteArray(), "M4A ".toByteArray() + ByteArray(8)) + moov
        assertEquals("[00:04.00]MP4 Lyric", BinaryTagReader.readFromBytes(m4a, "m4a").lyrics)

        // 5. >512 KB lyrics frame must be ignored without OOM
        val hugeUslt = ByteArrayOutputStream().apply {
            write("USLT".toByteArray())
            writeInt32BE(this, BinaryTagReader.MAX_TEXT_BYTES + 128)
            write(byteArrayOf(0, 0))
            write(ByteArray(BinaryTagReader.MAX_TEXT_BYTES + 128))
        }
        assertNull(BinaryTagReader.readFromBytes(wrapId3Tag(3, hugeUslt.toByteArray()), "mp3").lyrics)
    }

    @Test
    fun lrcParser_enforcesStrict4TierLyricSourcePriorityIncludingWavSidecar() {
        val musicDir = tempFolder.newFolder("music")
        val internalDir = tempFolder.newFolder("internal_lrc")

        val flacFile = File(musicDir, "track.flac").apply {
            val out = ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            writeFlacMetaBlock(
                out,
                isLast = true,
                type = 4,
                payload = buildVorbisCommentBlock("Test", listOf("LYRICS=[00:01.00]Embedded Synced"))
            )
            writeBytes(out.toByteArray())
        }
        val sidecarFlac = File(musicDir, "track.lrc").apply {
            writeText("[00:02.00]Sidecar Synced", Charsets.UTF_8)
        }
        val isolatedRoot = tempFolder.newFolder("isolated_root")
        LrcParser.saveLrcFileSafely(flacFile, "[00:03.00]Internal Synced", isolatedRoot, internalDir)

        // Tier 1: Embedded synced wins over sidecar & internal
        val res1 = LrcParser.loadLyrics(flacFile, internalDir)
        assertEquals(LyricSource.EMBEDDED_TAG, res1.source)
        assertEquals("Embedded Synced", res1.lines.first().primaryText)

        // Tier 2: Plain embedded text yields to sidecar .lrc (and WAV uses sidecar .lrc)
        val wavFile = File(musicDir, "wave_track.wav").apply { writeBytes("RIFF\u0004\u0000\u0000\u0000WAVE".toByteArray()) }
        File(musicDir, "wave_track.lrc").writeText("[00:04.00]WAV Sidecar", Charsets.UTF_8)
        val wavRes = LrcParser.loadLyrics(wavFile, internalDir)
        assertEquals(LyricSource.SIDECAR_LRC, wavRes.source)
        assertEquals("WAV Sidecar", wavRes.lines.first().primaryText)

        // Tier 3: Internal fallback wins when no sidecar & embedded is plain text
        sidecarFlac.delete()
        val res3 = LrcParser.loadLyrics(flacFile, internalDir, embeddedLyrics = "Plain unsynced embedded")
        assertEquals(LyricSource.INTERNAL_FALLBACK, res3.source)
        assertEquals("Internal Synced", res3.lines.first().primaryText)

        // Tier 4: Plain text embedded used when no sidecar and no internal fallback
        val otherAudio = File(musicDir, "plain_only.mp3").apply { writeBytes(ByteArray(8)) }
        val res4 = LrcParser.loadLyrics(otherAudio, internalDir, embeddedLyrics = "Plain verse 1\nPlain verse 2")
        assertEquals(LyricSource.EMBEDDED_TAG, res4.source)
        assertFalse(res4.isSynced)
        assertEquals(2, res4.lines.size)
        assertEquals("Plain verse 1", res4.lines[0].primaryText)
    }

    @Test
    fun lrcParser_parses1To3LayerLyricsAtSameTimestampAndWindowMathFramesTallBlocks() {
        val raw = """
            [00:05.00]Single layer line
            [00:10.00]Original two-layer
            [00:10.00]Terjemahan dua lapis
            [00:15.00]夜に駆ける
            [00:15.00]Yoru ni kakeru
            [00:15.00]Berlari menembus malam
        """.trimIndent()

        val parsed = LrcParser.parse(raw)
        assertTrue(parsed.isSynced)
        assertEquals(3, parsed.lines.size)

        assertEquals("Single layer line", parsed.lines[0].primaryText)
        assertNull(parsed.lines[0].romanizedText)
        assertNull(parsed.lines[0].translatedText)

        assertEquals("Original two-layer", parsed.lines[1].primaryText)
        assertNull(parsed.lines[1].romanizedText)
        assertEquals("Terjemahan dua lapis", parsed.lines[1].translatedText)

        assertEquals("夜に駆ける", parsed.lines[2].primaryText)
        assertEquals("Yoru ni kakeru", parsed.lines[2].romanizedText)
        assertEquals("Berlari menembus malam", parsed.lines[2].translatedText)

        // Tall 3-layer block stays in tier 0 and frames safely inside viewport
        val viewportH = 680
        val threeLayerHeight = 320
        val topOffset = LyricsRecyclerAdapter.LyricWindowMath.computeScrollTopOffset(
            viewportH, threeLayerHeight, PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL, 40, 80
        )
        assertTrue(topOffset >= 40)
        assertTrue(topOffset + threeLayerHeight <= viewportH - 80)
        assertEquals(0, LyricsRecyclerAdapter.LyricWindowMath.computeBlurTier(2, 2, synced = true, selectionMode = false))
    }

    @Test
    fun playDragMath_appliesRubberBandDampingAnd44DpThreshold() {
        val thresholdPx = 132f // 44dp * 3
        val maxOffsetPx = 174f // 58dp * 3

        assertEquals(0, NowPlayingView.PlayDragMath.resolveDragDirection(0f, thresholdPx))
        assertEquals(0, NowPlayingView.PlayDragMath.resolveDragDirection(thresholdPx - 1f, thresholdPx))
        assertEquals(1, NowPlayingView.PlayDragMath.resolveDragDirection(thresholdPx, thresholdPx))
        assertEquals(-1, NowPlayingView.PlayDragMath.resolveDragDirection(-thresholdPx, thresholdPx))

        val dampedRight = NowPlayingView.PlayDragMath.computeRubberBandOffset(800f, thresholdPx, maxOffsetPx)
        assertTrue(dampedRight > 0f && dampedRight < maxOffsetPx)

        val dampedLeft = NowPlayingView.PlayDragMath.computeRubberBandOffset(-800f, thresholdPx, maxOffsetPx)
        assertTrue(dampedLeft < 0f && dampedLeft > -maxOffsetPx)
    }

    @Test
    fun waveformMath_normalizesAmplitudesAndGeneratesDeterministicFallback() {
        val normalized = WaveformPillSeekBarView.WaveformMath.normalizeAmplitudes(
            floatArrayOf(0f, 50f, 100f),
            minHeight = 0.16f
        )
        assertEquals(0.16f, normalized[0], 0.001f)
        assertEquals(0.58f, normalized[1], 0.001f)
        assertEquals(1.0f, normalized[2], 0.001f)

        val p1 = WaveformPillSeekBarView.WaveformMath.generateDeterministicProfile("track://1", 48)
        val p2 = WaveformPillSeekBarView.WaveformMath.generateDeterministicProfile("track://1", 48)
        assertEquals(48, p1.size)
        for (i in p1.indices) {
            assertEquals(p1[i], p2[i], 0.0001f)
            assertTrue(p1[i] in 0.16f..1.0f)
        }
    }

    @Test
    fun amoledTransitionMath_interpolatesOverlayAndRimContrastWithSpring380() {
        assertEquals(380f, SpringAnimatorHelper.AmoledTransitionMath.AMOLED_STIFFNESS, 0.0001f)
        assertEquals(0.85f, SpringAnimatorHelper.AmoledTransitionMath.AMOLED_DAMPING_RATIO, 0.0001f)
        assertEquals(0f, SpringAnimatorHelper.AmoledTransitionMath.computeOverlayAlpha(-0.2f), 0.0001f)
        assertEquals(0.5f, SpringAnimatorHelper.AmoledTransitionMath.computeOverlayAlpha(0.5f), 0.0001f)
        assertEquals(1f, SpringAnimatorHelper.AmoledTransitionMath.computeOverlayAlpha(1.4f), 0.0001f)
        assertEquals(1f, SpringAnimatorHelper.AmoledTransitionMath.computeRimContrastFactor(0f), 0.0001f)
        assertEquals(1.4f, SpringAnimatorHelper.AmoledTransitionMath.computeRimContrastFactor(1f), 0.0001f)
    }

    @Test
    fun lyricMotionMath_interpolatesFocusScaleAlphaBlurPoolAndSpringScroll() {
        assertEquals(0.95f, LyricsRecyclerAdapter.LyricMotionMath.computeFocusScale(0f), 0.0001f)
        assertEquals(1.00f, LyricsRecyclerAdapter.LyricMotionMath.computeFocusScale(1f), 0.0001f)
        assertEquals(0.40f, LyricsRecyclerAdapter.LyricMotionMath.computeFocusAlpha(0f), 0.0001f)
        assertEquals(1.00f, LyricsRecyclerAdapter.LyricMotionMath.computeFocusAlpha(1f), 0.0001f)

        assertEquals(0, LyricsRecyclerAdapter.LyricMotionMath.selectBlurPoolIndex(0f, 0, synced = true, selectionMode = false))
        assertEquals(2, LyricsRecyclerAdapter.LyricMotionMath.selectBlurPoolIndex(0f, 1, synced = true, selectionMode = false))
        assertEquals(4, LyricsRecyclerAdapter.LyricMotionMath.selectBlurPoolIndex(0f, 2, synced = true, selectionMode = false))
        assertEquals(0, LyricsRecyclerAdapter.LyricMotionMath.selectBlurPoolIndex(1f, 2, synced = true, selectionMode = false))

        val solver = SpringAnimatorHelper.SpringSolver(
            value = 0f,
            target = 120f,
            stiffness = 220f,
            dampingRatio = 0.92f,
            valueThreshold = 0.5f
        )
        var applied = 0f
        var settled = false
        var ticks = 0
        while (!settled && ticks < 300) {
            val (delta, done) = LyricsRecyclerAdapter.LyricMotionMath.computeScrollStep(solver, applied, 1f / 60f)
            applied += delta
            settled = done
            ticks++
        }
        assertTrue(settled)
        assertEquals(120f, applied, 0.5f)
    }

    // --- Synthetic Binary Helpers ---

    private fun buildVorbisCommentBlock(vendor: String, comments: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vb = vendor.toByteArray(Charsets.UTF_8)
        writeInt32LE(out, vb.size)
        out.write(vb)
        writeInt32LE(out, comments.size)
        for (c in comments) {
            val cb = c.toByteArray(Charsets.UTF_8)
            writeInt32LE(out, cb.size)
            out.write(cb)
        }
        return out.toByteArray()
    }

    private fun writeFlacMetaBlock(out: ByteArrayOutputStream, isLast: Boolean, type: Int, payload: ByteArray) {
        out.write((if (isLast) 0x80 else 0x00) or (type and 0x7F))
        out.write((payload.size ushr 16) and 0xFF)
        out.write((payload.size ushr 8) and 0xFF)
        out.write(payload.size and 0xFF)
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
        out.write(0)
        out.write(0)
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
        writeInt32BE(out, 0)
        out.write(payload)
        return mp4Box("data".toByteArray(Charsets.US_ASCII), out.toByteArray())
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
