package com.laguku.player.ui.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.LyricLine
import com.laguku.player.model.Track
import com.laguku.player.ui.glass.StackBlur
import java.io.File

/**
 * Renders a shareable Liquid Glass lyric quote card (900x1125 PNG) off the main thread
 * and invokes Android's share sheet via [FileProvider].
 */
object LyricCardRenderer {

    private const val CARD_W = 900
    private const val CARD_H = 1125

    fun renderCardFile(
        context: Context,
        track: Track,
        lines: List<LyricLine>,
        coverBitmap: Bitmap?,
        palette: CoverPalette
    ): File? {
        if (lines.isEmpty()) return null
        val outBmp = Bitmap.createBitmap(CARD_W, CARD_H, Bitmap.Config.ARGB_8888)
        var blurredBg: Bitmap? = null
        try {
            val canvas = Canvas(outBmp)
            if (coverBitmap != null && !coverBitmap.isRecycled) {
                blurredBg = StackBlur.createBlurredBackdrop(coverBitmap, radius = 12)
                val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(blurredBg, null, RectF(0f, 0f, CARD_W.toFloat(), CARD_H.toFloat()), bmpPaint)
            } else {
                canvas.drawColor(palette.deepBackdrop)
            }

            // Glass card container
            val cardRect = RectF(64f, 96f, CARD_W - 64f, CARD_H - 96f)
            val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0x99 shl 24) or (palette.deepBackdrop and 0x00FFFFFF)
            }
            canvas.drawRoundRect(cardRect, 48f, 48f, basePaint)

            val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0x28 shl 24) or (palette.accentGlow and 0x00FFFFFF)
            }
            canvas.drawRoundRect(cardRect, 48f, 48f, tintPaint)

            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                shader = LinearGradient(
                    cardRect.left, cardRect.top, cardRect.right, cardRect.bottom,
                    0x57FFFFFF, 0x10FFFFFF, Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, 48f, 48f, rimPaint)

            // Header: cover thumbnail + title/artist
            var contentTop = 156f
            if (coverBitmap != null && !coverBitmap.isRecycled) {
                val thumbRect = RectF(112f, contentTop, 224f, contentTop + 112f)
                canvas.save()
                val clipPath = android.graphics.Path().apply {
                    addRoundRect(thumbRect, 20f, 20f, android.graphics.Path.Direction.CW)
                }
                canvas.clipPath(clipPath)
                canvas.drawBitmap(coverBitmap, null, thumbRect, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.restore()
            }

            val metaStart = if (coverBitmap != null && !coverBitmap.isRecycled) 252f else 112f
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 34f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3FFFFFF.toInt()
                textSize = 26f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            canvas.drawText(track.title.take(36), metaStart, contentTop + 48f, titlePaint)
            canvas.drawText(track.artist.take(40), metaStart, contentTop + 88f, artistPaint)

            // Selected lyric lines
            val quoteText = lines.take(4).joinToString("\n\n") { line ->
                val tr = line.translation?.takeIf { it.isNotBlank() }
                if (tr != null) "${line.text}\n$tr" else line.text
            }
            val quotePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 42f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val textWidth = (CARD_W - 224)
            val layout = StaticLayout.Builder.obtain(quoteText, 0, quoteText.length, quotePaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(10f, 1.15f)
                .setMaxLines(12)
                .build()

            canvas.save()
            canvas.translate(112f, 340f)
            layout.draw(canvas)
            canvas.restore()

            // Footer watermark
            val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.accentGlow
                textSize = 26f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            canvas.drawText("LAGUKU", 112f, CARD_H - 140f, brandPaint)

            val outDir = File(context.cacheDir, "shared_cards").apply { mkdirs() }
            val outFile = File(outDir, "laguku_lyric_card.png")
            outFile.outputStream().use { fos ->
                outBmp.compress(Bitmap.CompressFormat.PNG, 96, fos)
            }
            return outFile
        } catch (_: Exception) {
            return null
        } finally {
            blurredBg?.recycle()
            outBmp.recycle()
        }
    }

    fun buildShareIntent(context: Context, imageFile: File, trackTitle: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, trackTitle)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
