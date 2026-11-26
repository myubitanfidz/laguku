package com.laguku.player.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.LyricLine
import com.laguku.player.model.Track
import java.io.File

/**
 * Compatibility facade delegating quote card rendering & sharing to [LyricShareCardRenderer].
 */
object LyricCardRenderer {

    fun renderCardFile(
        context: Context,
        track: Track,
        lines: List<LyricLine>,
        coverBitmap: Bitmap?,
        palette: CoverPalette
    ): File? = LyricShareCardRenderer.renderCardFile(context, track, lines, coverBitmap, palette)

    fun buildShareIntent(context: Context, imageFile: File, trackTitle: String): Intent =
        LyricShareCardRenderer.buildShareIntent(context, imageFile, trackTitle)
}
