package com.laguku.player

import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.StackBlur
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassAndPaletteTest {

    @Test
    fun stackBlur_preservesDimensionsAndSoftensSharpEdge() {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (x < width / 2) {
                    0xFF000000.toInt()
                } else {
                    0xFFFFFFFF.toInt()
                }
            }
        }

        val blurred = StackBlur.blurPixels(pixels, width, height, radius = 12)
        assertEquals(pixels.size, blurred.size)

        // Boundary pixels around x = 15..16 should transition smoothly into intermediate grays
        val leftBoundary = blurred[(height / 2) * width + (width / 2 - 1)]
        val rightBoundary = blurred[(height / 2) * width + (width / 2)]
        val leftR = (leftBoundary ushr 16) and 0xFF
        val rightR = (rightBoundary ushr 16) and 0xFF

        assertTrue("Expected left edge pixel to brighten after blur, got $leftR", leftR in 20..220)
        assertTrue("Expected right edge pixel to darken after blur, got $rightR", rightR in 35..235)
        assertTrue("Expected blurred pixels to preserve full alpha", ((leftBoundary ushr 24) and 0xFF) == 0xFF)
    }

    @Test
    fun stackBlur_handlesRadiusZeroEmptyAndSmallArraysSafely() {
        val sample = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt(), 0xFFAABBCC.toInt())
        val zeroRadius = StackBlur.blurPixels(sample, 2, 2, radius = 0)
        assertArrayEquals(sample, zeroRadius)

        val emptyOut = StackBlur.blurPixels(IntArray(0), 0, 0, radius = 12)
        assertEquals(0, emptyOut.size)

        val singlePixel = intArrayOf(0xFF336699.toInt())
        val blurredSingle = StackBlur.blurPixels(singlePixel, 1, 1, radius = 12)
        assertEquals(1, blurredSingle.size)
        assertEquals(singlePixel[0], blurredSingle[0])

        val invalidDims = StackBlur.blurPixels(sample, 10, 10, radius = 5)
        assertArrayEquals(sample, invalidDims)
    }

    @Test
    fun coverPalette_extractsDistinctColorsFromSyntheticBuffers() {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            pixels[i] = if (i % 3 == 0) {
                0xFF102840.toInt() // Deep navy
            } else {
                0xFFFF6D00.toInt() // Vibrant orange
            }
        }

        val palette = CoverPaletteExtractor.extractFromPixels(pixels)
        assertEquals(0xFF, (palette.deepBackdrop ushr 24) and 0xFF)
        assertEquals(0xFF, (palette.accentGlow ushr 24) and 0xFF)
        assertEquals(0xFF, (palette.glassTint ushr 24) and 0xFF)

        assertNotEquals(palette.deepBackdrop, palette.accentGlow)
        assertNotEquals(palette.deepBackdrop, palette.glassTint)
        assertNotEquals(palette.accentGlow, palette.glassTint)

        // Accent glow should remain noticeably brighter than deepBackdrop
        val accentR = (palette.accentGlow ushr 16) and 0xFF
        val deepR = (palette.deepBackdrop ushr 16) and 0xFF
        assertTrue("Expected accentGlow ($accentR) brighter than deepBackdrop ($deepR)", accentR > deepR)
    }

    @Test
    fun coverPalette_fallsBackGracefullyOnEmptyAndUniformAchromaticPixels() {
        val defaultPal = CoverPaletteExtractor.defaultPalette()

        assertEquals(defaultPal, CoverPaletteExtractor.extractFromPixels(IntArray(0)))

        val allBlack = IntArray(64) { 0xFF000000.toInt() }
        assertEquals(defaultPal, CoverPaletteExtractor.extractFromPixels(allBlack))

        val allWhite = IntArray(64) { 0xFFFFFFFF.toInt() }
        assertEquals(defaultPal, CoverPaletteExtractor.extractFromPixels(allWhite))

        val allTransparent = IntArray(64) { 0x00FF0000 }
        assertEquals(defaultPal, CoverPaletteExtractor.extractFromPixels(allTransparent))
    }
}
