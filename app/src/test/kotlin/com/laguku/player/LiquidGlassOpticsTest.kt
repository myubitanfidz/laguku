package com.laguku.player

import com.laguku.player.ui.glass.LiquidGlassDrawable.BezelOpticsMath
import com.laguku.player.ui.glass.LiquidGlassDrawable.GlassSurfaceScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassOpticsTest {

    @Test
    fun sdfAndBezelBand_keepCenterSharpAndClampWidth() {
        assertFalse(BezelOpticsMath.CENTER_WARP_ENABLED)

        // Inside center has negative SDF; boundary is 0; outside is positive
        val dCenter = BezelOpticsMath.computeRoundedRectSdf(0f, 0f, halfW = 100f, halfH = 30f, radius = 24f)
        val dEdge = BezelOpticsMath.computeRoundedRectSdf(100f, 0f, halfW = 100f, halfH = 30f, radius = 24f)
        val dOutside = BezelOpticsMath.computeRoundedRectSdf(112f, 0f, halfW = 100f, halfH = 30f, radius = 24f)
        assertTrue(dCenter < 0f)
        assertEquals(0f, dEdge, 0.001f)
        assertTrue(dOutside > 0f)

        // Bezel width = 0.5 * radius, clamped 8dp..20dp and <= 1/3 shortest side
        val density = 2f
        val normalBezel = BezelOpticsMath.computeBezelWidthPx(widthPx = 400f, heightPx = 120f, radiusPx = 60f, density = density)
        assertEquals(30f, normalBezel, 0.001f) // 0.5*60 = 30px (15dp in 8..20dp), <= 120*0.33=39.6

        val cappedBezel = BezelOpticsMath.computeBezelWidthPx(widthPx = 400f, heightPx = 60f, radiusPx = 60f, density = density)
        assertEquals(60f * 0.33f, cappedBezel, 0.001f)

        // Displacement strictly zero at/past bezelWidth and modulated by materialization
        assertEquals(0f, BezelOpticsMath.computeBezelDisplacement(insidePx = 30f, bezelWidthPx = 30f), 0.0001f)
        assertEquals(0f, BezelOpticsMath.computeBezelDisplacement(insidePx = 45f, bezelWidthPx = 30f), 0.0001f)
        val rimShiftFull = BezelOpticsMath.computeBezelDisplacement(insidePx = 0.1f, bezelWidthPx = 30f, materialization = 1f)
        val rimShiftHalf = BezelOpticsMath.computeBezelDisplacement(insidePx = 0.1f, bezelWidthPx = 30f, materialization = 0.5f)
        assertTrue(rimShiftFull > 17f)
        assertEquals(rimShiftFull * 0.5f, rimShiftHalf, 0.01f)
    }

    @Test
    fun chromaticDispersion_staggersRgbWithinTwoToSixPercent() {
        val (r, g, b) = BezelOpticsMath.computeChromaticOffsets(baseShift = 10f, dispersion = 0.04f)
        assertEquals(10.4f, r, 0.001f)
        assertEquals(10.0f, g, 0.001f)
        assertEquals(9.6f, b, 0.001f)

        val (rClamp, _, bClamp) = BezelOpticsMath.computeChromaticOffsets(baseShift = 10f, dispersion = 0.25f)
        assertEquals(10.6f, rClamp, 0.001f)
        assertEquals(9.4f, bClamp, 0.001f)
    }

    @Test
    fun smallVsLargePolarityAndRimAlphas_followOpticalTokens() {
        // Small bars flip to light polarity over bright backdrops; large sheets stay stable
        assertTrue(BezelOpticsMath.resolvePolarityIsLight(GlassSurfaceScale.SMALL, backdropLuminance = 0.80f, defaultLightMode = false))
        assertFalse(BezelOpticsMath.resolvePolarityIsLight(GlassSurfaceScale.SMALL, backdropLuminance = 0.20f, defaultLightMode = true))
        assertFalse(BezelOpticsMath.resolvePolarityIsLight(GlassSurfaceScale.LARGE, backdropLuminance = 0.90f, defaultLightMode = false))
        assertTrue(BezelOpticsMath.resolvePolarityIsLight(GlassSurfaceScale.LARGE, backdropLuminance = 0.10f, defaultLightMode = true))

        val (lightUl, lightMid, lightLr) = BezelOpticsMath.computeRimLightAlphas(isLightPolarity = true, materialization = 1f)
        assertEquals(0.90f, lightUl, 0.0001f)
        assertEquals(0.16f, lightMid, 0.0001f)
        assertEquals(0.55f, lightLr, 0.0001f)

        val (darkUl, darkMid, darkLr) = BezelOpticsMath.computeRimLightAlphas(isLightPolarity = false, materialization = 1f)
        assertEquals(0.32f, darkUl, 0.0001f)
        assertEquals(0.08f, darkMid, 0.0001f)
        assertEquals(0.22f, darkLr, 0.0001f)

        val (zeroUl, zeroMid, zeroLr) = BezelOpticsMath.computeRimLightAlphas(isLightPolarity = true, materialization = 0f)
        assertEquals(0f, zeroUl, 0.0001f)
        assertEquals(0f, zeroMid, 0.0001f)
        assertEquals(0f, zeroLr, 0.0001f)
    }

    @Test
    fun adaptiveShadow_scalesBetweenPlainAndTextAndBleedsAmbientColor() {
        assertEquals(0.08f, BezelOpticsMath.computeAdaptiveShadowAlpha(0f), 0.0001f)
        assertEquals(0.22f, BezelOpticsMath.computeAdaptiveShadowAlpha(1f), 0.0001f)
        assertEquals(0.15f, BezelOpticsMath.computeAdaptiveShadowAlpha(0.5f), 0.0001f)

        val shadowColor = BezelOpticsMath.computeAmbientShadowColor(
            accentColor = 0xFFFF6D00.toInt(),
            shadowAlpha = 0.20f,
            bleedFraction = 0.20f
        )
        val alpha = (shadowColor ushr 24) and 0xFF
        val red = (shadowColor ushr 16) and 0xFF
        val blue = shadowColor and 0xFF
        assertTrue(alpha in 50..52)
        assertTrue("Expected warm ambient red bleed > blue, got r=$red b=$blue", red > blue)
    }
}
