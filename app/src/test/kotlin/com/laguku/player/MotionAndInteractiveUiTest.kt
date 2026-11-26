package com.laguku.player

import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.ui.glass.InteractiveGlassScrubView
import com.laguku.player.ui.library.LibraryView
import com.laguku.player.ui.motion.SpringAnimatorHelper
import com.laguku.player.ui.player.LyricMotionSpec
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

    @Test
    fun lyricAnchorPaddingAndEmptyGuard_alignFirstLineAt36Percent() {
        assertEquals(0 to 0, LyricsRecyclerAdapter.LyricWindowMath.computeAnchorPadding(0, 0.36f))
        assertEquals(280 to 720, LyricsRecyclerAdapter.LyricWindowMath.computeAnchorPadding(1000, PlaybackStateStore.LYRIC_ANCHOR_TOP))
        assertEquals(360 to 640, LyricsRecyclerAdapter.LyricWindowMath.computeAnchorPadding(1000, PlaybackStateStore.LYRIC_ANCHOR_OPTIMAL))
        assertEquals(480 to 520, LyricsRecyclerAdapter.LyricWindowMath.computeAnchorPadding(1000, PlaybackStateStore.LYRIC_ANCHOR_CENTER))

        assertEquals(-1, LyricsRecyclerAdapter.LyricWindowMath.resolveInitialAnchorIndex(0, -1))
        assertEquals(-1, LyricsRecyclerAdapter.LyricWindowMath.resolveInitialAnchorIndex(0, 2))
        assertEquals(0, LyricsRecyclerAdapter.LyricWindowMath.resolveInitialAnchorIndex(12, -1))
        assertEquals(5, LyricsRecyclerAdapter.LyricWindowMath.resolveInitialAnchorIndex(12, 5))
    }

    @Test
    fun subPixelScrollAccumulator_withUnifiedLyricSpringConvergesToZeroResidualError() {
        assertEquals(120f, LyricsRecyclerAdapter.LyricMotionMath.LYRIC_SCROLL_STIFFNESS, 0.0001f)
        assertEquals(0.78f, LyricsRecyclerAdapter.LyricMotionMath.LYRIC_SCROLL_DAMPING_RATIO, 0.0001f)
        assertEquals(0.94f, LyricsRecyclerAdapter.LyricMotionMath.FOCUS_SCALE_MIN, 0.0001f)
        assertFalse(LyricsRecyclerAdapter.LyricMotionMath.HAS_ITEM_BACKGROUND)

        val adapter = LyricsRecyclerAdapter(onLineTap = { _, _ -> })
        adapter.startSpringScrollTarget(137.4f)
        var scrolledPx = 0
        var ticks = 0
        while (adapter.isScrollSpringActive() && ticks < 500) {
            scrolledPx += adapter.stepSpringScroll(1f / 60f)
            ticks++
        }
        assertFalse(adapter.isScrollSpringActive())
        assertEquals(137, scrolledPx)
        assertEquals(0f, adapter.subPixelRemainder, 0.0001f)
    }

    @Test
    fun lyricMotionSpec_exposesExactPhysicsConstantsStaggerWordSweepAndPause() {
        assertEquals(120f, LyricMotionSpec.SPRING_STIFFNESS, 0.0001f)
        assertEquals(0.78f, LyricMotionSpec.SPRING_DAMPING_RATIO, 0.0001f)
        assertEquals(0.36f, LyricMotionSpec.FOCUS_ANCHOR_FRACTION, 0.0001f)
        assertEquals(40L, LyricMotionSpec.STAGGER_STEP_MS)
        assertEquals(90L, LyricMotionSpec.STAGGER_MAX_MS)
        assertEquals(0.94f, LyricMotionSpec.INACTIVE_SCALE, 0.0001f)
        assertEquals(3.5f, LyricMotionSpec.MAX_DEPTH_BLUR_PX, 0.0001f)
        assertTrue(LyricMotionSpec.WORD_SWEEP_ENABLED)
        assertEquals(2_500L, LyricMotionSpec.MANUAL_SCROLL_PAUSE_MS)

        // Stagger applies only to rows below activeIndex (capped at 90ms)
        assertEquals(0L, LyricsRecyclerAdapter.LyricMotionMath.computeStaggerDelayMs(rowIndex = 1, activeIndex = 2))
        assertEquals(0L, LyricsRecyclerAdapter.LyricMotionMath.computeStaggerDelayMs(rowIndex = 2, activeIndex = 2))
        assertEquals(40L, LyricsRecyclerAdapter.LyricMotionMath.computeStaggerDelayMs(rowIndex = 3, activeIndex = 2))
        assertEquals(80L, LyricsRecyclerAdapter.LyricMotionMath.computeStaggerDelayMs(rowIndex = 4, activeIndex = 2))
        assertEquals(90L, LyricsRecyclerAdapter.LyricMotionMath.computeStaggerDelayMs(rowIndex = 5, activeIndex = 2))

        // Downward microLagY only affects rows below activeIndex
        assertEquals(0f, LyricsRecyclerAdapter.LyricMotionMath.computeInitialMicroLagY(1, 2, 120f, 3f), 0.0001f)
        assertTrue(LyricsRecyclerAdapter.LyricMotionMath.computeInitialMicroLagY(3, 2, 120f, 3f) > 0f)
        assertEquals(0f, LyricsRecyclerAdapter.LyricMotionMath.computeInitialMicroLagY(3, 2, 120f, 3f, reducedMotion = true), 0.0001f)

        // Word-by-word sweep progression
        val parsed = com.laguku.player.lyrics.LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>world")
        val words = parsed.lines.first().words
        val midProgress = LyricsRecyclerAdapter.LyricMotionMath.computeSweepCharProgress(
            textLength = parsed.lines.first().text.length,
            words = words,
            lineStartMs = 1_000L,
            lineEndMs = 2_500L,
            positionMs = 1_750L
        )
        assertTrue("Expected sweep midProgress inside line text, got $midProgress", midProgress > 6f && midProgress < 11f)
    }

    @Test
    fun lyricViewHolder_hasNoBackgroundDrawableFieldAndSetActiveIndexSkipsNotifyItemChanged() {
        val bgFields = LyricsRecyclerAdapter.LyricViewHolder::class.java.declaredFields.filter { field ->
            field.name.contains("Glass", ignoreCase = true) ||
                field.name.contains("Pill", ignoreCase = true) ||
                field.name.contains("Background", ignoreCase = true)
        }
        assertTrue("LyricViewHolder must not hold any pill/background drawable fields: $bgFields", bgFields.isEmpty())

        val adapter = LyricsRecyclerAdapter(onLineTap = { _, _ -> })
        val parsed = com.laguku.player.lyrics.LrcParser.parse("[00:01.00]Line 1\n[00:05.00]Line 2\n[00:10.00]Line 3")
        // Seed adapter state directly (RecyclerView's AdapterDataObservable throws NPE in JVM stub jar if any notify* is called)
        LyricsRecyclerAdapter::class.java.getDeclaredField("lines").apply {
            isAccessible = true
            set(adapter, parsed.lines)
        }
        LyricsRecyclerAdapter::class.java.getDeclaredField("synced").apply {
            isAccessible = true
            setBoolean(adapter, true)
        }

        // Must update activeIndex in-place without invoking notifyItemChanged / notifyItemRangeChanged
        adapter.setActiveIndex(1, recyclerView = null, autoScroll = false)
        assertEquals(1, adapter.getActiveIndex())
        adapter.setActiveIndex(2, recyclerView = null, autoScroll = false)
        assertEquals(2, adapter.getActiveIndex())
    }

    @Test
    fun scrubDwellController_commitsAfter240msWithoutDoubleTriggerOnRelease() {
        assertEquals(240L, InteractiveGlassScrubView.ScrubDwellController.DWELL_DELAY_MS)
        val controller = InteractiveGlassScrubView.ScrubDwellController()

        controller.onTouchDown(initialSelectedIndex = 0)
        assertTrue(controller.onHoverSlot(2, nowMs = 1_000L))

        // Before 240ms: dwell does not fire
        assertEquals(null, controller.onDwellTimeout(1_239L))
        assertFalse(controller.dwellCommitted)

        // At 240ms: dwell commits slot 2 once
        assertEquals(2, controller.onDwellTimeout(1_240L))
        assertTrue(controller.dwellCommitted)

        // Release on slot 2 must NOT double-trigger
        assertEquals(null, controller.onTouchUp(targetIndex = 2, isCancel = false))

        // Quick drag + release before 240ms commits once on UP
        controller.onTouchDown(initialSelectedIndex = 2)
        controller.onHoverSlot(1, nowMs = 2_000L)
        assertEquals(1, controller.onTouchUp(targetIndex = 1, isCancel = false))

        // Cancel never commits
        controller.onTouchDown(initialSelectedIndex = 1)
        controller.onHoverSlot(4, nowMs = 3_000L)
        assertEquals(null, controller.onTouchUp(targetIndex = 4, isCancel = true))
    }

    @Test
    fun navBarScrollMath_shrinksOnDownScrollAndExpandsOnUpScroll() {
        assertEquals(0.94f, LibraryView.NavBarScrollMath.COMPACT_SCALE, 0.0001f)

        // Down scroll past threshold compacts navbar
        assertTrue(LibraryView.NavBarScrollMath.resolveCompactState(currentlyCompact = false, dy = 24))
        // Small jitter preserves current compact state
        assertTrue(LibraryView.NavBarScrollMath.resolveCompactState(currentlyCompact = true, dy = -4))
        assertFalse(LibraryView.NavBarScrollMath.resolveCompactState(currentlyCompact = false, dy = 6))
        // Up scroll past threshold restores full navbar
        assertFalse(LibraryView.NavBarScrollMath.resolveCompactState(currentlyCompact = true, dy = -24))
    }
}
