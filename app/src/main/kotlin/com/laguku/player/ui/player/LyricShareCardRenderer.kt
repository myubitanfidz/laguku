package com.laguku.player.ui.player

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.laguku.player.R
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.LyricLine
import com.laguku.player.model.Track
import java.io.File
import java.io.FileNotFoundException

/**
 * Studio renderer for 1080x1350 Liquid Glass lyric quote PNG cards off the main thread,
 * paired with a pure-Kotlin [ContentProvider] ([LyricShareCardProvider]) for [Intent.ACTION_SEND].
 */
object LyricShareCardRenderer {

    const val CARD_WIDTH_PX = 1080
    const val CARD_HEIGHT_PX = 1350
    const val COVER_CORNER_RADIUS_PX = 20f
    const val MAX_QUOTE_LINES = 4
    const val AUTHORITY_SUFFIX = ".sharecardprovider"
    const val SHARED_CARDS_DIR = "shared_cards"
    const val OUTPUT_FILE_NAME = "laguku_quote_card.png"

    object ShareCardSpec {
        fun clampContiguousLines(lines: List<LyricLine>): List<LyricLine> {
            if (lines.isEmpty()) return emptyList()
            return lines.take(MAX_QUOTE_LINES)
        }
    }

    fun renderCardFile(
        context: Context,
        track: Track,
        lines: List<LyricLine>,
        coverBitmap: Bitmap?,
        palette: CoverPalette,
        meshPalette: NowPlayingView.AmbientMeshMath.MeshPalette? = null
    ): File? {
        val selected = ShareCardSpec.clampContiguousLines(lines)
        if (selected.isEmpty()) return null

        val outBmp = Bitmap.createBitmap(CARD_WIDTH_PX, CARD_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        var coverThumb: Bitmap? = null
        try {
            val canvas = Canvas(outBmp)
            val resolvedMesh = meshPalette ?: if (coverBitmap != null && !coverBitmap.isRecycled) {
                val sample = Bitmap.createScaledBitmap(coverBitmap, 32, 32, true)
                val pixels = IntArray(32 * 32)
                sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
                if (sample !== coverBitmap) sample.recycle()
                NowPlayingView.AmbientMeshMath.extractMeshPaletteFromPixels(pixels)
            } else {
                NowPlayingView.AmbientMeshMath.fromCoverPalette(palette)
            }

            // 1. 3-color Mesh Gradient background + adaptive contrast dimming veil
            canvas.drawColor(resolvedMesh.baseColor)
            val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
            drawMeshBlob(canvas, blobPaint, 260f, 310f, 840f, resolvedMesh.primaryBlob, 0xD2)
            drawMeshBlob(canvas, blobPaint, 860f, 640f, 780f, resolvedMesh.secondaryBlob, 0xC0)
            drawMeshBlob(canvas, blobPaint, 460f, 1080f, 820f, resolvedMesh.tertiaryBlob, 0xB4)

            val veilAlpha = NowPlayingView.AmbientMeshMath.computeAdaptiveVeilAlpha(
                resolvedMesh.averageLuminance,
                resolvedMesh.primaryBlob,
                resolvedMesh.secondaryBlob,
                resolvedMesh.tertiaryBlob
            )
            canvas.drawColor(((veilAlpha * 210f).toInt().coerceIn(48, 210) shl 24))

            // 2. Center Liquid Glass card with 135-degree directional rim light
            val cardRect = RectF(72f, 108f, CARD_WIDTH_PX - 72f, CARD_HEIGHT_PX - 108f)
            val cardRadius = 52f
            val glassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0x8F shl 24) or (resolvedMesh.baseColor and 0x00FFFFFF)
            }
            canvas.drawRoundRect(cardRect, cardRadius, cardRadius, glassFillPaint)

            val tintFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0x24 shl 24) or (palette.accentGlow and 0x00FFFFFF)
            }
            canvas.drawRoundRect(cardRect, cardRadius, cardRadius, tintFillPaint)

            val topSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    cardRect.left,
                    cardRect.top,
                    cardRect.left,
                    cardRect.top + 320f,
                    0x26FFFFFF,
                    0x00FFFFFF,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, cardRadius, cardRadius, topSheenPaint)

            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.6f
                shader = LinearGradient(
                    cardRect.left,
                    cardRect.top,
                    cardRect.right,
                    cardRect.bottom,
                    intArrayOf(0xA6FFFFFF.toInt(), 0x1AFFFFFF, 0x59FFFFFF),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(cardRect, cardRadius, cardRadius, rimPaint)

            // 3. Rounded album artwork (20px) at top + quote header
            val thumbSize = 152f
            val thumbRect = RectF(128f, 168f, 128f + thumbSize, 168f + thumbSize)
            val clipPath = Path().apply {
                addRoundRect(thumbRect, COVER_CORNER_RADIUS_PX, COVER_CORNER_RADIUS_PX, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            if (coverBitmap != null && !coverBitmap.isRecycled) {
                canvas.drawBitmap(coverBitmap, null, thumbRect, Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                val fallbackDrawable = runCatching { context.getDrawable(R.drawable.ic_default_album_cover) }.getOrNull()
                if (fallbackDrawable != null) {
                    fallbackDrawable.setBounds(
                        thumbRect.left.toInt(),
                        thumbRect.top.toInt(),
                        thumbRect.right.toInt(),
                        thumbRect.bottom.toInt()
                    )
                    fallbackDrawable.draw(canvas)
                } else {
                    canvas.drawRect(thumbRect, tintFillPaint)
                }
            }
            canvas.restore()

            val coverRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0x44FFFFFF
            }
            canvas.drawRoundRect(thumbRect, COVER_CORNER_RADIUS_PX, COVER_CORNER_RADIUS_PX, coverRimPaint)

            val brandBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.accentGlow
                textSize = 26f
                letterSpacing = 0.14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            canvas.drawText("LAGUKU", 308f, 254f, brandBadgePaint)

            // 4. Selected 3-layer lyric lines (primary + optional romanization + translation)
            val quoteTextWidth = CARD_WIDTH_PX - 256
            val primaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 44f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val romanPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xCCFFFFFF.toInt()
                textSize = 28f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val transPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0xEB shl 24) or (palette.accentGlow and 0x00FFFFFF)
                textSize = 27f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }

            var cursorY = 380f
            val quoteBottomLimit = CARD_HEIGHT_PX - 260f
            for (line in selected) {
                val primary = line.primaryText.ifBlank { "\u266A" }
                val primaryLayout = StaticLayout.Builder.obtain(primary, 0, primary.length, primaryPaint, quoteTextWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(8f, 1.12f)
                    .setMaxLines(4)
                    .build()
                if (cursorY + primaryLayout.height > quoteBottomLimit) break
                canvas.save()
                canvas.translate(128f, cursorY)
                primaryLayout.draw(canvas)
                canvas.restore()
                cursorY += primaryLayout.height + 10f

                val rom = line.romanizedText?.takeIf { it.isNotBlank() }
                if (rom != null && cursorY + 42f < quoteBottomLimit) {
                    val romLayout = StaticLayout.Builder.obtain(rom, 0, rom.length, romanPaint, quoteTextWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(4f, 1.1f)
                        .setMaxLines(2)
                        .build()
                    if (cursorY + romLayout.height <= quoteBottomLimit) {
                        canvas.save()
                        canvas.translate(128f, cursorY)
                        romLayout.draw(canvas)
                        canvas.restore()
                        cursorY += romLayout.height + 6f
                    }
                }

                val tr = line.translatedText?.takeIf { it.isNotBlank() }
                if (tr != null && cursorY + 42f < quoteBottomLimit) {
                    val trLayout = StaticLayout.Builder.obtain(tr, 0, tr.length, transPaint, quoteTextWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(4f, 1.1f)
                        .setMaxLines(2)
                        .build()
                    if (cursorY + trLayout.height <= quoteBottomLimit) {
                        canvas.save()
                        canvas.translate(128f, cursorY)
                        trLayout.draw(canvas)
                        canvas.restore()
                        cursorY += trLayout.height + 6f
                    }
                }
                cursorY += 22f
            }

            // 5. Track title & artist footer inside glass card
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x26FFFFFF
                strokeWidth = 1.5f
            }
            canvas.drawLine(128f, CARD_HEIGHT_PX - 238f, CARD_WIDTH_PX - 128f, CARD_HEIGHT_PX - 238f, dividerPaint)

            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 34f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB8FFFFFF.toInt()
                textSize = 26f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            canvas.drawText(track.title.ifBlank { context.getString(R.string.unknown_title) }.take(38), 128f, CARD_HEIGHT_PX - 186f, titlePaint)
            canvas.drawText(track.artist.ifBlank { context.getString(R.string.unknown_artist) }.take(44), 128f, CARD_HEIGHT_PX - 144f, artistPaint)

            val outDir = File(context.cacheDir, SHARED_CARDS_DIR).apply { mkdirs() }
            val outFile = File(outDir, OUTPUT_FILE_NAME)
            outFile.outputStream().use { fos ->
                outBmp.compress(Bitmap.CompressFormat.PNG, 96, fos)
            }
            return outFile
        } catch (_: Exception) {
            return null
        } finally {
            coverThumb?.recycle()
            outBmp.recycle()
        }
    }

    fun buildShareIntent(context: Context, imageFile: File, trackTitle: String): Intent {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}$AUTHORITY_SUFFIX")
            .appendPath(imageFile.name)
            .build()
        return Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, trackTitle)
            clipData = ClipData.newRawUri("lyric_card", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun drawMeshBlob(
        canvas: Canvas,
        paint: Paint,
        cx: Float,
        cy: Float,
        radius: Float,
        rgb: Int,
        centerAlpha: Int
    ) {
        val c0 = ((centerAlpha.coerceIn(0, 255)) shl 24) or (rgb and 0x00FFFFFF)
        val c1 = (((centerAlpha * 0.45f).toInt().coerceIn(0, 255)) shl 24) or (rgb and 0x00FFFFFF)
        val c2 = rgb and 0x00FFFFFF
        paint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(c0, c1, c2),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }
}

