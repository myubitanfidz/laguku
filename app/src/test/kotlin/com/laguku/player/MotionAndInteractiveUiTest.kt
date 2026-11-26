package com.laguku.player

import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.ui.glass.InteractiveGlassScrubView
import com.laguku.player.ui.motion.SpringAnimatorHelper
import com.laguku.player.ui.player.LyricsRecyclerAdapter
import com.laguku.player.ui.player.NowPlayingView
import com.laguku.player.ui.player.VinylDiscVisualizerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MotionAndInteractiveUiTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun springSolver_convergesAtCalibratedApplePresetAcross60Hz120HzAndDroppedFrames() {
        assertEquals("apple", SpringAnimatorHelper.MOTION_PRESET)
        assertEquals(420f, SpringAnimatorHelper.SPRING_STIFFNESS, 0.0001f)
        assertEquals(0.80f, SpringAnimatorHelper.SPRING_DAMPING_RATIO, 0.0001f)
        assertEquals(0.96f, SpringAnimatorHelper.SPRING_PRESS_SCALE, 0.0001f)

        val stepsHz = listOf(1f / 60f, 1f / 120f, 0.05f)
        for (dt in stepsHz) {
            val solver = SpringAnimatorHelper.SpringSolver(
                value = 0f,
                target = 1f,
                stiffness = SpringAnimatorHelper.SPRING_STIFFNESS,
                dampingRatio = SpringAnimatorHelper.SPRING_DAMPING_RATIO
            )
            var settled = false
            var ticks = 0
            while (!settled && ticks < 400) {
                settled = solver.step(dt)
                ticks++
            }
            assertTrue("Solver must settle for dt=$dt", settled)
            assertEquals(1f, solver.value, 0.0001f)
            assertEquals(0f, solver.velocity, 0.0001f)
        }
    }

    @Test
    fun ios26SpringProfiles_exposeCalibratedConstantsAndConvergeAt60And120Hz() {
        assertEquals(145f, SpringAnimatorHelper.SPRING_FLUID_SCROLL.stiffness, 0.0001f)
        assertEquals(0.94f, SpringAnimatorHelper.SPRING_FLUID_SCROLL.dampingRatio, 0.0001f)
        assertEquals(280f, SpringAnimatorHelper.SPRING_LOUPE_GLIDE.stiffness, 0.0001f)
        assertEquals(0.84f, SpringAnimatorHelper.SPRING_LOUPE_GLIDE.dampingRatio, 0.0001f)
        assertEquals(320f, SpringAnimatorHelper.SPRING_MORPH_SHEET.stiffness, 0.0001f)
        assertEquals(0.86f, SpringAnimatorHelper.SPRING_MORPH_SHEET.dampingRatio, 0.0001f)

        val profiles = listOf(
            SpringAnimatorHelper.SPRING_FLUID_SCROLL,
            SpringAnimatorHelper.SPRING_LOUPE_GLIDE,
            SpringAnimatorHelper.SPRING_MORPH_SHEET
        )
        for (profile in profiles) {
            for (dt in listOf(1f / 60f, 1f / 120f)) {
                val solver = SpringAnimatorHelper.SpringSolver(
                    value = 0f,
                    target = 1f,
                    stiffness = profile.stiffness,
                    dampingRatio = profile.dampingRatio
                )
                var settled = false
                var ticks = 0
                while (!settled && ticks < 500) {
                    settled = solver.step(dt)
                    ticks++
                }
                assertTrue("Profile $profile must settle at dt=$dt", settled)
                assertEquals(1f, solver.value, 0.0001f)
                assertEquals(0f, solver.velocity, 0.0001f)
            }
        }
    }

    @Test
    fun scrubMath_handlesNearestSlotRubberBandAndDynamicWidth() {
        val centers = floatArrayOf(100f, 250f, 450f)
        val widths = floatArrayOf(80f, 120f, 100f)

        assertEquals(0, InteractiveGlassScrubView.ScrubMath.nearestSlotIndex(130f, centers))
        assertEquals(1, InteractiveGlassScrubView.ScrubMath.nearestSlotIndex(240f, centers))
        assertEquals(2, InteractiveGlassScrubView.ScrubMath.nearestSlotIndex(520f, centers))

        // In-bounds: strictly 1:1
        assertEquals(250f, InteractiveGlassScrubView.ScrubMath.computeRubberBand(250f, 100f, 450f, 20f), 0.0001f)

        // Out-of-bounds: damped overshoot bounded by maxOvershoot
        val leftOvershoot = InteractiveGlassScrubView.ScrubMath.computeRubberBand(0f, 100f, 450f, 20f)
        assertTrue(leftOvershoot < 100f && leftOvershoot > 80f)

        val rightOvershoot = InteractiveGlassScrubView.ScrubMath.computeRubberBand(900f, 100f, 450f, 20f)
        assertTrue(rightOvershoot > 450f && rightOvershoot < 470f)

        // Midpoint width interpolation between slot 0 (80) and slot 1 (120)
        val midWidth = InteractiveGlassScrubView.ScrubMath.interpolateSlotWidth(175f, centers, widths)
        assertEquals(100f, midWidth, 0.001f)
    }

    @Test
    fun lyricWindowMath_framesMultiLineBlocksAndComputesWholeBlockBlurTiers() {
        val viewportH = 700
        val threeLineBlockH = 260

        val topAnchor = LyricsRecyclerAdapter.LyricWindowMath.computeScrollTopOffset(
            viewportH, threeLineBlockH, PlaybackStateStore.LYRIC_ANCHOR_TOP, topSafeMargin = 40, bottomSafeMargin = 80
        )
        val optimalAnchor = LyricsRecyclerAdapter.LyricWindowMath.computeScrollTopOffset(
            viewportH, threeLineBlockH, PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL, topSafeMargin = 40, bottomSafeMargin = 80
        )
        val centerAnchor = LyricsRecyclerAdapter.LyricWindowMath.computeScrollTopOffset(
            viewportH, threeLineBlockH, PlaybackStateStore.LYRIC_ANCHOR_CENTER, topSafeMargin = 40, bottomSafeMargin = 80
        )

        assertTrue(topAnchor < optimalAnchor && optimalAnchor < centerAnchor)
        assertTrue(centerAnchor + threeLineBlockH <= viewportH - 80)

        // Huge 4-line block clamps safely above bottom margin without clipping
        val tallBlockH = 560
        val tallOffset = LyricsRecyclerAdapter.LyricWindowMath.computeScrollTopOffset(
            viewportH, tallBlockH, PlaybackStateStore.LYRIC_ANCHOR_CENTER, topSafeMargin = 40, bottomSafeMargin = 80
        )
        assertTrue(tallOffset <= viewportH - 80 - tallBlockH)

        // Adaptive fade shrinks so multi-line active block stays sharp
        val defaultFade = 90
        val normalFade = LyricsRecyclerAdapter.LyricWindowMath.computeAdaptiveFadeLength(viewportH, 100, defaultFade)
        val tallFade = LyricsRecyclerAdapter.LyricWindowMath.computeAdaptiveFadeLength(viewportH, 520, defaultFade)
        assertEquals(defaultFade, normalFade)
        assertTrue(tallFade < defaultFade && tallFade >= defaultFade / 3)

        // Whole-block blur tiers: active = 0, neighbors = 1, rest = 2
        assertEquals(0, LyricsRecyclerAdapter.LyricWindowMath.computeBlurTier(4, 4, synced = true, selectionMode = false))
        assertEquals(1, LyricsRecyclerAdapter.LyricWindowMath.computeBlurTier(3, 4, synced = true, selectionMode = false))
        assertEquals(1, LyricsRecyclerAdapter.LyricWindowMath.computeBlurTier(5, 4, synced = true, selectionMode = false))
        assertEquals(2, LyricsRecyclerAdapter.LyricWindowMath.computeBlurTier(2, 4, synced = true, selectionMode = false))
    }

    @Test
    fun playbackStateStore_persistsLyricHighlightFractionAcrossReloads() {
        val file = File(tempFolder.root, "state.json")
        val store1 = PlaybackStateStore(file)
        assertEquals(PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL, store1.getLyricHighlightFraction(), 0.0001f)

        store1.setLyricHighlightFraction(PlaybackStateStore.LYRIC_ANCHOR_TOP)
        assertEquals(PlaybackStateStore.LYRIC_ANCHOR_TOP, PlaybackStateStore(file).getLyricHighlightFraction(), 0.0001f)

        store1.setLyricHighlightFraction(PlaybackStateStore.LYRIC_ANCHOR_CENTER)
        assertEquals(PlaybackStateStore.LYRIC_ANCHOR_CENTER, PlaybackStateStore(file).getLyricHighlightFraction(), 0.0001f)
    }

    @Test
    fun lyricTunerState_startsCollapsedAndAutoCollapsesOnSave() {
        val state = NowPlayingView.LyricTunerState()
        assertFalse(state.isExpanded)

        assertTrue(state.toggle())
        assertTrue(state.isExpanded)

        state.onSavePressed()
        assertFalse(state.isExpanded)

        state.expand()
        assertTrue(state.isExpanded)
        state.onEnterLyricsMode()
        assertFalse(state.isExpanded)
    }

    @Test
    fun vinylDisc_runsAt15SecondsPerRevolution() {
        assertEquals(24f, VinylDiscVisualizerView.TARGET_DEG_PER_SEC, 0.0001f)
    }
}
