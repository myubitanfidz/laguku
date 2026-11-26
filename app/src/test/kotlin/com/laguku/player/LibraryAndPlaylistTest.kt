package com.laguku.player

import com.laguku.player.data.LibraryScanner
import com.laguku.player.model.AudioQualityInfo
import com.laguku.player.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class LibraryAndPlaylistTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun groupByFolder_groupsDirectFoldersWithoutDuplicatesAndSortsCleanly() {
        val tracks = listOf(
            Track(
                id = 1L,
                title = "Beta",
                artist = "Artist A",
                album = "Album 1",
                trackNumber = 2,
                path = "/music/rock/beta.flac",
                folderPath = "/music/rock"
            ),
            Track(
                id = 2L,
                title = "Alpha",
                artist = "Artist A",
                album = "Album 1",
                trackNumber = 1,
                path = "/music/rock/alpha.flac",
                folderPath = "/music/rock"
            ),
            // Duplicate path should be deduplicated
            Track(
                id = 1L,
                title = "Beta",
                artist = "Artist A",
                album = "Album 1",
                trackNumber = 2,
                path = "/music/rock/beta.flac",
                folderPath = "/music/rock"
            ),
            Track(
                id = 3L,
                title = "Jazz Tune",
                artist = "Artist B",
                album = "Album 2",
                trackNumber = 1,
                path = "/music/jazz/tune.mp3",
                folderPath = "/music/jazz"
            )
        )

        val folders = LibraryScanner.groupByFolder(tracks)
        assertEquals(2, folders.size)
        assertEquals("jazz", folders[0].name)
        assertEquals(1, folders[0].tracks.size)

        assertEquals("rock", folders[1].name)
        assertEquals(2, folders[1].tracks.size)
        assertEquals("Alpha", folders[1].tracks[0].title)
        assertEquals("Beta", folders[1].tracks[1].title)
    }

    @Test
    fun groupByAlbum_groupsAndSortsByTrackNumberWithoutDuplicates() {
        val tracks = listOf(
            Track(id = 10L, title = "Track Two", artist = "Band", album = "Zeta", albumId = 100L, trackNumber = 2, path = "/a/2.mp3"),
            Track(id = 11L, title = "Track One", artist = "Band", album = "Zeta", albumId = 100L, trackNumber = 1, path = "/a/1.mp3"),
            Track(id = 11L, title = "Track One", artist = "Band", album = "Zeta", albumId = 100L, trackNumber = 1, path = "/a/1.mp3"),
            Track(id = 12L, title = "Intro", artist = "Solo", album = "Alpha", albumId = 0L, trackNumber = 1, path = "/b/1.flac")
        )

        val albums = LibraryScanner.groupByAlbum(tracks)
        assertEquals(2, albums.size)
        assertEquals("Alpha", albums[0].name)
        assertEquals("Zeta", albums[1].name)
        assertEquals(2, albums[1].tracks.size)
        assertEquals("Track One", albums[1].tracks[0].title)
        assertEquals("Track Two", albums[1].tracks[1].title)
    }

    @Test
    fun groupByArtist_normalizesCaseDeduplicatesAndSorts() {
        val tracks = listOf(
            Track(id = 1L, title = "Song B", artist = "Miles Davis", album = "Kind of Blue", trackNumber = 2, path = "/m/2.flac"),
            Track(id = 2L, title = "Song A", artist = "miles davis", album = "Birth of the Cool", trackNumber = 1, path = "/m/1.flac"),
            Track(id = 2L, title = "Song A", artist = "miles davis", album = "Birth of the Cool", trackNumber = 1, path = "/m/1.flac"),
            Track(id = 3L, title = "Aria", artist = "Bach", album = "Suites", trackNumber = 1, path = "/b/1.opus")
        )

        val artists = LibraryScanner.groupByArtist(tracks)
        assertEquals(2, artists.size)
        assertEquals("Bach", artists[0].name)
        assertEquals(1, artists[0].tracks.size)
        assertEquals("Miles Davis", artists[1].name)
        assertEquals(2, artists[1].tracks.size)
        assertEquals("Birth of the Cool", artists[1].tracks[0].album)
        assertEquals("Kind of Blue", artists[1].tracks[1].album)
    }

    @Test
    fun scanDirectory_findsOnlySupportedAudioFilesAndPreservesDirectFolder() {
        val root = tempFolder.newFolder("music")
        val rockDir = File(root, "rock").apply { mkdirs() }
        val jazzDir = File(root, "jazz").apply { mkdirs() }

        val flac = File(rockDir, "01 - Thunder.flac").apply { writeBytes(byteArrayOf(0)) }
        val opus = File(rockDir, "02 - Breeze.opus").apply { writeBytes(byteArrayOf(0)) }
        val m4a = File(jazzDir, "Blue.m4a").apply { writeBytes(byteArrayOf(0)) }
        File(rockDir, "cover.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(jazzDir, "notes.txt").writeText("ignored")

        val scanned = LibraryScanner.scanDirectory(root)
        assertEquals(3, scanned.size)
        val paths = scanned.map { File(it.path).canonicalPath }.toSet()
        assertTrue(paths.contains(flac.canonicalPath))
        assertTrue(paths.contains(opus.canonicalPath))
        assertTrue(paths.contains(m4a.canonicalPath))

        val folders = LibraryScanner.groupByFolder(scanned)
        assertEquals(2, folders.size)
        assertEquals(listOf("jazz", "rock"), folders.map { it.name })
    }

    @Test
    fun writeAndReadM3u8_roundTripRelativeAndAbsoluteInsideAllowedRoot() {
        val root = tempFolder.newFolder("allowed_music")
        val subDir = File(root, "albums/ost").apply { mkdirs() }
        val playlistsDir = File(root, "playlists").apply { mkdirs() }

        val song1 = File(subDir, "theme.flac").apply { writeBytes(byteArrayOf(0)) }
        val song2 = File(root, "single.mp3").apply { writeBytes(byteArrayOf(0)) }

        val playlistFile = File(playlistsDir, "favorites.m3u8")
        val tracks = listOf(
            Track(1L, "Theme", "Composer", "OST", durationMs = 125_000L, path = song1.absolutePath, quality = AudioQualityInfo("FLAC")),
            Track(2L, "Single", "Artist", "Singles", durationMs = 200_000L, path = song2.absolutePath, quality = AudioQualityInfo("MP3"))
        )

        LibraryScanner.writeM3u8(playlistFile, tracks, root)
        assertTrue(playlistFile.isFile)

        // Also test reading a playlist containing both relative and absolute paths inside root
        val readBack = LibraryScanner.readM3u8(playlistFile, root)
        assertEquals(2, readBack.size)
        assertEquals(song1.canonicalPath, readBack[0].canonicalPath)
        assertEquals(song2.canonicalPath, readBack[1].canonicalPath)

        // Verify explicit mix of relative and absolute entries inside allowedRoot
        val mixedPlaylist = File(root, "mixed.m3u8")
        mixedPlaylist.writeText(
            """
            #EXTM3U
            #EXTINF:125,Composer - Theme
            albums/ost/theme.flac
            ${song2.absolutePath}
            """.trimIndent(),
            Charsets.UTF_8
        )
        val mixedRead = LibraryScanner.readM3u8(mixedPlaylist, root)
        assertEquals(2, mixedRead.size)
        assertEquals(song1.canonicalPath, mixedRead[0].canonicalPath)
        assertEquals(song2.canonicalPath, mixedRead[1].canonicalPath)
    }

    @Test
    fun readM3u8_rejectsPathTraversalAndOutsidePaths() {
        val parent = tempFolder.newFolder("sandbox")
        val allowedRoot = File(parent, "music").apply { mkdirs() }
        val outsideDir = File(parent, "secret").apply { mkdirs() }
        val outsideSong = File(outsideDir, "private.mp3").apply { writeBytes(byteArrayOf(0)) }
        val validSong = File(allowedRoot, "ok.flac").apply { writeBytes(byteArrayOf(0)) }

        val playlist = File(allowedRoot, "malicious.m3u8")
        playlist.writeText(
            """
            #EXTM3U
            ../../etc/passwd
            ../secret/private.mp3
            /etc/passwd
            ${outsideSong.absolutePath}
            file:///etc/passwd
            http://example.com/stream.mp3
            ok.flac
            """.trimIndent(),
            Charsets.UTF_8
        )

        val entries = LibraryScanner.readM3u8(playlist, allowedRoot)
        assertEquals(1, entries.size)
        assertEquals(validSong.canonicalPath, entries[0].canonicalPath)

        // Optional symlink escape check if OS permits creating symlinks
        val linkFile = File(allowedRoot, "escape_link.mp3")
        val linkCreated = runCatching {
            Files.createSymbolicLink(linkFile.toPath(), outsideSong.toPath())
            true
        }.getOrDefault(false)

        if (linkCreated) {
            val linkPlaylist = File(allowedRoot, "symlink.m3u8")
            linkPlaylist.writeText("escape_link.mp3\nok.flac\n", Charsets.UTF_8)
            val linkEntries = LibraryScanner.readM3u8(linkPlaylist, allowedRoot)
            assertEquals(1, linkEntries.size)
            assertEquals(validSong.canonicalPath, linkEntries[0].canonicalPath)
        }
    }

    @Test
    fun writeM3u8_skipsEscapingTracksAndPathsOutsideAllowedRoot() {
        val parent = tempFolder.newFolder("sandbox_write")
        val allowedRoot = File(parent, "music").apply { mkdirs() }
        val outsideDir = File(parent, "outside").apply { mkdirs() }
        val outsideSong = File(outsideDir, "leak.mp3").apply { writeBytes(byteArrayOf(0)) }
        val validSong = File(allowedRoot, "inside.mp3").apply { writeBytes(byteArrayOf(0)) }

        val playlist = File(allowedRoot, "safe.m3u8")
        val tracks = listOf(
            Track(1L, "Traversal", "Bad", "Bad", path = "../../etc/passwd"),
            Track(2L, "Outside", "Bad", "Bad", path = outsideSong.absolutePath),
            Track(3L, "Valid", "Good", "Good", path = validSong.absolutePath)
        )

        LibraryScanner.writeM3u8(playlist, tracks, allowedRoot)
        val content = playlist.readText(Charsets.UTF_8)
        assertFalse(content.contains("passwd"))
        assertFalse(content.contains("leak.mp3"))
        assertTrue(content.contains("inside.mp3"))

        val readBack = LibraryScanner.readM3u8(playlist, allowedRoot)
        assertEquals(1, readBack.size)
        assertEquals(validSong.canonicalPath, readBack[0].canonicalPath)
    }

    @Test
    fun smartLibraryFiltersMinDurationBlacklistPinsFoldersAndBuildsSmartPlaylistsAndHealthReport() {
        val root = tempFolder.newFolder("smart_lib")
        val pinnedDir = File(root, "ZebraPinned").apply { mkdirs() }
        val normalDir = File(root, "AlphaNormal").apply { mkdirs() }
        val hiddenDir = File(root, "HiddenSfx").apply { mkdirs() }

        val validFlac = File(pinnedDir, "hi.flac").apply { writeBytes("fLaC".toByteArray() + ByteArray(32)) }
        val validMp3 = File(normalDir, "ok.mp3").apply { writeBytes("ID3".toByteArray() + ByteArray(32)) }
        val dupMp3 = File(normalDir, "ok_copy.mp3").apply { writeBytes("ID3".toByteArray() + ByteArray(32)) }
        val corruptFlac = File(normalDir, "broken.flac").apply { writeBytes(ByteArray(32)) } // bad magic
        val shortTrack = Track(4L, "Ping", "SFX", "SFX", durationMs = 12_000L, path = validMp3.absolutePath, folderPath = normalDir.absolutePath)

        val tracks = listOf(
            Track(1L, "Hi", "Band", "Alb", durationMs = 200_000L, path = validFlac.absolutePath, folderPath = pinnedDir.absolutePath, quality = AudioQualityInfo("FLAC"), dateAddedSec = 500L),
            Track(2L, "Same Song", "Artist", "Alb", durationMs = 180_000L, path = validMp3.absolutePath, folderPath = normalDir.absolutePath, quality = AudioQualityInfo("MP3"), dateAddedSec = 200L),
            Track(3L, "Same Song", "Artist", "Alb", durationMs = 180_500L, path = dupMp3.absolutePath, folderPath = normalDir.absolutePath, quality = AudioQualityInfo("MP3"), dateAddedSec = 100L),
            shortTrack,
            Track(5L, "Hidden", "X", "X", durationMs = 90_000L, path = File(hiddenDir, "a.mp3").absolutePath, folderPath = hiddenDir.absolutePath),
            Track(6L, "Broken", "X", "X", durationMs = 100_000L, path = corruptFlac.absolutePath, folderPath = normalDir.absolutePath)
        )

        val filtered = LibraryScanner.filterTracks(tracks, minDurationMs = 30_000L, blacklistedFolders = setOf(hiddenDir.absolutePath))
        assertFalse(filtered.any { it.id == 4L })
        assertFalse(filtered.any { it.id == 5L })

        val pinnedGroups = LibraryScanner.groupByFolderWithPins(filtered, setOf(pinnedDir.absolutePath))
        assertEquals("ZebraPinned", pinnedGroups[0].name)

        val smart = LibraryScanner.buildSmartPlaylists(
            filtered,
            totalPlayCounts = mapOf(validMp3.absolutePath to 3),
            monthlyPlayCounts = mapOf(validMp3.absolutePath to 2)
        )
        assertEquals(4, smart.size)
        assertEquals("Baru Ditambahkan", smart[0].name)
        assertEquals("Sering Diputar Bulan Ini", smart[1].name)
        assertEquals(1, smart[1].tracks.size)
        assertEquals("Koleksi Lossless", smart[3].name)
        assertEquals(1, smart[3].tracks.size)

        val health = LibraryScanner.scanLibraryHealth(filtered)
        assertEquals(1, health.corruptTracks.size)
        assertEquals(6L, health.corruptTracks[0].id)
        assertEquals(1, health.duplicateGroups.size)
        assertEquals(2, health.duplicateGroups[0].size)
    }
}
