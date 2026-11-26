package com.laguku.player

import com.laguku.player.widget.LagukuPillWidgetProvider
import com.laguku.player.widget.LagukuPillWidgetProvider.BackdropBitmapCache
import com.laguku.player.widget.LagukuPillWidgetProvider.BackdropCacheKey
import com.laguku.player.widget.LagukuPillWidgetProvider.PinWidgetStatus
import com.laguku.player.widget.LagukuPillWidgetProvider.WidgetLayoutMode
import com.laguku.player.widget.LagukuPillWidgetProvider.WidgetOpticsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetPillTest {

    private fun resolveProjectFile(relativePath: String): File {
        val direct = File(relativePath)
        if (direct.isFile) return direct
        val fromApp = File("app", relativePath)
        if (fromApp.isFile) return fromApp
        val withoutAppPrefix = relativePath.removePrefix("app/")
        val stripped = File(withoutAppPrefix)
        if (stripped.isFile) return stripped
        return direct
    }

    @Test
    fun resolveLayoutMode_compactWidthsBelow220AndHeightBelow110() {
        assertEquals(
            WidgetLayoutMode.COMPACT,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 180, minHeightDp = 56)
        )
        assertEquals(
            WidgetLayoutMode.COMPACT,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 219, minHeightDp = 64)
        )
        assertEquals(
            WidgetLayoutMode.COMPACT,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 219, minHeightDp = 109)
        )
    }

    @Test
    fun resolveLayoutMode_standardWidthsAtLeast220AndHeightBelow110() {
        assertEquals(
            WidgetLayoutMode.STANDARD,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 220, minHeightDp = 64)
        )
        assertEquals(
            WidgetLayoutMode.STANDARD,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 260, minHeightDp = 109)
        )
    }

    @Test
    fun resolveLayoutMode_expandedHeightsAtLeast110RegardlessOfWidth() {
        assertEquals(
            WidgetLayoutMode.EXPANDED,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 180, minHeightDp = 110)
        )
        assertEquals(
            WidgetLayoutMode.EXPANDED,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 300, minHeightDp = 140)
        )
    }

    @Test
    fun resolveLayoutMode_zeroAndNegativeEdgeCases() {
        assertEquals(
            WidgetLayoutMode.COMPACT,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 0, minHeightDp = 0)
        )
        assertEquals(
            WidgetLayoutMode.COMPACT,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = -10, minHeightDp = -10)
        )
        assertEquals(
            WidgetLayoutMode.STANDARD,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 220, minHeightDp = 0)
        )
        assertEquals(
            WidgetLayoutMode.EXPANDED,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = 0, minHeightDp = 110)
        )
        assertEquals(
            WidgetLayoutMode.EXPANDED,
            LagukuPillWidgetProvider.resolveLayoutMode(minWidthDp = -50, minHeightDp = 110)
        )
    }

    @Test
    fun widgetManifestAndMetadata_includeMiui13CompatibilityAndKeepRules() {
        val manifest = resolveProjectFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".widget.LagukuPillWidgetProvider\""))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("android:label=\"@string/widget_name\""))
        assertTrue(manifest.contains("android.appwidget.action.APPWIDGET_UPDATE"))
        assertTrue(manifest.contains("android.appwidget.provider"))
        assertTrue(manifest.contains("@xml/laguku_pill_widget_info"))

        val keepXml = resolveProjectFile("app/src/main/res/raw/keep.xml").readText()
        assertTrue(keepXml.contains("@xml/laguku_pill_widget_info"))
        assertTrue(keepXml.contains("@layout/widget_laguku_pill"))
        assertTrue(keepXml.contains("@drawable/ic_default_album_cover"))

        val info = resolveProjectFile("app/src/main/res/xml/laguku_pill_widget_info.xml").readText()
        assertTrue(info.contains("android:minWidth=\"250dp\""))
        assertTrue(info.contains("android:minHeight=\"56dp\""))
        assertTrue(info.contains("android:targetCellWidth=\"4\""))
        assertTrue(info.contains("android:targetCellHeight=\"1\""))
        assertTrue(info.contains("android:resizeMode=\"horizontal\""))
        assertTrue(info.contains("android:widgetCategory=\"home_screen\""))
        assertTrue(info.contains("android:updatePeriodMillis=\"0\""))
        assertTrue(info.contains("android:initialLayout=\"@layout/widget_laguku_pill\""))
        assertTrue(info.contains("android:previewImage=\"@drawable/ic_default_album_cover\""))
        assertFalse("MIUI 13 parser must not reject previewLayout", info.contains("android:previewLayout"))

        val layout = resolveProjectFile("app/src/main/res/layout/widget_laguku_pill.xml").readText()
        assertFalse("RemoteViews layout must not resolve launcher theme attributes", layout.contains("?android:attr/"))
        assertFalse("RemoteViews root must not rely on clipToOutline attribute", layout.contains("android:clipToOutline"))
    }

    @Test
    fun pinWidgetFallback_returnsManualGuideWhenLauncherDoesNotSupportAutoPin() {
        val unsupported = LagukuPillWidgetProvider.resolvePinRequestStatus(
            isPinSupported = false,
            requestAccepted = false
        )
        assertEquals(PinWidgetStatus.UNSUPPORTED_USE_MANUAL_GUIDE, unsupported)
        assertEquals(R.string.pin_widget_unsupported, LagukuPillWidgetProvider.resolvePinFeedbackRes(unsupported))

        val rejected = LagukuPillWidgetProvider.resolvePinRequestStatus(
            isPinSupported = true,
            requestAccepted = false
        )
        assertEquals(PinWidgetStatus.UNSUPPORTED_USE_MANUAL_GUIDE, rejected)
        assertEquals(R.string.pin_widget_unsupported, LagukuPillWidgetProvider.resolvePinFeedbackRes(rejected))

        val requested = LagukuPillWidgetProvider.resolvePinRequestStatus(
            isPinSupported = true,
            requestAccepted = true
        )
        assertEquals(PinWidgetStatus.PIN_REQUEST_SENT, requested)
        assertEquals(R.string.pin_widget_requested, LagukuPillWidgetProvider.resolvePinFeedbackRes(requested))

        val strings = resolveProjectFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("name=\"widget_name\">Laguku<"))
        assertTrue(strings.contains("ketuk Semua"))
        assertTrue(strings.contains("Widget Android"))
        assertTrue(strings.contains("pilih Laguku"))
    }

    @Test
    fun widgetOpticsMath_computesConcentricRadiusBinderSafeBoundsAndHighContrastAmbient() {
        // Binder-safe offscreen bitmap dimensions (<= 720x180 and < 1 MB ARGB_8888)
        val (stdW, stdH) = WidgetOpticsMath.computeBackdropSizePx(widthDp = 320, heightDp = 64, density = 3.0f)
        assertTrue("Width must stay <= 720px, got $stdW", stdW <= 720)
        assertTrue("Height must stay <= 180px, got $stdH", stdH <= 180)
        assertTrue("Bitmap ARGB byte size must stay far below 1 MB Binder cap", stdW * stdH * 4 < 550_000)

        val (hugeW, hugeH) = WidgetOpticsMath.computeBackdropSizePx(widthDp = 500, heightDp = 140, density = 3.0f)
        assertTrue(hugeW <= 720)
        assertTrue(hugeH <= 180)

        // Concentric artwork corner radius = outerRadius - padding clamped in 12..16dp
        assertEquals(16f, WidgetOpticsMath.computeConcentricArtworkRadiusDp(capsuleHeightDp = 52f, innerPaddingDp = 8f), 0.001f)
        assertEquals(14f, WidgetOpticsMath.computeConcentricArtworkRadiusDp(capsuleHeightDp = 44f, innerPaddingDp = 8f), 0.001f)

        // Ambient color bleed preserves >= 7.0:1 contrast with white text
        val (topColor, bottomColor) = WidgetOpticsMath.computeAmbientTintColors(
            dominantColor = 0xFFFF6D00.toInt(),
            accentGlow = 0xFFFFAB40.toInt(),
            isLightBackdrop = false
        )
        val topAlpha = ((topColor ushr 24) and 0xFF) / 255f
        assertEquals(0.58f, topAlpha, 0.01f)
        val opaqueTop = (0xFF shl 24) or (topColor and 0x00FFFFFF)
        val opaqueBottom = (0xFF shl 24) or (bottomColor and 0x00FFFFFF)
        assertTrue(WidgetOpticsMath.computeContrastRatio(0xFFFFFFFF.toInt(), opaqueTop) >= 7.0)
        assertTrue(WidgetOpticsMath.computeContrastRatio(0xFFFFFFFF.toInt(), opaqueBottom) >= 7.0)

        // 135-degree directional rim alphas: 0.65 -> 0.10 -> 0.35
        val (ul, mid, lr) = WidgetOpticsMath.computeDirectionalRimAlphas()
        assertEquals(0.65f, ul, 0.0001f)
        assertEquals(0.10f, mid, 0.0001f)
        assertEquals(0.35f, lr, 0.0001f)
    }

    @Test
    fun widgetBackdropCache_hitsOnMatchingKeyAndEvictsSafelyWhenBounded() {
        val cache = BackdropBitmapCache<String>(maxEntries = 2)
        val k1 = BackdropCacheKey(widthPx = 600, heightPx = 120, dominantColor = 0xFF112233.toInt(), isPlaying = true)
        val k2 = BackdropCacheKey(widthPx = 600, heightPx = 120, dominantColor = 0xFF112233.toInt(), isPlaying = false)
        val k3 = BackdropCacheKey(widthPx = 600, heightPx = 120, dominantColor = 0xFF445566.toInt(), isPlaying = true)

        assertNull(cache.put(k1, "bmp1"))
        assertEquals("bmp1", cache.get(k1))
        assertNull(cache.put(k2, "bmp2"))
        // Access k1 so k2 becomes least-recently-used
        assertEquals("bmp1", cache.get(k1))

        val evicted = cache.put(k3, "bmp3")
        assertEquals("bmp2", evicted)
        assertEquals(2, cache.size())
        assertEquals("bmp1", cache.get(k1))
        assertEquals("bmp3", cache.get(k3))
    }
}
