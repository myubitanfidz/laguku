package com.laguku.player

import com.laguku.player.lyrics.LrcParser
import com.laguku.player.lyrics.LyricQuerySanitizer
import com.laguku.player.lyrics.OnlineLyricFetcher
import com.laguku.player.ui.player.TapToSyncSheetView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OnlineLyricsAndSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun lyricQuerySanitizer_cleansDownloaderNoiseAndSplitsArtistTitle() {
        val q1 = LyricQuerySanitizer.sanitize(
            rawTitle = "01. Nadin Amizah - Bertaut (Official Music Video) [320kbps] (Lyrics).mp3",
            rawArtist = "<unknown>",
            rawAlbum = "Selamat Ulang Tahun (Deluxe)",
            durationMs = 315_400L
        )
        assertEquals("Nadin Amizah", q1.artist)
        assertEquals("Bertaut", q1.title)
        assertEquals(315, q1.durationSec)

        val q2 = LyricQuerySanitizer.sanitize(
            rawTitle = "Hati-Hati di Jalan (Official Audio) [128kbps] feat. Guest",
            rawArtist = "Tulus - Topic",
            durationMs = 242_600L
        )
        assertEquals("Tulus", q2.artist)
        assertEquals("Hati-Hati di Jalan", q2.title)
        assertEquals(243, q2.durationSec)
    }

    @Test
    fun onlineLyricFetcher_parsesLrclibJsonAndEnforcesDurationTolerance() {
        val getJsonValid = """
            {
              "id": 12,
              "duration": 212.0,
              "instrumental": false,
              "plainLyrics": "Line one\nLine two",
              "syncedLyrics": "[00:05.00] Line one\n[00:10.00] Line two"
            }
        """.trimIndent()
        val foundGet = OnlineLyricFetcher.parseGetResponse(getJsonValid, expectedDurationSec = 210)
        assertTrue(foundGet is OnlineLyricFetcher.FetchResult.Found)
        foundGet as OnlineLyricFetcher.FetchResult.Found
        assertTrue(foundGet.isSynced)
        assertTrue(foundGet.lrcContent.contains("[00:05.00] Line one"))

        // Duration diff > 3s on /api/get must be rejected
        val rejectedGet = OnlineLyricFetcher.parseGetResponse(getJsonValid, expectedDurationSec = 220)
        assertTrue(rejectedGet is OnlineLyricFetcher.FetchResult.NotFound)

        // /api/search chooses syncedLyrics within +/-4s over closer plainLyrics
        val searchJson = """
            [
              {
                "duration": 200.0,
                "instrumental": false,
                "plainLyrics": "Only plain close duration",
                "syncedLyrics": null
              },
              {
                "duration": 203.0,
                "instrumental": false,
                "plainLyrics": "Plain synced fallback",
                "syncedLyrics": "[00:02.50] Matched synced"
              },
              {
                "duration": 220.0,
                "instrumental": false,
                "syncedLyrics": "[00:01.00] Too far duration"
              }
            ]
        """.trimIndent()
        val foundSearch = OnlineLyricFetcher.parseSearchResponse(searchJson, expectedDurationSec = 200)
        assertTrue(foundSearch is OnlineLyricFetcher.FetchResult.Found)
        foundSearch as OnlineLyricFetcher.FetchResult.Found
        assertTrue(foundSearch.isSynced)
        assertEquals("[00:02.50] Matched synced", foundSearch.lrcContent)
    }

    @Test
    fun tapToSyncSession_stampsTimestampsUndoesAndBuildsValidLrc() {
        assertEquals("[01:05.42]", TapToSyncSheetView.TapToSyncSession.formatLrcTimestamp(65_429L))

        val session = TapToSyncSheetView.TapToSyncSession()
        session.loadRawLyrics(
            """
            Baris pertama lagu

            [00:10.00] Baris kedua lagu
            Baris ketiga lagu
            """.trimIndent()
        )
        assertEquals(3, session.getTotalCount())
        assertEquals("Baris pertama lagu", session.getCurrentLinePreview())
        assertEquals("Baris kedua lagu", session.getNextLinePreview())

        assertEquals("[00:04.20] Baris pertama lagu", session.stampNextLine(4_200L))
        assertEquals("[00:09.50] Baris kedua lagu", session.stampNextLine(9_500L))
        assertEquals(2, session.getStampedCount())

        assertTrue(session.undoLastStamp())
        assertEquals(1, session.getStampedCount())
        assertEquals("Baris kedua lagu", session.getCurrentLinePreview())

        session.stampNextLine(9_850L)
        session.stampNextLine(15_000L)
        assertTrue(session.isComplete())

        val lrc = session.buildLrcContent()
        val parsed = LrcParser.parse(lrc)
        assertTrue(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals(4_200L, parsed.lines[0].timestampMs)
        assertEquals(9_850L, parsed.lines[1].timestampMs)
        assertEquals("Baris ketiga lagu", parsed.lines[2].text)
    }

    @Test
    fun lrcParser_saveLrcFileSafelyWritesSidecarAndProtectsAgainstPathTraversal() {
        val musicRoot = tempFolder.newFolder("music")
        val internalFallback = tempFolder.newFolder("internal_lrc")

        val insideAudio = File(musicRoot, "song.flac").apply { writeText("audio") }
        val savedSidecar = LrcParser.saveLrcFileSafely(
            audioFile = insideAudio,
            lrcContent = "[00:01.00] Halo",
            allowedRootDir = musicRoot,
            internalFallbackDir = internalFallback
        )
        assertNotNull(savedSidecar)
        assertEquals(File(musicRoot, "song.lrc").canonicalPath, savedSidecar!!.canonicalPath)
        assertEquals("[00:01.00] Halo", savedSidecar.readText(Charsets.UTF_8))

        // Audio outside allowedRootDir must NOT write sidecar outside root; falls back safely inside internalFallback
        val outsideDir = tempFolder.newFolder("external_sd")
        val outsideAudio = File(outsideDir, "sd_track.mp3").apply { writeText("audio") }
        val savedFallback = LrcParser.saveLrcFileSafely(
            audioFile = outsideAudio,
            lrcContent = "[00:02.00] Fallback",
            allowedRootDir = musicRoot,
            internalFallbackDir = internalFallback
        )
        assertNotNull(savedFallback)
        assertFalse(File(outsideDir, "sd_track.lrc").exists())
        assertTrue(savedFallback!!.canonicalPath.startsWith(internalFallback.canonicalPath))

        val loadedFallback = LrcParser.loadFallbackLrc(outsideAudio, internalFallback)
        assertEquals("[00:02.00] Fallback", loadedFallback)
        assertNull(LrcParser.loadFallbackLrc(File(outsideDir, "missing.mp3"), internalFallback))
    }
}
