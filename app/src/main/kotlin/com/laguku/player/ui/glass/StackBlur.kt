package com.laguku.player.ui.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs

/**
 * Fast 2-pass StackBlur for 64x64 adaptive backdrop generation and memory-safe micro cover decoding.
 */
object StackBlur {

    private const val DEFAULT_RADIUS = 12
    private const val MAX_RADIUS = 64
    private const val BACKDROP_SIZE = 64

    // #7A080A0F scrim overlay constants (alpha = 0x7A = 122)
    private const val SCRIM_ALPHA = 0x7A
    private const val SCRIM_INV_ALPHA = 255 - SCRIM_ALPHA
    private const val SCRIM_R = 0x08
    private const val SCRIM_G = 0x0A
    private const val SCRIM_B = 0x0F

    /**
     * Pure JVM 2-pass StackBlur over an ARGB [pixels] buffer of [width] x [height].
     * Returns a newly blurred IntArray without mutating [pixels].
     */
    fun blurPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius: Int = DEFAULT_RADIUS
    ): IntArray {
        if (width <= 0 || height <= 0 || pixels.isEmpty() || pixels.size < width * height) {
            return pixels.copyOf()
        }
        val r = radius.coerceAtMost(MAX_RADIUS)
        if (r < 1) {
            return pixels.copyOf()
        }

        val pix = pixels.copyOf()
        val wm = width - 1
        val hm = height - 1
        val wh = width * height
        val div = r + r + 1

        val a = IntArray(wh)
        val red = IntArray(wh)
        val green = IntArray(wh)
        val blue = IntArray(wh)

        val vmin = IntArray(maxOf(width, height))
        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in dv.indices) {
            dv[i] = i / divsum
        }

        val stackA = IntArray(div)
        val stackR = IntArray(div)
        val stackG = IntArray(div)
        val stackB = IntArray(div)
        val r1 = r + 1

        var yi = 0
        var yw = 0

        // Horizontal pass
        for (y in 0 until height) {
            var asum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var aoutsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var ainsum = 0
            var rinsum = 0
            var ginsum = 0
            var binsum = 0

            for (i in -r..r) {
                val p = pix[yi + minOf(wm, maxOf(i, 0))]
                val sIdx = i + r
                val pa = (p ushr 24) and 0xFF
                val pr = (p ushr 16) and 0xFF
                val pg = (p ushr 8) and 0xFF
                val pb = p and 0xFF
                stackA[sIdx] = pa
                stackR[sIdx] = pr
                stackG[sIdx] = pg
                stackB[sIdx] = pb

                val rbs = r1 - abs(i)
                asum += pa * rbs
                rsum += pr * rbs
                gsum += pg * rbs
                bsum += pb * rbs
                if (i > 0) {
                    ainsum += pa
                    rinsum += pr
                    ginsum += pg
                    binsum += pb
                } else {
                    aoutsum += pa
                    routsum += pr
                    goutsum += pg
                    boutsum += pb
                }
            }

            var stackpointer = r
            for (x in 0 until width) {
                a[yi] = dv[asum]
                red[yi] = dv[rsum]
                green[yi] = dv[gsum]
                blue[yi] = dv[bsum]

                asum -= aoutsum
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = (stackpointer - r + div) % div
                aoutsum -= stackA[stackstart]
                routsum -= stackR[stackstart]
                goutsum -= stackG[stackstart]
                boutsum -= stackB[stackstart]

                if (y == 0) {
                    vmin[x] = minOf(x + r + 1, wm)
                }
                val p = pix[yw + vmin[x]]
                val pa = (p ushr 24) and 0xFF
                val pr = (p ushr 16) and 0xFF
                val pg = (p ushr 8) and 0xFF
                val pb = p and 0xFF
                stackA[stackstart] = pa
                stackR[stackstart] = pr
                stackG[stackstart] = pg
                stackB[stackstart] = pb

                ainsum += pa
                rinsum += pr
                ginsum += pg
                binsum += pb

                asum += ainsum
                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                aoutsum += stackA[stackpointer]
                routsum += stackR[stackpointer]
                goutsum += stackG[stackpointer]
                boutsum += stackB[stackpointer]

                ainsum -= stackA[stackpointer]
                rinsum -= stackR[stackpointer]
                ginsum -= stackG[stackpointer]
                binsum -= stackB[stackpointer]

                yi++
            }
            yw += width
        }

        // Vertical pass
        for (x in 0 until width) {
            var asum = 0
            var rsum = 0
            var gsum = 0
            var bsum = 0
            var aoutsum = 0
            var routsum = 0
            var goutsum = 0
            var boutsum = 0
            var ainsum = 0
            var rinsum = 0
            var ginsum = 0
            var binsum = 0

            var yp = -r * width
            for (i in -r..r) {
                val idx = maxOf(0, yp) + x
                val sIdx = i + r
                val pa = a[idx]
                val pr = red[idx]
                val pg = green[idx]
                val pb = blue[idx]
                stackA[sIdx] = pa
                stackR[sIdx] = pr
                stackG[sIdx] = pg
                stackB[sIdx] = pb

                val rbs = r1 - abs(i)
                asum += pa * rbs
                rsum += pr * rbs
                gsum += pg * rbs
                bsum += pb * rbs
                if (i > 0) {
                    ainsum += pa
                    rinsum += pr
                    ginsum += pg
                    binsum += pb
                } else {
                    aoutsum += pa
                    routsum += pr
                    goutsum += pg
                    boutsum += pb
                }

                if (i < hm) {
                    yp += width
                }
            }

            var yiCol = x
            var stackpointer = r
            for (y in 0 until height) {
                pix[yiCol] = (dv[asum] shl 24) or
                    (dv[rsum] shl 16) or
                    (dv[gsum] shl 8) or
                    dv[bsum]

                asum -= aoutsum
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                val stackstart = (stackpointer - r + div) % div
                aoutsum -= stackA[stackstart]
                routsum -= stackR[stackstart]
                goutsum -= stackG[stackstart]
                boutsum -= stackB[stackstart]

                if (x == 0) {
                    vmin[y] = minOf(y + r1, hm) * width
                }
                val p = x + vmin[y]
                val pa = a[p]
                val pr = red[p]
                val pg = green[p]
                val pb = blue[p]
                stackA[stackstart] = pa
                stackR[stackstart] = pr
                stackG[stackstart] = pg
                stackB[stackstart] = pb

                ainsum += pa
                rinsum += pr
                ginsum += pg
                binsum += pb

                asum += ainsum
                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                aoutsum += stackA[stackpointer]
                routsum += stackR[stackpointer]
                goutsum += stackG[stackpointer]
                boutsum += stackB[stackpointer]

                ainsum -= stackA[stackpointer]
                rinsum -= stackR[stackpointer]
                ginsum -= stackG[stackpointer]
                binsum -= stackB[stackpointer]

                yiCol += width
            }
        }

        return pix
    }

    /**
     * Scales [source] to 64x64, applies 2-pass StackBlur, and composites the #7A080A0F dark scrim
     * so white foreground typography always meets contrast requirements.
     */
    fun createBlurredBackdrop(source: Bitmap, radius: Int = DEFAULT_RADIUS): Bitmap {
        val scaled = if (source.width == BACKDROP_SIZE &&
            source.height == BACKDROP_SIZE &&
            source.config == Bitmap.Config.ARGB_8888
        ) {
            source
        } else {
            Bitmap.createScaledBitmap(source, BACKDROP_SIZE, BACKDROP_SIZE, true)
        }

        val pixels = IntArray(BACKDROP_SIZE * BACKDROP_SIZE)
        scaled.getPixels(pixels, 0, BACKDROP_SIZE, 0, 0, BACKDROP_SIZE, BACKDROP_SIZE)
        if (scaled !== source) {
            scaled.recycle()
        }

        val blurred = blurPixels(pixels, BACKDROP_SIZE, BACKDROP_SIZE, radius)
        for (i in blurred.indices) {
            val px = blurred[i]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF

            val outR = (SCRIM_R * SCRIM_ALPHA + r * SCRIM_INV_ALPHA) / 255
            val outG = (SCRIM_G * SCRIM_ALPHA + g * SCRIM_INV_ALPHA) / 255
            val outB = (SCRIM_B * SCRIM_ALPHA + b * SCRIM_INV_ALPHA) / 255
            blurred[i] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        return Bitmap.createBitmap(
            blurred,
            BACKDROP_SIZE,
            BACKDROP_SIZE,
            Bitmap.Config.ARGB_8888
        )
    }

    /**
     * Decodes [artworkBytes] with power-of-2 [BitmapFactory.Options.inSampleSize] targeting
     * roughly [targetSize] pixels to keep memory under ~120 KB even for 3000x3000 covers.
     */
    fun decodeMicroCover(artworkBytes: ByteArray?, targetSize: Int = BACKDROP_SIZE): Bitmap? {
        if (artworkBytes == null || artworkBytes.isEmpty() || targetSize <= 0) return null

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size, bounds)
        val rawWidth = bounds.outWidth
        val rawHeight = bounds.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        val halfW = rawWidth / 2
        val halfH = rawHeight / 2
        while ((halfW / sampleSize) >= targetSize && (halfH / sampleSize) >= targetSize) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(artworkBytes, 0, artworkBytes.size, decodeOpts)
    }
}
