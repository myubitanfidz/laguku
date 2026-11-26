package com.laguku.player

import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.ui.motion.SpringAnimatorHelper.ScrollEdgeMath
import com.laguku.player.ui.motion.SpringAnimatorHelper.SwipeDismissMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LiquidGlassComponentsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun scrollEdgeDissolution_tracksNormalizedScrollOffset() {
        assertEquals(0f, ScrollEdgeMath.computeDissolutionProgress(0, 72f), 0.0001f)
        assertEquals(0f, ScrollEdgeMath.computeDissolutionProgress(-10, 72f), 0.0001f)
        assertEquals(0.5f, ScrollEdgeMath.computeDissolutionProgress(36, 72f), 0.0001f)
        assertEquals(1f, ScrollEdgeMath.computeDissolutionProgress(72, 72f), 0.0001f)
        assertEquals(1f, ScrollEdgeMath.computeDissolutionProgress(400, 72f), 0.0001f)
    }

    @Test
    fun swipeDismissMath_triggersOnThresholdOrFlingVelocity() {
        val threshold = 160f
        assertFalse(SwipeDismissMath.shouldDismiss(dragDyPx = 40f, velocityYPxPerSec = 200f, thresholdPx = threshold))
        assertFalse(SwipeDismissMath.shouldDismiss(dragDyPx = 20f, velocityYPxPerSec = 1500f, thresholdPx = threshold))
        assertTrue(SwipeDismissMath.shouldDismiss(dragDyPx = 160f, velocityYPxPerSec = 0f, thresholdPx = threshold))
        assertTrue(SwipeDismissMath.shouldDismiss(dragDyPx = 70f, velocityYPxPerSec = 1100f, thresholdPx = threshold))
    }

    @Test
    fun playbackStateStore_persistsAndClampsContinuousGlassTransparency() {
        val file = File(tempFolder.root, "state.json")
        val store = PlaybackStateStore(file)
        assertEquals(PlaybackStateStore.DEFAULT_GLASS_TRANSPARENCY, store.getGlassTransparencyLevel(), 0.0001f)

        store.setGlassTransparencyLevel(0.15f)
        assertEquals(0.15f, PlaybackStateStore(file).getGlassTransparencyLevel(), 0.0001f)

        store.setGlassTransparencyLevel(1.8f)
        assertEquals(1.0f, PlaybackStateStore(file).getGlassTransparencyLevel(), 0.0001f)

        store.setGlassTransparencyLevel(-0.5f)
        assertEquals(0.0f, PlaybackStateStore(file).getGlassTransparencyLevel(), 0.0001f)
    }
}
