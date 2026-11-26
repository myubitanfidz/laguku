package com.laguku.player.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.laguku.player.R

/**
 * Quick Settings Tile providing one-tap Play/Pause control without background polling.
 */
class LagukuTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val activeTrack = PlaybackService.lastKnownTrackTitle
        val playing = PlaybackService.lastKnownPlayingState
        tile.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = activeTrack?.takeIf { it.isNotBlank() }
                ?: getString(if (playing) R.string.qs_playing else R.string.qs_paused)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val serviceIntent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_PLAY_PAUSE
        }
        runCatching {
            startForegroundService(serviceIntent)
        }
        qsTile?.let { tile ->
            val nextPlaying = !PlaybackService.lastKnownPlayingState
            tile.state = if (nextPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }
}
