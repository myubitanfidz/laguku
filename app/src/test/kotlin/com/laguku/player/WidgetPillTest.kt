package com.laguku.player

import com.laguku.player.widget.LagukuPillWidgetProvider
import com.laguku.player.widget.LagukuPillWidgetProvider.WidgetLayoutMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetPillTest {

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
}
