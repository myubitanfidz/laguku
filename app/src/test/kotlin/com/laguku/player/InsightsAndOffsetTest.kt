package com.laguku.player

import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.lyrics.LrcParser
import com.laguku.player.model.ChapterMarker
import com.laguku.player.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InsightsAndOffsetTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resumeMemoryEnforces100EntryLruAndAutoResumesLongOrChapterTracks() {
        val store = PlaybackStateStore(File(tempFolder.root, "state.json"))
        for (i in 1..105) {
            val t = Track(i.toLong(), "Track $i", "Artist", "Album", durationMs = 240_000L, path = "/music/t$i.flac")
            store.saveResumePosition(t, 45_000L, nowMs = i.toLong() * 1000L)
        }
        assertEquals(PlaybackStateStore.MAX_RESUME_ENTRIES, store.getResumeCount())
        assertNull(store.getSavedPositionMs("/music/t1.flac"))
        assertEquals(45_000L, store.getSavedPositionMs("/music/t105.flac"))

        // Short track (< 3 min) without chapters does NOT auto-resume
        val shortTrack = Track(200L, "Short", "A", "B", durationMs = 120_000L, path = "/music/short.mp3")
        store.saveResumePosition(shortTrack, 30_000L)
        assertNull(store.shouldAutoResume(shortTrack))

        // Long track (> 3 min) auto-resumes
        val longTrack = Track(201L, "Long", "A", "B", durationMs = 240_000L, path = "/music/long.flac")
        store.saveResumePosition(longTrack, 90_000L)
        assertEquals(90_000L, store.shouldAutoResume(longTrack))

        // Short track WITH chapters auto-resumes
        val chapterTrack = shortTrack.copy(chapters = listOf(ChapterMarker(0L, "Ch1"), ChapterMarker(20_000L, "Ch2")))
        assertEquals(30_000L, store.shouldAutoResume(chapterTrack))
    }

    @Test
    fun bilingualLyricsMergeSameTimestampAndSupportCombinedOffsetSearchAndSafeSave() {
        val lrcRaw = """
            [00:05.00]Kimi no na wa
            [00:05.00]Siapa namamu
            [00:10.00]Yoru ni kakeru
        """.trimIndent()
        val parsed = LrcParser.parse(lrcRaw)
        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        assertEquals("Kimi no na wa", parsed.lines[0].text)
        assertEquals("Siapa namamu", parsed.lines[0].translation)
        assertNull(parsed.lines[1].translation)

        // At 5100ms with BT latency -180ms => effective 4920ms => before 5000ms (-1)
        assertEquals(-1, LrcParser.findActiveLineIndex(parsed.lines, 5100L, userOffsetMs = 0L, bluetoothLatencyOffsetMs = -180L))
        // With userOffset +100ms => effective 5020ms => index 0
        assertEquals(0, LrcParser.findActiveLineIndex(parsed.lines, 5100L, userOffsetMs = 100L, bluetoothLatencyOffsetMs = -180L))

        val root = tempFolder.newFolder("music")
        val lrcFile = File(root, "song.lrc").apply { writeText(lrcRaw, Charsets.UTF_8) }
        assertTrue(LrcParser.writeOffsetToLrcFile(lrcFile, -250L, root))
        assertTrue(lrcFile.readText().contains("[offset:-250]"))

        // Outside root must be rejected
        val outsideLrc = File(tempFolder.root, "evil.lrc").apply { writeText(lrcRaw, Charsets.UTF_8) }
        assertFalse(LrcParser.writeOffsetToLrcFile(outsideLrc, 500L, root))
    }

    @Test
    fun listeningInsightsIgnoresShortPlaysComputesTop5AndStaysUnder50Kb() {
        val storeFile = File(tempFolder.root, "insights.json")
        val store = PlaybackStateStore(storeFile)

        // Ignored (< 30s)
        val tShort = Track(1L, "Skip", "ArtistX", "Alb", path = "/m/skip.mp3")
        store.recordPlaySession(tShort, 15_000L)
        assertEquals(0L, store.getInsightsSummary().totalListenedMs)

        // Record 150 distinct tracks to verify Top 5 + < 50 KB size cap
        for (i in 1..150) {
            val t = Track(i.toLong(), "Song $i", "Artist ${i % 8}", "Album", path = "/m/song_$i.flac")
            val repeats = if (i <= 5) 10 - i else 1
            for (r in 1..repeats) {
                store.recordPlaySession(t, 60_000L)
            }
        }

        val summary = store.getInsightsSummary()
        assertTrue(summary.totalListenedMs > 0L)
        assertEquals(5, summary.topTracks.size)
        assertEquals(5, summary.topArtists.size)
        assertEquals("Song 1", summary.topTracks[0].title)
        assertTrue("Expected JSON < 50KB, got ${store.getSerializedSizeBytes()}", store.getSerializedSizeBytes() < 50 * 1024)
    }
}
