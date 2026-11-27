package com.laguku.player

import com.laguku.player.data.LibraryScanner
import com.laguku.player.lyrics.LrcParser
import com.laguku.player.model.AudioQualityInfo
import com.laguku.player.model.LyricSource
import com.laguku.player.model.Track
import com.laguku.player.service.TrackInstalledReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class InstallerHandoffAndLyricsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesTrackInstalledIntentAndNotifiesUiObserver() {
        assertNull(TrackInstalledReceiver.parseIntent(null))
        assertNull(
            TrackInstalledReceiver.parsePayload(
                action = "com.laguku.player.OTHER_ACTION",
                dataUri = "content://media/external/audio/media/99",
                extraAudioUri = "content://media/external/audio/media/99",
                extraTtmlUri = null,
                extraLrcUri = null,
                extraTitle = "Ignored",
                extraArtist = "Artist"
            )
        )

        val parsed = TrackInstalledReceiver.parsePayload(
            action = TrackInstalledReceiver.ACTION_TRACK_INSTALLED,
            dataUri = "content://media/external/audio/media/42",
            extraAudioUri = "content://media/external/audio/media/42",
            extraTtmlUri = "content://media/external/file/43",
            extraLrcUri = "content://media/external/file/44",
            extraTitle = "Laskar Pelangi",
            extraArtist = "Nidji"
        )
        assertNotNull(parsed)
        assertEquals("content://media/external/audio/media/42", parsed!!.audioUri)
        assertEquals("content://media/external/file/43", parsed.ttmlUri)
        assertEquals("content://media/external/file/44", parsed.lrcUri)
        assertEquals("Laskar Pelangi", parsed.title)
        assertEquals("Nidji", parsed.artist)

        // Verify fallback to EXTRA_AUDIO_URI when Intent.data is null
        val parsedExtraOnly = TrackInstalledReceiver.parsePayload(
            action = TrackInstalledReceiver.ACTION_TRACK_INSTALLED,
            dataUri = null,
            extraAudioUri = "content://media/external/audio/media/77",
            extraTtmlUri = null,
            extraLrcUri = null,
            extraTitle = null,
            extraArtist = null
        )
        assertEquals("content://media/external/audio/media/77", parsedExtraOnly?.audioUri)

        var observedTitle: String? = null
        val listener: (com.laguku.player.service.TrackInstalledEvent) -> Unit = { ev ->
            observedTitle = ev.title
        }
        TrackInstalledReceiver.addListener(listener)
        try {
            TrackInstalledReceiver.emitEvent(parsed)
            assertEquals("Laskar Pelangi", observedTitle)
            assertEquals("content://media/external/audio/media/42", TrackInstalledReceiver.latestEvent?.audioUri)
        } finally {
            TrackInstalledReceiver.removeListener(listener)
        }
    }

    @Test
    fun resolvesThreeLayerLyricsWithStrictTtmlThenLrcThenEmbeddedTagPriority() {
        val dir = tempFolder.newFolder("Music_Laguku")
        val embeddedThreeLayer = "[00:12.00] 夢ならば\n[00:12.00] Yume naraba\n[00:12.00] Jika ini mimpi"
        val flacFile = File(dir, "Kenshi Yonezu - Lemon.flac").apply {
            writeBytes(buildMinimalFlacWithLyrics(embeddedThreeLayer))
        }
        val lrcFile = File(dir, "Kenshi Yonezu - Lemon.lrc").apply {
            writeText(
                "[00:12.00] LRC 夢ならば\n" +
                    "[00:12.00] LRC Yume naraba\n" +
                    "[00:12.00] LRC Jika ini mimpi"
            )
        }
        val ttmlFile = File(dir, "Kenshi Yonezu - Lemon.ttml").apply {
            writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                  <body>
                    <div>
                      <p begin="00:12.000" end="00:16.000">
                        <span begin="00:12.000" end="00:14.000">TTML</span>
                        <span begin="00:14.000" end="00:16.000">夢ならば</span>
                        <span ttm:role="x-roman">TTML Yume naraba</span>
                        <span ttm:role="x-translation" xml:lang="id">TTML Jika ini mimpi</span>
                      </p>
                    </div>
                  </body>
                </tt>
                """.trimIndent()
            )
        }

        // Priority 1: .ttml 3-layer sidecar wins
        val ttmlResult = LrcParser.loadTrackLyrics(flacFile)
        assertEquals(LyricSource.SIDECAR_TTML, ttmlResult.source)
        assertEquals(1, ttmlResult.lines.size)
        assertEquals("TTML 夢ならば", ttmlResult.lines[0].primaryText)
        assertEquals("TTML Yume naraba", ttmlResult.lines[0].romanizedText)
        assertEquals("TTML Jika ini mimpi", ttmlResult.lines[0].translatedText)

        // Priority 2: .lrc 3-layer sidecar wins once .ttml is removed
        ttmlFile.delete()
        val lrcResult = LrcParser.loadTrackLyrics(flacFile)
        assertEquals(LyricSource.SIDECAR_LRC, lrcResult.source)
        assertEquals(1, lrcResult.lines.size)
        assertEquals("LRC 夢ならば", lrcResult.lines[0].primaryText)
        assertEquals("LRC Yume naraba", lrcResult.lines[0].romanizedText)
        assertEquals("LRC Jika ini mimpi", lrcResult.lines[0].translatedText)

        // Priority 3: embedded 3-layer binary tag wins once both sidecars are absent
        lrcFile.delete()
        val embeddedResult = LrcParser.loadTrackLyrics(flacFile)
        assertEquals(LyricSource.EMBEDDED_TAG, embeddedResult.source)
        assertEquals(1, embeddedResult.lines.size)
        assertEquals("夢ならば", embeddedResult.lines[0].primaryText)
        assertEquals("Yume naraba", embeddedResult.lines[0].romanizedText)
        assertEquals("Jika ini mimpi", embeddedResult.lines[0].translatedText)
    }

    @Test
    fun mergeDirectoryTracksUpdatesLagukuFolderImmediatelyWhileKeepingOtherFolders() {
        val otherDir = tempFolder.newFolder("Downloads")
        val lagukuDir = tempFolder.newFolder("Laguku")
        val otherTrack = Track(
            id = 10L,
            title = "Alpha Song",
            artist = "Artist A",
            album = "Downloads",
            path = File(otherDir, "alpha.mp3").absolutePath,
            quality = AudioQualityInfo("MP3")
        )
        val existingLaguku = Track(
            id = 20L,
            title = "Old Name",
            artist = "Nidji",
            album = "OST",
            albumId = 99L,
            durationMs = 231_000L,
            path = File(lagukuDir, "Nidji - Laskar Pelangi.flac").absolutePath,
            contentUri = "content://media/external/audio/media/20",
            quality = AudioQualityInfo("FLAC")
        )
        val updatedExisting = existingLaguku.copy(
            id = 555L,
            title = "Laskar Pelangi",
            albumId = 0L,
            durationMs = 0L,
            contentUri = ""
        )
        val newlyInstalled = Track(
            id = 30L,
            title = "Hati-Hati di Jalan",
            artist = "Tulus",
            album = "Manusia",
            path = File(lagukuDir, "Tulus - Hati-Hati di Jalan.wav").absolutePath,
            contentUri = "content://media/external/audio/media/30",
            quality = AudioQualityInfo("WAV")
        )

        val merged = LibraryScanner.mergeDirectoryTracks(
            existing = listOf(otherTrack, existingLaguku),
            directoryTracks = listOf(updatedExisting, newlyInstalled),
            directory = lagukuDir
        )

        assertEquals(3, merged.size)
        assertTrue(merged.any { it.path == otherTrack.path })
        val updated = merged.first { it.path == existingLaguku.path }
        assertEquals("Laskar Pelangi", updated.title)
        assertEquals(20L, updated.id)
        assertEquals(231_000L, updated.durationMs)
        assertEquals("content://media/external/audio/media/20", updated.contentUri)
        assertTrue(merged.any { it.path == newlyInstalled.path })
    }

    private fun buildMinimalFlacWithLyrics(lyrics: String): ByteArray {
        val comment = "LYRICS=$lyrics".toByteArray(Charsets.UTF_8)
        val vendor = "LagukuTest".toByteArray(Charsets.UTF_8)
        val block = ByteArrayOutputStream().apply {
            writeInt32LE(vendor.size)
            write(vendor)
            writeInt32LE(1)
            writeInt32LE(comment.size)
            write(comment)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            write(0x84) // isLast = true, type = 4 (VORBIS_COMMENT)
            write((block.size ushr 16) and 0xFF)
            write((block.size ushr 8) and 0xFF)
            write(block.size and 0xFF)
            write(block)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt32LE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }
}
