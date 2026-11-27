package com.laguku.player.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.laguku.player.data.LibraryScanner
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

data class TrackInstalledEvent(
    val audioUri: String?,
    val ttmlUri: String?,
    val lrcUri: String?,
    val title: String?,
    val artist: String?,
    val receivedAtMs: Long = System.currentTimeMillis()
)

/**
 * Receives explicit installation broadcasts from `com.laguku.installer`, immediately scans
 * `Music/Laguku/` without waiting for system-wide MediaStore indexing, and emits live
 * refresh events to UI observers.
 */
class TrackInstalledReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        val event = parseIntent(intent) ?: return
        val pendingResult = goAsync()

        ioExecutor.execute {
            try {
                @Suppress("DEPRECATION")
                val musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val lagukuDir = if (musicRoot != null) File(musicRoot, LAGUKU_SUBDIR) else null
                if (lagukuDir != null && lagukuDir.isDirectory) {
                    LibraryScanner.scanDirectory(lagukuDir)
                }

                val pathsToScan = LinkedHashSet<String>()
                event.audioUri?.let { raw ->
                    LibraryScanner.resolveAudioFile(appContext, Uri.parse(raw), musicRoot)?.let { file ->
                        pathsToScan.add(file.absolutePath)
                    }
                }
                if (lagukuDir != null && lagukuDir.isDirectory) {
                    lagukuDir.listFiles { f ->
                        f.isFile && f.extension.lowercase() in LibraryScanner.SUPPORTED_EXTENSIONS
                    }?.forEach { pathsToScan.add(it.absolutePath) }
                }
                if (pathsToScan.isNotEmpty()) {
                    MediaScannerConnection.scanFile(appContext, pathsToScan.toTypedArray(), null, null)
                }

                emitEvent(event)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    companion object {
        const val ACTION_TRACK_INSTALLED = "com.laguku.player.ACTION_TRACK_INSTALLED"
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_TTML_URI = "extra_ttml_uri"
        const val EXTRA_LRC_URI = "extra_lrc_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        private const val LAGUKU_SUBDIR = "Laguku"

        private val ioExecutor = Executors.newSingleThreadExecutor()
        private val listeners = CopyOnWriteArraySet<(TrackInstalledEvent) -> Unit>()

        @Volatile
        var latestEvent: TrackInstalledEvent? = null
            private set

        fun parseIntent(intent: Intent?): TrackInstalledEvent? {
            if (intent == null) return null
            return parsePayload(
                action = intent.action,
                dataUri = intent.dataString,
                extraAudioUri = intent.getStringExtra(EXTRA_AUDIO_URI),
                extraTtmlUri = intent.getStringExtra(EXTRA_TTML_URI),
                extraLrcUri = intent.getStringExtra(EXTRA_LRC_URI),
                extraTitle = intent.getStringExtra(EXTRA_TITLE),
                extraArtist = intent.getStringExtra(EXTRA_ARTIST)
            )
        }

        fun parsePayload(
            action: String?,
            dataUri: String?,
            extraAudioUri: String?,
            extraTtmlUri: String?,
            extraLrcUri: String?,
            extraTitle: String?,
            extraArtist: String?
        ): TrackInstalledEvent? {
            if (action != ACTION_TRACK_INSTALLED) return null
            val audioUri = dataUri?.takeIf { it.isNotBlank() }
                ?: extraAudioUri?.takeIf { it.isNotBlank() }
            val ttmlUri = extraTtmlUri?.takeIf { it.isNotBlank() }
            val lrcUri = extraLrcUri?.takeIf { it.isNotBlank() }
            val title = extraTitle?.takeIf { it.isNotBlank() }
            val artist = extraArtist?.takeIf { it.isNotBlank() }
            if (audioUri == null && title == null) return null
            return TrackInstalledEvent(
                audioUri = audioUri,
                ttmlUri = ttmlUri,
                lrcUri = lrcUri,
                title = title,
                artist = artist
            )
        }

        fun addListener(listener: (TrackInstalledEvent) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (TrackInstalledEvent) -> Unit) {
            listeners.remove(listener)
        }

        internal fun emitEvent(event: TrackInstalledEvent) {
            latestEvent = event
            for (listener in listeners) {
                runCatching { listener(event) }
            }
        }
    }
}