/**
 * Lightweight pure-Kotlin read-only ContentProvider serving rendered quote cards from `cacheDir/shared_cards/`.
 */
class LyricShareCardProvider : ContentProvider() {

    object SecuritySpec {
        fun resolveSafeCardFile(cacheDir: File, rawFileName: String?): File? {
            val cleanName = rawFileName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (cleanName.contains('/') || cleanName.contains('\\') || cleanName.contains("..")) {
                return null
            }
            val rootCanonical = runCatching {
                File(cacheDir, LyricShareCardRenderer.SHARED_CARDS_DIR).canonicalFile
            }.getOrNull() ?: return null
            val targetCanonical = runCatching {
                File(rootCanonical, cleanName).canonicalFile
            }.getOrNull() ?: return null
            if (targetCanonical.parentFile != rootCanonical) return null
            return targetCanonical
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        val target = SecuritySpec.resolveSafeCardFile(ctx.cacheDir, uri.lastPathSegment)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        if (target != null && target.isFile) {
            val values = Array<Any?>(cols.size) { i ->
                when (cols[i]) {
                    OpenableColumns.DISPLAY_NAME -> target.name
                    OpenableColumns.SIZE -> target.length()
                    else -> null
                }
            }
            cursor.addRow(values)
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw SecurityException("Read-only provider")
        }
        val ctx = context ?: throw FileNotFoundException("No context")
        val target = SecuritySpec.resolveSafeCardFile(ctx.cacheDir, uri.lastPathSegment)
            ?: throw FileNotFoundException("Invalid share card path")
        if (!target.isFile || !target.canRead()) {
            throw FileNotFoundException("Card file unavailable")
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Read-only")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        throw UnsupportedOperationException("Read-only")
    }
}
