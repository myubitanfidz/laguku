package com.laguku.player.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import com.laguku.player.metadata.BinaryTagReader
import com.laguku.player.model.AlbumGroup
import com.laguku.player.model.ArtistGroup
import com.laguku.player.model.AudioQualityInfo
import com.laguku.player.model.FolderNode
import com.laguku.player.model.Playlist
import com.laguku.player.model.Track
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

data class LibraryHealthReport(
    val duplicateGroups: List<List<Track>>,
    val corruptTracks: List<Track>
)

/**
 * Music library scanner (MediaStore + direct folder traversal) and hardened M3U8 playlist I/O.
 */
object LibraryScanner {

    val SUPPORTED_EXTENSIONS: Set<String> = setOf(
        "flac", "alac", "m4a", "opus", "ogg", "aac", "mp3", "wav", "amr"
    )
    private val LOSSLESS_CODECS: Set<String> = setOf("FLAC", "ALAC", "WAV", "PCM", "AIFF", "DSD")

    fun scanMediaStore(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val cursor = runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )
        }.getOrNull() ?: return emptyList()

        cursor.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.Audio.Media._ID)
            val titleIdx = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistIdx = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumIdx = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val albumIdIdx = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val trackIdx = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val durationIdx = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            val addedIdx = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val id = if (idIdx >= 0) c.getLong(idIdx) else 0L
                val path = if (dataIdx >= 0) c.getString(dataIdx).orEmpty() else ""
                val dateAddedSec = if (addedIdx >= 0) c.getLong(addedIdx).coerceAtLeast(0L) else 0L
                val file = if (path.isNotBlank()) File(path) else null
                val tags = if (file != null && file.isFile && file.canRead()) {
                    runCatching { BinaryTagReader.read(file) }.getOrNull()
                } else null

                val rawTitle = if (titleIdx >= 0) c.getString(titleIdx) else null
                val rawArtist = if (artistIdx >= 0) c.getString(artistIdx) else null
                val rawAlbum = if (albumIdx >= 0) c.getString(albumIdx) else null
                val albumId = if (albumIdIdx >= 0) c.getLong(albumIdIdx) else 0L
                val rawTrackNum = if (trackIdx >= 0) c.getInt(trackIdx) else 0
                val durationMs = if (durationIdx >= 0) c.getLong(durationIdx).coerceAtLeast(0L) else 0L

                val title = tags?.title?.takeIf { it.isNotBlank() }
                    ?: rawTitle?.takeIf { it.isNotBlank() }
                    ?: file?.nameWithoutExtension
                    ?: "Unknown Title"
                val artist = tags?.artist?.takeIf { it.isNotBlank() }
                    ?: rawArtist?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: "Unknown Artist"
                val album = tags?.album?.takeIf { it.isNotBlank() }
                    ?: rawAlbum?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: "Unknown Album"
                val trackNum = tags?.trackNumber ?: normalizeTrackNumber(rawTrackNum)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                ).toString()
                val folderPath = file?.parentFile?.absolutePath.orEmpty()
                val codecLabel = extensionToCodecLabel(file?.extension.orEmpty())

                tracks.add(
                    Track(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = albumId,
                        trackNumber = trackNum,
                        durationMs = durationMs,
                        path = path,
                        contentUri = contentUri,
                        folderPath = folderPath,
                        replayGainDb = tags?.replayGainDb,
                        quality = AudioQualityInfo(codec = codecLabel),
                        dateAddedSec = dateAddedSec,
                        chapters = tags?.chapters ?: emptyList()
                    )
                )
            }
        }
        return tracks
    }

    fun scanDirectory(rootDir: File): List<Track> {
        if (!rootDir.exists() || !rootDir.isDirectory) return emptyList()
        val files = rootDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            .sortedBy { it.absolutePath.lowercase() }
            .toList()

        return files.map { file ->
            val tags = runCatching { BinaryTagReader.read(file) }.getOrNull()
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            val title = tags?.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
            val artist = tags?.artist?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val album = tags?.album?.takeIf { it.isNotBlank() }
                ?: file.parentFile?.name?.takeIf { it.isNotBlank() }
                ?: "Unknown Album"
            val folderPath = file.parentFile?.absolutePath.orEmpty()

            Track(
                id = stablePositiveId(canonical),
                title = title,
                artist = artist,
                album = album,
                albumId = stablePositiveId("${album.lowercase()}|${artist.lowercase()}"),
                trackNumber = tags?.trackNumber ?: 0,
                durationMs = 0L,
                path = file.absolutePath,
                contentUri = "",
                folderPath = folderPath,
                replayGainDb = tags?.replayGainDb,
                quality = AudioQualityInfo(codec = extensionToCodecLabel(file.extension)),
                dateAddedSec = (file.lastModified() / 1000L).coerceAtLeast(0L),
                chapters = tags?.chapters ?: emptyList()
            )
        }
    }

    fun groupByFolder(tracks: List<Track>): List<FolderNode> {
        val deduplicated = tracks.distinctBy { trackUniqueKey(it) }
        return deduplicated
            .groupBy { track ->
                track.folderPath.ifBlank { File(track.path).parent.orEmpty() }
            }
            .map { (folderPath, groupTracks) ->
                val folderName = File(folderPath).name.ifBlank { folderPath.ifBlank { "Root" } }
                val sortedTracks = groupTracks.sortedWith(
                    compareBy<Track> { it.trackNumber }.thenBy { it.title.lowercase() }
                )
                FolderNode(
                    path = folderPath,
                    name = folderName,
                    tracks = sortedTracks
                )
            }
            .sortedWith(compareBy<FolderNode> { it.name.lowercase() }.thenBy { it.path.lowercase() })
    }

    fun groupByFolderWithPins(tracks: List<Track>, pinnedFolders: Set<String>): List<FolderNode> {
        val base = groupByFolder(tracks)
        if (pinnedFolders.isEmpty()) return base
        return base.sortedWith(
            compareByDescending<FolderNode> { it.path in pinnedFolders }
                .thenBy { it.name.lowercase() }
                .thenBy { it.path.lowercase() }
        )
    }

    fun filterTracks(
        tracks: List<Track>,
        minDurationMs: Long,
        blacklistedFolders: Set<String>
    ): List<Track> {
        if (minDurationMs <= 0L && blacklistedFolders.isEmpty()) return tracks
        return tracks.filter { track ->
            val folder = track.folderPath.ifBlank { File(track.path).parent.orEmpty() }
            val blacklisted = blacklistedFolders.any { blk ->
                folder == blk || folder.startsWith("$blk${File.separator}")
            }
            val tooShort = minDurationMs > 0L && track.durationMs in 1L until minDurationMs
            !blacklisted && !tooShort
        }
    }

    fun buildSmartPlaylists(
        tracks: List<Track>,
        totalPlayCounts: Map<String, Int>,
        monthlyPlayCounts: Map<String, Int>
    ): List<Playlist> {
        val deduped = tracks.distinctBy { trackUniqueKey(it) }
        val recentlyAdded = deduped
            .sortedByDescending { it.dateAddedSec }
            .take(50)
        val mostPlayedThisMonth = deduped
            .filter { (monthlyPlayCounts[trackUniqueKey(it)] ?: 0) > 0 }
            .sortedByDescending { monthlyPlayCounts[trackUniqueKey(it)] ?: 0 }
            .take(50)
        val neverPlayed = deduped
            .filter { (totalPlayCounts[trackUniqueKey(it)] ?: 0) == 0 }
        val lossless = deduped
            .filter { it.quality.codec.uppercase() in LOSSLESS_CODECS }

        return listOf(
            Playlist("Baru Ditambahkan", "smart://recently_added", recentlyAdded),
            Playlist("Sering Diputar Bulan Ini", "smart://top_month", mostPlayedThisMonth),
            Playlist("Belum Pernah Diputar", "smart://unplayed", neverPlayed),
            Playlist("Koleksi Lossless", "smart://lossless", lossless)
        )
    }

    fun scanLibraryHealth(tracks: List<Track>): LibraryHealthReport {
        val deduped = tracks.distinctBy { trackUniqueKey(it) }
        val corrupt = ArrayList<Track>()
        val valid = ArrayList<Track>(deduped.size)

        for (t in deduped) {
            val file = File(t.path)
            if (!file.exists() || !file.isFile || !file.canRead() || file.length() < 16L) {
                corrupt.add(t)
                continue
            }
            val headerOk = hasPlausibleAudioHeader(file)
            if (!headerOk) {
                corrupt.add(t)
            } else {
                valid.add(t)
            }
        }

        val dupGroups = valid
            .groupBy { t ->
                val durBucket = (t.durationMs / 2_000L)
                "${t.title.trim().lowercase()}|${t.artist.trim().lowercase()}|$durBucket"
            }
            .values
            .filter { it.size > 1 }
            .toList()

        return LibraryHealthReport(duplicateGroups = dupGroups, corruptTracks = corrupt)
    }

    fun refreshFolder(context: Context, folder: File, onComplete: () -> Unit) {
        if (!folder.exists() || !folder.isDirectory) {
            onComplete()
            return
        }
        val audioPaths = folder.listFiles { f ->
            f.isFile && f.extension.lowercase() in SUPPORTED_EXTENSIONS
        }?.map { it.absolutePath }?.toTypedArray()
        if (audioPaths.isNullOrEmpty()) {
            onComplete()
            return
        }
        var remaining = audioPaths.size
        MediaScannerConnection.scanFile(context, audioPaths, null) { _, _ ->
            remaining--
            if (remaining <= 0) {
                onComplete()
            }
        }
    }

    private fun hasPlausibleAudioHeader(file: File): Boolean {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len < 16L) return false
                val buf = ByteArray(12)
                raf.readFully(buf)
                val ext = file.extension.lowercase()
                val m4 = String(buf, 0, 4, Charsets.US_ASCII)
                when (ext) {
                    "flac" -> m4 == "fLaC"
                    "ogg", "opus" -> m4 == "OggS"
                    "wav" -> m4 == "RIFF" && String(buf, 8, 4, Charsets.US_ASCII) == "WAVE"
                    "m4a", "alac" -> String(buf, 4, 4, Charsets.US_ASCII) == "ftyp"
                    "mp3" -> m4.startsWith("ID3") ||
                        ((buf[0].toInt() and 0xFF) == 0xFF && (buf[1].toInt() and 0xE0) == 0xE0)
                    else -> true
                }
            }
        }.getOrDefault(false)
    }

    fun groupByAlbum(tracks: List<Track>): List<AlbumGroup> {
        val deduplicated = tracks.distinctBy { trackUniqueKey(it) }
        return deduplicated
            .groupBy { track ->
                if (track.albumId != 0L) {
                    "id:${track.albumId}"
                } else {
                    "name:${track.album.trim().lowercase()}"
                }
            }
            .map { (_, groupTracks) ->
                val first = groupTracks.first()
                val albumName = first.album.ifBlank { "Unknown Album" }
                val albumArtist = groupTracks
                    .map { it.artist.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: "Unknown Artist"
                val id = if (first.albumId != 0L) {
                    first.albumId
                } else {
                    stablePositiveId(albumName.lowercase())
                }
                val sortedTracks = groupTracks.sortedWith(
                    compareBy<Track> { it.trackNumber }.thenBy { it.title.lowercase() }
                )
                AlbumGroup(
                    id = id,
                    name = albumName,
                    artist = albumArtist,
                    tracks = sortedTracks
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun groupByArtist(tracks: List<Track>): List<ArtistGroup> {
        val deduplicated = tracks.distinctBy { trackUniqueKey(it) }
        return deduplicated
            .groupBy { track ->
                track.artist.trim().ifEmpty { "Unknown Artist" }.lowercase()
            }
            .map { (_, groupTracks) ->
                val displayName = groupTracks
                    .map { it.artist.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?: "Unknown Artist"
                val sortedTracks = groupTracks.sortedWith(
                    compareBy<Track> { it.album.lowercase() }
                        .thenBy { it.trackNumber }
                        .thenBy { it.title.lowercase() }
                )
                ArtistGroup(
                    name = displayName,
                    tracks = sortedTracks
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun readM3u8(playlistFile: File, allowedRootDir: File): List<File> {
        if (!playlistFile.isFile) return emptyList()
        val rootCanonical = allowedRootDir.canonicalFile
        val rootReal = resolveRealPath(rootCanonical) ?: rootCanonical.toPath()
        val baseDir = playlistFile.parentFile ?: allowedRootDir
        val resolvedFiles = mutableListOf<File>()

        playlistFile.readLines(Charsets.UTF_8).forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val candidate = resolvePlaylistEntry(line, baseDir) ?: return@forEach
            val targetCanonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            val targetReal = resolveRealPath(targetCanonical) ?: return@forEach
            if (isWithinRoot(targetCanonical, rootCanonical) && isWithinRootPath(targetReal, rootReal)) {
                resolvedFiles.add(targetCanonical)
            }
        }
        return resolvedFiles
    }

    fun writeM3u8(playlistFile: File, tracks: List<Track>, allowedRootDir: File) {
        val rootCanonical = allowedRootDir.canonicalFile
        val rootReal = resolveRealPath(rootCanonical) ?: rootCanonical.toPath()
        val playlistParent = playlistFile.parentFile
        playlistParent?.mkdirs()

        val sb = StringBuilder("#EXTM3U\n")
        val baseDir = playlistParent ?: allowedRootDir

        for (track in tracks) {
            val rawPath = track.path.trim()
            if (rawPath.isEmpty()) continue
            val candidate = resolvePlaylistEntry(rawPath, baseDir) ?: continue
            val targetCanonical = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
            val targetReal = resolveRealPath(targetCanonical) ?: continue
            if (!isWithinRoot(targetCanonical, rootCanonical) || !isWithinRootPath(targetReal, rootReal)) continue

            val durationSeconds = (track.durationMs / 1000L).coerceAtLeast(0L)
            sb.append("#EXTINF:")
                .append(durationSeconds)
                .append(',')
                .append(track.artist)
                .append(" - ")
                .append(track.title)
                .append('\n')

            val entryPath = computePlaylistEntryPath(targetCanonical, playlistParent?.canonicalFile)
            sb.append(entryPath).append('\n')
        }

        playlistFile.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun resolvePlaylistEntry(entry: String, baseDir: File): File? {
        if (entry.startsWith("file://", ignoreCase = true)) {
            val uriFile = runCatching { File(URI(entry)) }.getOrNull() ?: return null
            return uriFile
        }
        if (entry.contains("://")) {
            return null
        }
        val rawFile = File(entry)
        val isAbsoluteOrRooted = rawFile.isAbsolute || entry.startsWith("/") || entry.startsWith("\\")
        return if (isAbsoluteOrRooted) rawFile else File(baseDir, entry)
    }

    private fun resolveRealPath(file: File): Path? {
        val path = file.toPath()
        if (Files.isSymbolicLink(path) && !Files.exists(path)) {
            return null
        }
        if (Files.exists(path)) {
            return runCatching { path.toRealPath() }.getOrNull()
        }
        // Resolve nearest existing ancestor to catch symlinked parent directories on Windows/Unix
        var current: Path? = path.parent
        val tail = mutableListOf<Path>()
        path.fileName?.let { tail.add(it) }
        while (current != null && !Files.exists(current)) {
            if (Files.isSymbolicLink(current)) return null
            current.fileName?.let { tail.add(it) }
            current = current.parent
        }
        val baseReal = if (current != null) {
            runCatching { current.toRealPath() }.getOrNull() ?: return null
        } else {
            return path.normalize()
        }
        var resolved = baseReal
        for (i in tail.lastIndex downTo 0) {
            resolved = resolved.resolve(tail[i])
        }
        return resolved.normalize()
    }

    private fun isWithinRootPath(targetReal: Path, rootReal: Path): Boolean {
        val normalizedTarget = targetReal.normalize()
        val normalizedRoot = rootReal.normalize()
        return normalizedTarget == normalizedRoot || normalizedTarget.startsWith(normalizedRoot)
    }

    private fun isWithinRoot(targetCanonical: File, rootCanonical: File): Boolean {
        val targetPath = targetCanonical.canonicalPath
        val rootPath = rootCanonical.canonicalPath
        if (targetPath == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return targetPath.startsWith(prefix)
    }

    private fun computePlaylistEntryPath(targetCanonical: File, playlistDirCanonical: File?): String {
        if (playlistDirCanonical != null && isWithinRoot(targetCanonical, playlistDirCanonical)) {
            val rel = runCatching {
                playlistDirCanonical.toPath().relativize(targetCanonical.toPath()).toString()
            }.getOrNull()
            if (!rel.isNullOrBlank() && !rel.startsWith("..")) {
                return rel.replace('\\', '/')
            }
        }
        return targetCanonical.canonicalPath
    }

    private fun trackUniqueKey(track: Track): String {
        return track.path.takeIf { it.isNotBlank() } ?: "id:${track.id}"
    }

    private fun normalizeTrackNumber(raw: Int): Int {
        if (raw <= 0) return 0
        return if (raw >= 1000) raw % 1000 else raw
    }

    private fun extensionToCodecLabel(ext: String): String {
        return when (ext.lowercase()) {
            "flac" -> "FLAC"
            "alac" -> "ALAC"
            "m4a" -> "AAC"
            "opus" -> "OPUS"
            "ogg" -> "OGG"
            "aac" -> "AAC"
            "mp3" -> "MP3"
            "wav" -> "WAV"
            "amr" -> "AMR"
            else -> ext.uppercase().ifBlank { "UNKNOWN" }
        }
    }

    private fun stablePositiveId(key: String): Long {
        var hash = -3750763034362895579L
        for (ch in key) {
            hash = hash xor ch.code.toLong()
            hash *= 1099511628211L
        }
        val positive = hash and Long.MAX_VALUE
        return if (positive == 0L) 1L else positive
    }
}
