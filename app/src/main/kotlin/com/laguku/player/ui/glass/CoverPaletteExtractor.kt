package com.laguku.player.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import com.laguku.player.R
import com.laguku.player.model.CoverPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Histogram-based color extractor producing [CoverPalette] from cover artwork pixels
 * without Android framework dependencies in its core pixel path.
 */
object CoverPaletteExtractor {

    private const val DEFAULT_DEEP_BACKDROP = 0xFF080A0F.toInt()
    private const val DEFAULT_ACCENT_GLOW = 0xFF6BE2C4.toInt()
    private const val DEFAULT_GLASS_TINT = 0xFF24344D.toInt()

    private const val HIST_SIZE = 4096 // 12-bit quantized RGB (4 + 4 + 4 bits)
    private const val SAMPLE_SIZE = 64

    fun defaultPalette(): CoverPalette {
        return CoverPalette(
            deepBackdrop = DEFAULT_DEEP_BACKDROP,
            accentGlow = DEFAULT_ACCENT_GLOW,
            glassTint = DEFAULT_GLASS_TINT
        )
    }

    fun renderDefaultCoverBitmap(context: Context, sizePx: Int = 256): Bitmap? {
        val drawable: Drawable = context.getDrawable(R.drawable.ic_default_album_cover) ?: return null
        val dim = sizePx.coerceIn(64, 512)
        val bitmap = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, dim, dim)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Extracts distinct [CoverPalette.deepBackdrop], [CoverPalette.accentGlow], and
     * [CoverPalette.glassTint] colors from an ARGB [pixels] buffer. Pure JVM testable.
     */
    fun extractFromPixels(pixels: IntArray): CoverPalette {
        if (pixels.isEmpty()) return defaultPalette()

        val counts = IntArray(HIST_SIZE)
        val sumR = LongArray(HIST_SIZE)
        val sumG = LongArray(HIST_SIZE)
        val sumB = LongArray(HIST_SIZE)

        var validCount = 0
        for (px in pixels) {
            val alpha = (px ushr 24) and 0xFF
            if (alpha < 128) continue

            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF

            val bin = ((r ushr 4) shl 8) or ((g ushr 4) shl 4) or (b ushr 4)
            counts[bin]++
            sumR[bin] += r.toLong()
            sumG[bin] += g.toLong()
            sumB[bin] += b.toLong()
            validCount++
        }

        if (validCount == 0) return defaultPalette()

        var bestDominantScore = -1f
        var dominantColor = 0

        var bestAccentScore = -1f
        var accentCandidate = 0
        var hasChromaticAccent = false

        val hsv = FloatArray(3)
        for (bin in 0 until HIST_SIZE) {
            val count = counts[bin]
            if (count == 0) continue

            val avgR = (sumR[bin] / count).toInt().coerceIn(0, 255)
            val avgG = (sumG[bin] / count).toInt().coerceIn(0, 255)
            val avgB = (sumB[bin] / count).toInt().coerceIn(0, 255)
            val color = (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB

            rgbToHsv(avgR, avgG, avgB, hsv)
            val sat = hsv[1]
            val value = hsv[2]

            // Dominant tone favors frequent mid-to-rich colors over pure near-black/white
            val domLumWeight = if (value < 0.08f || value > 0.94f) 0.25f else 1.0f
            val domScore = count.toFloat() * (0.5f + sat) * domLumWeight
            if (domScore > bestDominantScore) {
                bestDominantScore = domScore
                dominantColor = color
            }

            // Accent glow favors saturated, vibrant hues that remain legible on dark glass
            if (sat >= 0.18f && value >= 0.16f) {
                hasChromaticAccent = true
                val lumBalance = 1.0f - kotlin.math.abs(value - 0.68f) * 0.65f
                val accentScore = count.toFloat() * (0.25f + sat * sat * 2.5f) * lumBalance.coerceAtLeast(0.2f)
                if (accentScore > bestAccentScore) {
                    bestAccentScore = accentScore
                    accentCandidate = color
                }
            }
        }

        if (!hasChromaticAccent) {
            return defaultPalette()
        }

        val accentHsv = FloatArray(3)
        rgbToHsv(
            (accentCandidate ushr 16) and 0xFF,
            (accentCandidate ushr 8) and 0xFF,
            accentCandidate and 0xFF,
            accentHsv
        )
        // Boost accent brightness and clamp saturation so controls glow crisply on dark glass
        val accentGlow = hsvToRgb(
            hue = accentHsv[0],
            sat = accentHsv[1].coerceIn(0.45f, 0.90f),
            value = accentHsv[2].coerceIn(0.68f, 0.95f)
        )

        val domHsv = FloatArray(3)
        rgbToHsv(
            (dominantColor ushr 16) and 0xFF,
            (dominantColor ushr 8) and 0xFF,
            dominantColor and 0xFF,
            domHsv
        )
        val baseHue = if (domHsv[1] >= 0.12f) domHsv[0] else accentHsv[0]
        val baseSat = if (domHsv[1] >= 0.12f) domHsv[1] else accentHsv[1]

        val deepBackdrop = hsvToRgb(
            hue = baseHue,
            sat = baseSat.coerceIn(0.22f, 0.58f),
            value = 0.11f
        )

        val glassTint = hsvToRgb(
            hue = accentHsv[0],
            sat = accentHsv[1].coerceIn(0.26f, 0.62f),
            value = 0.30f
        )

        return CoverPalette(
            deepBackdrop = deepBackdrop,
            accentGlow = accentGlow,
            glassTint = glassTint
        )
    }

    /**
     * Extracts [CoverPalette] from a [Bitmap] without modifying or recycling it.
     */
    fun extract(bitmap: Bitmap): CoverPalette {
        val sample = if (bitmap.width <= SAMPLE_SIZE && bitmap.height <= SAMPLE_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        }
        val w = sample.width
        val h = sample.height
        val pixels = IntArray(w * h)
        sample.getPixels(pixels, 0, w, 0, 0, w, h)
        if (sample !== bitmap) {
            sample.recycle()
        }
        return extractFromPixels(pixels)
    }

    private fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxVal = max(rf, max(gf, bf))
        val minVal = min(rf, min(gf, bf))
        val delta = maxVal - minVal

        val hue = when {
            delta <= 1e-5f -> 0f
            maxVal == rf -> 60f * (((gf - bf) / delta) % 6f)
            maxVal == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val sat = if (maxVal <= 1e-5f) 0f else delta / maxVal
        out[0] = hue
        out[1] = sat
        out[2] = maxVal
    }

    private fun hsvToRgb(hue: Float, sat: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val c = value * sat
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = value - c

        val (rf, gf, bf) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        val r = ((rf + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((gf + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((bf + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
