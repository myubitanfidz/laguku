package com.laguku.player

import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.glass.LiquidGlassDrawable.AccessibilityFlags
import com.laguku.player.ui.glass.LiquidGlassDrawable.LayerTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassCoreTest {

    @Test
    fun contentLayerTokens_matchLightAndDarkElevatedPaletteAndHairlineAlpha() {
        assertEquals(0xFFF2F2F7.toInt(), LayerTokens.pageGroundColor(isDarkMode = false))
        assertEquals(0xFFFFFFFF.toInt(), LayerTokens.contentCardColor(isDarkMode = false))
        assertEquals(0xFF131316.toInt(), LayerTokens.pageGroundColor(isDarkMode = true))
        assertEquals(0xFF1D1D20.toInt(), LayerTokens.contentCardColor(isDarkMode = true))
        assertEquals(0xFF26262A.toInt(), LayerTokens.nestedCardColor(isDarkMode = true))

        // High contrast mode forces stark black/white surfaces
        assertEquals(0xFFFFFFFF.toInt(), LayerTokens.contentCardColor(isDarkMode = false, highContrast = true))
        assertEquals(0xFF000000.toInt(), LayerTokens.contentCardColor(isDarkMode = true, highContrast = true))

        val lightHairAlpha = (LayerTokens.hairlineColor(isDarkMode = false) ushr 24) and 0xFF
        val darkHairAlpha = (LayerTokens.hairlineColor(isDarkMode = true) ushr 24) and 0xFF
        assertTrue("Expected ~12% alpha on light hairline, got $lightHairAlpha", lightHairAlpha in 28..35)
        assertTrue("Expected ~12% alpha on dark hairline, got $darkHairAlpha", darkHairAlpha in 28..35)
    }

    @Test
    fun continuousTransparency_respectsLegibilityFloorAndAccessibilityFallbacks() {
        val base = 0.56f
        val crystal = LayerTokens.resolveContinuousTransparencyAlpha(base, 0.0f)
        val mid = LayerTokens.resolveContinuousTransparencyAlpha(base, 0.5f)
        val dense = LayerTokens.resolveContinuousTransparencyAlpha(base, 1.0f)

        assertEquals(LayerTokens.MIN_CONTINUOUS_ALPHA, crystal, 0.0001f)
        assertEquals(base, mid, 0.0001f)
        assertEquals(LayerTokens.MAX_CONTINUOUS_ALPHA, dense, 0.0001f)
        assertTrue(crystal < mid && mid < dense)

        // Reduce Transparency -> ~92% opaque fill and disables refraction
        val reduced = AccessibilityFlags(reduceTransparency = true)
        assertEquals(0.92f, LayerTokens.resolveContinuousTransparencyAlpha(base, 0.0f, reduced), 0.0001f)
        assertFalse(LayerTokens.isRefractionAllowed(reduced))
        assertTrue(LayerTokens.isGelMotionAllowed(reduced))

        // Increase Contrast -> 100% opaque stark surface and disables refraction
        val contrast = AccessibilityFlags(increaseContrast = true)
        assertEquals(1.0f, LayerTokens.resolveContinuousTransparencyAlpha(base, 0.0f, contrast), 0.0001f)
        assertFalse(LayerTokens.isRefractionAllowed(contrast))

        // Reduce Motion -> disables gel deformation and bounce
        val motion = AccessibilityFlags(reduceMotion = true)
        assertFalse(LayerTokens.isGelMotionAllowed(motion))
        assertTrue(LayerTokens.isRefractionAllowed(motion))
    }

    @Test
    fun clearVariantGate_requiresAllThreeConditions() {
        assertTrue(LayerTokens.canUseClearVariant(overRichMedia = true, dimmingVeilSafe = true, boldBrightForeground = true))
        assertFalse(LayerTokens.canUseClearVariant(overRichMedia = false, dimmingVeilSafe = true, boldBrightForeground = true))
        assertFalse(LayerTokens.canUseClearVariant(overRichMedia = true, dimmingVeilSafe = false, boldBrightForeground = true))
        assertFalse(LayerTokens.canUseClearVariant(overRichMedia = true, dimmingVeilSafe = true, boldBrightForeground = false))
    }
}
