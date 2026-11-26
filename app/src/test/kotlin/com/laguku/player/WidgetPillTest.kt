package com.laguku.player

import com.laguku.player.widget.LagukuPillWidgetProvider
import com.laguku.player.widget.LagukuPillWidgetProvider.PinWidgetStatus
import com.laguku.player.widget.LagukuPillWidgetProvider.WidgetLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
