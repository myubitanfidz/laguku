package com.laguku.player

import com.laguku.player.data.PlaybackStateStore
import com.laguku.player.ui.glass.InteractiveGlassScrubView
import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.library.LibraryView
import com.laguku.player.ui.motion.LiquidGlassPressAnimator
import com.laguku.player.ui.motion.PageMotionSpec
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
import kotlin.math.roundToInt

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

    @Test
    fun lyricsMorphSpec_resolvesMiniHeaderSecondaryDockAndDissolutionStates() {
        assertEquals(44f, NowPlayingView.LyricsMorphSpec.MINI_COVER_SIZE_DP, 0.0001f)
        assertEquals(10f, NowPlayingView.LyricsMorphSpec.MINI_COVER_CORNER_RADIUS_DP, 0.0001f)
        assertEquals(16f, NowPlayingView.LyricsMorphSpec.MINI_TITLE_SIZE_SP, 0.0001f)
        assertEquals(13f, NowPlayingView.LyricsMorphSpec.MINI_ARTIST_SIZE_SP, 0.0001f)
        assertEquals(0.68f, NowPlayingView.LyricsMorphSpec.MINI_ARTIST_ALPHA, 0.0001f)
        assertEquals(340f, NowPlayingView.LyricsMorphSpec.MORPH_RESPONSE_MS, 0.0001f)
        assertEquals(0.88f, NowPlayingView.LyricsMorphSpec.MORPH_DAMPING_FRACTION, 0.0001f)
        assertEquals(0.70f, NowPlayingView.LyricsMorphSpec.LYRICS_VIEWPORT_TARGET_FRACTION, 0.0001f)
        assertEquals(SpringAnimatorHelper.SPRING_SMOOTH.dampingRatio, NowPlayingView.LyricsMorphSpec.MORPH_DAMPING_FRACTION, 0.0001f)

        val coverChrome = NowPlayingView.LyricsMorphSpec.resolveChromeState(NowPlayingView.DisplayMode.FULL_COVER)
        assertFalse(coverChrome.showMiniHeader)
        assertTrue(coverChrome.showPassiveHeader)
        assertTrue(coverChrome.showDockTrackRow)
        assertTrue(coverChrome.showSecondaryDockRow)
        assertTrue(coverChrome.showCenterCover)

        val discChrome = NowPlayingView.LyricsMorphSpec.resolveChromeState(NowPlayingView.DisplayMode.VINYL_DISC)
        assertFalse(discChrome.showMiniHeader)
        assertTrue(discChrome.showSecondaryDockRow)
        assertTrue(discChrome.showVinylDisc)

        val lyricsChrome = NowPlayingView.LyricsMorphSpec.resolveChromeState(NowPlayingView.DisplayMode.LYRICS_FOCUS)
        assertTrue(lyricsChrome.showMiniHeader)
        assertFalse(lyricsChrome.showPassiveHeader)
        assertFalse(lyricsChrome.showDockTrackRow)
        assertFalse(lyricsChrome.showSecondaryDockRow)
        assertTrue(lyricsChrome.showLyricsStage)

        val zenLyricsChrome = NowPlayingView.LyricsMorphSpec.resolveChromeState(NowPlayingView.DisplayMode.LYRICS_FOCUS, zenMode = true)
        assertFalse(zenLyricsChrome.showMiniHeader)

        val emptyEdge = NowPlayingView.LyricsMorphSpec.computeEdgeDissolutionAlphas(0, 500, 100f, hasLines = false)
        assertEquals(0f to 0f, emptyEdge)
        val topEdge = NowPlayingView.LyricsMorphSpec.computeEdgeDissolutionAlphas(0, 500, 100f, hasLines = true)
        assertEquals(0f, topEdge.first, 0.0001f)
        assertEquals(1f, topEdge.second, 0.0001f)
        val midEdge = NowPlayingView.LyricsMorphSpec.computeEdgeDissolutionAlphas(200, 500, 100f, hasLines = true)
        assertEquals(1f, midEdge.first, 0.0001f)
        assertEquals(1f, midEdge.second, 0.0001f)
        val bottomEdge = NowPlayingView.LyricsMorphSpec.computeEdgeDissolutionAlphas(500, 500, 100f, hasLines = true)
        assertEquals(1f, bottomEdge.first, 0.0001f)
        assertEquals(0f, bottomEdge.second, 0.0001f)
    }

    @Test
    fun ambientMeshMath_extracts3ColorsEnforces7To1ContrastAndGatesLifecycle() {
        assertEquals(20f, NowPlayingView.AmbientMeshMath.MESH_CYCLE_SECONDS, 0.0001f)
        assertEquals(7.0, NowPlayingView.AmbientMeshMath.MIN_TEXT_CONTRAST_RATIO, 0.0001)

        // Fallback on empty pixels uses dark glass mesh palette
        val fallback = NowPlayingView.AmbientMeshMath.extractMeshPaletteFromPixels(IntArray(0))
        assertEquals(NowPlayingView.AmbientMeshMath.defaultMeshPalette(), fallback)

        // Multi-color cover extracts 3 distinct palette blobs
        val vividPixels = IntArray(90) { i ->
            when (i % 3) {
                0 -> 0xFF1E88E5.toInt()
                1 -> 0xFFE53935.toInt()
                else -> 0xFF43A047.toInt()
            }
        }
        val extracted = NowPlayingView.AmbientMeshMath.extractMeshPaletteFromPixels(vividPixels)
        assertTrue(extracted.primaryBlob != extracted.secondaryBlob)
        assertTrue(extracted.secondaryBlob != extracted.tertiaryBlob)

        // Pure white worst-case cover still enforces >= 7.0:1 contrast with white text
        val whitePixels = IntArray(64) { 0xFFFFFFFF.toInt() }
        val whiteMesh = NowPlayingView.AmbientMeshMath.extractMeshPaletteFromPixels(whitePixels)
        val veilAlpha = NowPlayingView.AmbientMeshMath.computeAdaptiveVeilAlpha(
            whiteMesh.averageLuminance,
            whiteMesh.primaryBlob,
            whiteMesh.secondaryBlob,
            whiteMesh.tertiaryBlob
        )
        val dimmedWhite = NowPlayingView.AmbientMeshMath.blendColorOverBlack(0xFFFFFFFF.toInt(), veilAlpha)
        assertTrue(
            NowPlayingView.AmbientMeshMath.computeContrastRatio(0xFFFFFFFF.toInt(), dimmedWhite) >= 7.0
        )

        // 20s periodic harmonic orbit returns cleanly across full cycle 0..2*PI
        val startPos = FloatArray(3)
        val midPos = FloatArray(3)
        val endPos = FloatArray(3)
        NowPlayingView.AmbientMeshMath.computeBlobCenterAndRadius(0, 0f, 1080f, 2400f, startPos)
        NowPlayingView.AmbientMeshMath.computeBlobCenterAndRadius(0, Math.PI.toFloat(), 1080f, 2400f, midPos)
        NowPlayingView.AmbientMeshMath.computeBlobCenterAndRadius(0, (2.0 * Math.PI).toFloat(), 1080f, 2400f, endPos)
        assertEquals(startPos[0], endPos[0], 0.1f)
        assertEquals(startPos[1], endPos[1], 0.1f)
        assertTrue(kotlin.math.abs(startPos[0] - midPos[0]) > 50f)

        // Lifecycle gating for ambient mesh and word sweep
        assertTrue(NowPlayingView.AmbientMeshMath.shouldAnimateAmbient(true, true, true, false, 1f))
        assertFalse(NowPlayingView.AmbientMeshMath.shouldAnimateAmbient(false, true, true, false, 1f))
        assertFalse(NowPlayingView.AmbientMeshMath.shouldAnimateAmbient(true, false, true, false, 1f))
        assertFalse(NowPlayingView.AmbientMeshMath.shouldAnimateAmbient(true, true, false, false, 1f))
        assertFalse(NowPlayingView.AmbientMeshMath.shouldAnimateAmbient(true, true, true, true, 1f))
        assertFalse(NowPlayingView.AmbientMeshMath.shouldAnimateAmbient(true, true, true, false, 0f))

        assertTrue(
            NowPlayingView.AmbientMeshMath.shouldAnimateWordSweep(
                isPlaying = true,
                isPlayerVisible = true,
                isHostActive = true,
                mode = NowPlayingView.DisplayMode.LYRICS_FOCUS,
                synced = true,
                animatorDurationScale = 1f
            )
        )
        assertFalse(
            NowPlayingView.AmbientMeshMath.shouldAnimateWordSweep(
                isPlaying = false,
                isPlayerVisible = true,
                isHostActive = true,
                mode = NowPlayingView.DisplayMode.LYRICS_FOCUS,
                synced = true,
                animatorDurationScale = 1f
            )
        )
        assertFalse(
            NowPlayingView.AmbientMeshMath.shouldAnimateWordSweep(
                isPlaying = true,
                isPlayerVisible = true,
                isHostActive = true,
                mode = NowPlayingView.DisplayMode.FULL_COVER,
                synced = true,
                animatorDurationScale = 1f
            )
        )
        assertFalse(
            NowPlayingView.AmbientMeshMath.shouldAnimateWordSweep(
                isPlaying = true,
                isPlayerVisible = true,
                isHostActive = false,
                mode = NowPlayingView.DisplayMode.LYRICS_FOCUS,
                synced = true,
                animatorDurationScale = 1f
            )
        )
    }

    @Test
    fun interludeDotsMath_detectsIntroAndBetweenLine4000msGapsAndProgressiveDotFills() {
        assertEquals(4_000L, LyricsRecyclerAdapter.InterludeDotsMath.INTERLUDE_THRESHOLD_MS)
        assertEquals(3, LyricsRecyclerAdapter.InterludeDotsMath.DOT_COUNT)
        assertEquals(8f, LyricsRecyclerAdapter.InterludeDotsMath.DOT_DIAMETER_DP, 0.0001f)
        assertEquals(10f, LyricsRecyclerAdapter.InterludeDotsMath.DOT_GAP_DP, 0.0001f)

        val lines = listOf(
            com.laguku.player.model.LyricLine(timestampMs = 6_000L, text = "Intro arrives", endMs = 8_000L),
            com.laguku.player.model.LyricLine(timestampMs = 10_500L, text = "Short gap", endMs = 12_000L),
            com.laguku.player.model.LyricLine(timestampMs = 18_000L, text = "After long interlude", endMs = 20_000L)
        )

        // Intro >= 4000ms active with linear 0..1 progress
        val introHalf = LyricsRecyclerAdapter.InterludeDotsMath.resolveInterludeState(lines, positionMs = 3_000L)
        assertTrue(introHalf.active)
        assertTrue(introHalf.isIntro)
        assertEquals(0, introHalf.anchorRowIndex)
        assertEquals(0.5f, introHalf.progress, 0.001f)

        // Short 2500ms gap between lines 0 and 1 must NOT trigger dots
        val shortGap = LyricsRecyclerAdapter.InterludeDotsMath.resolveInterludeState(lines, positionMs = 9_000L)
        assertFalse(shortGap.active)

        // Long 6000ms gap between lines 1 and 2 triggers between-line interlude
        val longMid = LyricsRecyclerAdapter.InterludeDotsMath.resolveInterludeState(lines, positionMs = 15_000L)
        assertTrue(longMid.active)
        assertFalse(longMid.isIntro)
        assertEquals(1, longMid.anchorRowIndex)
        assertEquals(0.5f, longMid.progress, 0.001f)

        // Progressive 3-dot fill light across countdown
        val d0Start = LyricsRecyclerAdapter.InterludeDotsMath.computeDotFillAlpha(0, 0f)
        val d0Full = LyricsRecyclerAdapter.InterludeDotsMath.computeDotFillAlpha(0, 0.34f)
        val d1Half = LyricsRecyclerAdapter.InterludeDotsMath.computeDotFillAlpha(1, 0.50f)
        val d2End = LyricsRecyclerAdapter.InterludeDotsMath.computeDotFillAlpha(2, 1.00f)
        assertTrue(d0Full > d0Start)
        assertTrue(d1Half in 0.50f..0.75f)
        assertEquals(1.0f, d2End, 0.001f)
    }

    @Test
    fun horizontalSwipeCommitController_commitsTrackChangeOnlyOnReleaseAndAllowsCancelByReturningOrActionCancel() {
        val controller = NowPlayingView.HorizontalSwipeCommitController()
        val slopPx = 16f
        val thresholdPx = 150f
        val maxOffsetPx = 210f
        var navCount = 0
        var lastDir = 0
        val dispatchRelease: (Int) -> Unit = { dir ->
            if (dir != 0) {
                navCount++
                lastDir = dir
            }
        }

        // 1. ACTION_MOVE past threshold while finger still held down -> visual translationX updates, 0 track changes
        controller.onDown(x = 500f, y = 400f, nowMs = 1_000L)
        assertFalse(controller.onMove(x = 300f, y = 402f, nowMs = 1_040L, touchSlopPx = slopPx, swipeThresholdPx = thresholdPx, maxOffsetPx = maxOffsetPx))
        assertEquals(NowPlayingView.HorizontalSwipeCommitController.GestureState.HORIZONTAL_DRAG, controller.state)
        assertTrue("Expected rubber-band negative translationX, got ${controller.translationX}", controller.translationX < 0f)
        assertEquals(0, controller.committedDirection)
        assertEquals(0, navCount)

        // 2. Slide back to center before ACTION_UP -> neutral on release, 0 track changes
        assertFalse(controller.onMove(x = 492f, y = 401f, nowMs = 1_120L, touchSlopPx = slopPx, swipeThresholdPx = thresholdPx, maxOffsetPx = maxOffsetPx))
        dispatchRelease(controller.onRelease(x = 492f, isCancel = false, swipeThresholdPx = thresholdPx))
        assertEquals(0, controller.committedDirection)
        assertEquals(0f, controller.translationX, 0.0001f)
        assertEquals(0, navCount)

        // 3a. ACTION_UP outside threshold (swipe left -> next (+1) exactly once)
        controller.onDown(x = 500f, y = 400f, nowMs = 2_000L)
        controller.onMove(x = 460f, y = 400f, nowMs = 2_020L, touchSlopPx = slopPx, swipeThresholdPx = thresholdPx, maxOffsetPx = maxOffsetPx)
        controller.onMove(x = 310f, y = 400f, nowMs = 2_080L, touchSlopPx = slopPx, swipeThresholdPx = thresholdPx, maxOffsetPx = maxOffsetPx)
        assertEquals(0, navCount) // Still held -> 0 changes
        dispatchRelease(controller.onRelease(x = 310f, isCancel = false, swipeThresholdPx = thresholdPx))
        assertEquals(1, navCount)
        assertEquals(1, lastDir)
        assertEquals(0f, controller.translationX, 0.0001f)

        // 3b. ACTION_UP outside threshold (swipe right -> previous (-1) exactly once)
        navCount = 0
        lastDir = 0
        controller.onDown(x = 500f, y = 400f, nowMs = 3_000L)
        controller.onMove(x = 690f, y = 400f, nowMs = 3_080L, touchSlopPx = slopPx, swipeThresholdPx = thresholdPx, maxOffsetPx = maxOffsetPx)
        assertEquals(0, navCount)
        dispatchRelease(controller.onRelease(x = 690f, isCancel = false, swipeThresholdPx = thresholdPx))
        assertEquals(1, navCount)
        assertEquals(-1, lastDir)

        // 4. ACTION_CANCEL never triggers track change even when past threshold
        navCount = 0
        lastDir = 0
        controller.onDown(x = 500f, y = 400f, nowMs = 4_000L)
        controller.onMove(x = 280f, y = 400f, nowMs = 4_080L, touchSlopPx = slopPx, swipeThresholdPx = thresholdPx, maxOffsetPx = maxOffsetPx)
        dispatchRelease(controller.onRelease(x = 280f, isCancel = true, swipeThresholdPx = thresholdPx))
        assertEquals(0, navCount)
        assertEquals(0, controller.committedDirection)
        assertEquals(0f, controller.translationX, 0.0001f)

        // 5. Fast outward fling triggers next once on release; PlayLoupeController slide-back cancels cleanly
        navCount = 0
        lastDir = 0
        controller.onDown(x = 500f, y = 400f, nowMs = 5_000L)
        controller.onMove(x = 460f, y = 400f, nowMs = 5_020L, touchSlopPx = slopPx, swipeThresholdPx = thresholdPx, maxOffsetPx = maxOffsetPx)
        controller.onMove(x = 420f, y = 400f, nowMs = 5_040L, touchSlopPx = slopPx, swipeThresholdPx = thresholdPx, maxOffsetPx = maxOffsetPx) // -2000 px/s outward
        assertEquals(0, navCount)
        dispatchRelease(controller.onRelease(x = 420f, isCancel = false, swipeThresholdPx = thresholdPx))
        assertEquals(1, navCount)
        assertEquals(1, lastDir)

        val playCtrl = NowPlayingView.PlayLoupeController()
        playCtrl.onTouchDown()
        playCtrl.onDragMove(rawDxPx = 150f, thresholdPx = 120f, maxOffsetPx = 180f, nowMs = 6_000L)
        assertEquals(1, playCtrl.activeDirection)
        playCtrl.onDragMove(rawDxPx = 12f, thresholdPx = 120f, maxOffsetPx = 180f, nowMs = 6_400L)
        assertEquals(0, playCtrl.activeDirection)
        assertEquals(0, playCtrl.onTouchUp(wasDragging = true, isCancel = false))
    }

    @Test
    fun metadataGlassSpec_usesCalibratedTypographyAndThinRim() {
        assertEquals(18f, NowPlayingView.MetadataGlassSpec.TITLE_SIZE_SP, 0.0001f)
        assertEquals(14f, NowPlayingView.MetadataGlassSpec.ARTIST_SIZE_SP, 0.0001f)
        assertEquals(0.72f, NowPlayingView.MetadataGlassSpec.ARTIST_ALPHA, 0.0001f)
        assertEquals(1.0f, NowPlayingView.MetadataGlassSpec.RIM_WIDTH_DP, 0.0001f)
        assertEquals(18f, NowPlayingView.MetadataGlassSpec.CORNER_RADIUS_DP, 0.0001f)
        assertEquals((0.72f * 255f).roundToInt(), (NowPlayingView.MetadataGlassSpec.ARTIST_TEXT_COLOR ushr 24) and 0xFF)
        assertEquals(0xFFFFFF, NowPlayingView.MetadataGlassSpec.ARTIST_TEXT_COLOR and 0x00FFFFFF)
    }

    @Test
    fun activeTrackMetadata_keepsLiquidGlassBackgrounds() {
        val npSource = File("src/main/kotlin/com/laguku/player/ui/player/NowPlayingView.kt").readText()
        assertTrue(npSource.contains("background = metadataGlass"))
        assertTrue(npSource.contains("background = qualityBadgeGlass"))

        val onTrackChangedBody = npSource.substringAfter("fun onTrackChanged(").substringBefore("private fun triggerTrackNavigation")
        assertFalse(onTrackChangedBody.contains("background"))
        assertFalse(onTrackChangedBody.contains("setBackground"))
        assertFalse(onTrackChangedBody.contains("GradientDrawable"))

        val renderBadgeBody = npSource.substringAfter("private fun renderQualityBadge(").substringBefore("fun isModalOpen")
        assertFalse(renderBadgeBody.contains("background"))
        assertFalse(renderBadgeBody.contains("setBackground"))
        assertFalse(renderBadgeBody.contains("GradientDrawable"))

        val lvSource = File("src/main/kotlin/com/laguku/player/ui/library/LibraryView.kt").readText()
        val updateMiniBody = lvSource.substringAfter("fun updateMiniPlayer(").substringBefore("fun updateMiniPlayerProgress")
        assertFalse(updateMiniBody.contains("background"))
        assertFalse(updateMiniBody.contains("setBackground"))
        assertFalse(updateMiniBody.contains("GradientDrawable"))
    }

    @Test
    fun utilityMenuState_opensClosesAndReportsActiveIndicator() {
        val state = NowPlayingView.UtilityMenuState()
        assertFalse(state.isOpen)
        state.open()
        assertTrue(state.isOpen)
        state.close()
        assertFalse(state.isOpen)

        assertFalse(NowPlayingView.UtilityMenuState.hasActiveIndicator(dspActive = false, timerActive = false, amoledEnabled = false))
        assertTrue(NowPlayingView.UtilityMenuState.hasActiveIndicator(dspActive = true, timerActive = false, amoledEnabled = false))
        assertTrue(NowPlayingView.UtilityMenuState.hasActiveIndicator(dspActive = false, timerActive = true, amoledEnabled = false))
        assertTrue(NowPlayingView.UtilityMenuState.hasActiveIndicator(dspActive = false, timerActive = false, amoledEnabled = true))
    }

    @Test
    fun nowPlaying_hasSingleUtilityTriggerInsteadOfThreePills() {
        val npSource = File("src/main/kotlin/com/laguku/player/ui/player/NowPlayingView.kt").readText()
        assertFalse(npSource.contains("labPill"))
        assertFalse(npSource.contains("timerPill"))
        assertFalse(npSource.contains("amoledPill"))
        assertTrue(npSource.contains("showUtilitiesMenu"))
        assertTrue(npSource.contains("utilityTrigger"))
    }

    @Test
    fun coverAura_decaysToZeroBeforeHaloEdge() {
        assertEquals(0.24f, NowPlayingView.CoverAuraMath.alphaAt(0f), 0.0001f)
        assertEquals(0.09f, NowPlayingView.CoverAuraMath.alphaAt(0.50f), 0.0001f)
        assertEquals(0.02f, NowPlayingView.CoverAuraMath.alphaAt(0.78f), 0.0001f)
        assertEquals(0f, NowPlayingView.CoverAuraMath.alphaAt(1f), 0.0001f)
        assertTrue(NowPlayingView.CoverAuraMath.alphaAt(0.90f) < 0.02f)

        var prev = NowPlayingView.CoverAuraMath.alphaAt(0f)
        for (i in 1..20) {
            val next = NowPlayingView.CoverAuraMath.alphaAt(i / 20f)
            assertTrue("Expected strictly decreasing aura alpha at step $i", next < prev)
            prev = next
        }

        assertEquals(80f, NowPlayingView.CoverAuraMath.haloRadius(100f), 0.0001f)
        assertEquals(0, (NowPlayingView.CoverAuraMath.gradientColors().last() ushr 24) and 0xFF)
        assertEquals(0, NowPlayingView.CoverAuraMath.auraPaintAlpha(1f))
        assertEquals(255, NowPlayingView.CoverAuraMath.auraPaintAlpha(0f))
    }

    @Test
    fun coverArt_hasNoBoxShadowElevation() {
        val npSource = File("src/main/kotlin/com/laguku/player/ui/player/NowPlayingView.kt").readText()
        val stageBody = npSource.substringAfter("private fun buildCenterStage(").substringBefore("vinylDiscView.apply")
        assertFalse(Regex("""elevation\s*=\s*[1-9]""").containsMatchIn(stageBody))
        assertTrue(stageBody.contains("coverAuraView"))
    }

    @Test
    fun liquidGlassPressAnimator_compressesTo092And095AndRecoversOnUpAndCancel() {
        assertEquals(0.92f, LiquidGlassPressAnimator.COMPACT_PRESS_SCALE, 0.0001f)
        assertEquals(0.95f, LiquidGlassPressAnimator.WIDE_PRESS_SCALE, 0.0001f)
        assertEquals(500f, LiquidGlassPressAnimator.PRESS_STIFFNESS, 0.0001f)
        assertEquals(0.72f, LiquidGlassPressAnimator.PRESS_DAMPING_RATIO, 0.0001f)
        assertEquals(380f, LiquidGlassPressAnimator.RELEASE_STIFFNESS, 0.0001f)
        assertEquals(0.66f, LiquidGlassPressAnimator.RELEASE_DAMPING_RATIO, 0.0001f)

        for ((style, expectedScale) in listOf(
            LiquidGlassPressAnimator.PressStyle.COMPACT to 0.92f,
            LiquidGlassPressAnimator.PressStyle.WIDE to 0.95f
        )) {
            val state = LiquidGlassPressAnimator.PressPhysicsState(style)
            state.onActionDown(42f, 18f)
            assertEquals(expectedScale, state.targetScale, 0.0001f)
            assertEquals(1f, state.targetPressFraction, 0.0001f)

            val downSolver = SpringAnimatorHelper.SpringSolver(
                value = 1f,
                target = state.targetScale,
                stiffness = state.activeStiffness,
                dampingRatio = state.activeDampingRatio
            )
            var downSettled = false
            var ticks = 0
            while (!downSettled && ticks < 400) {
                downSettled = downSolver.step(1f / 60f)
                ticks++
            }
            assertTrue(downSettled)
            assertEquals(expectedScale, downSolver.value, 0.0001f)

            state.onActionUp()
            assertEquals(1.0f, state.targetScale, 0.0001f)
            assertEquals(0f, state.targetPressFraction, 0.0001f)

            val upSolver = SpringAnimatorHelper.SpringSolver(
                value = downSolver.value,
                target = state.targetScale,
                stiffness = state.activeStiffness,
                dampingRatio = state.activeDampingRatio
            )
            var upSettled = false
            ticks = 0
            while (!upSettled && ticks < 400) {
                upSettled = upSolver.step(1f / 60f)
                ticks++
            }
            assertTrue(upSettled)
            assertEquals(1.0f, upSolver.value, 0.0001f)

            state.onActionDown(10f, 12f)
            state.onActionCancel()
            assertEquals(1.0f, state.targetScale, 0.0001f)
            assertEquals(0f, state.targetPressFraction, 0.0001f)
        }
    }

    @Test
    fun liquidGlassDrawable_pressOpticsBoostSpecularRimAndBezelFlex() {
        assertEquals(0f, LiquidGlassDrawable.PressOpticsMath.computeSpecularBoost(0f), 0.0001f)
        assertEquals(0f, LiquidGlassDrawable.PressOpticsMath.computeRimAlphaBoost(0f), 0.0001f)
        assertEquals(0f, LiquidGlassDrawable.PressOpticsMath.computeBezelPressFlex(0f), 0.0001f)
        assertTrue(LiquidGlassDrawable.PressOpticsMath.computeSpecularBoost(1f) > LiquidGlassDrawable.PressOpticsMath.computeSpecularBoost(0.5f))
        assertTrue(LiquidGlassDrawable.PressOpticsMath.computeRimAlphaBoost(1f) > LiquidGlassDrawable.PressOpticsMath.computeRimAlphaBoost(0.5f))
        assertEquals(1f, LiquidGlassDrawable.PressOpticsMath.computeBezelPressFlex(1f), 0.0001f)

        val glass = LiquidGlassDrawable(cornerRadiusPx = 20f, rimWidthPx = 2f)
        glass.setPressState(1f, 36f, 24f)
        assertEquals(1f, glass.getPressFraction(), 0.0001f)
        assertEquals(36f, glass.getTouchX(), 0.0001f)
        assertEquals(24f, glass.getTouchY(), 0.0001f)

        glass.setPressState(0f, 36f, 24f)
        assertEquals(0f, glass.getPressFraction(), 0.0001f)
    }

    @Test
    fun pageMotionSpec_matchesCalibratedPhysicsAndStaggerAndAuraConstants() {
        assertEquals(160f, PageMotionSpec.PAGE_MORPH_STIFFNESS, 0.0001f)
        assertEquals(0.68f, PageMotionSpec.PAGE_MORPH_DAMPING_RATIO, 0.0001f)
        assertEquals(0.94f, PageMotionSpec.LIBRARY_RECEDE_SCALE, 0.0001f)
        assertEquals(35L, PageMotionSpec.LIBRARY_STAGGER_STEP_MS)
        assertEquals(24f, PageMotionSpec.LIBRARY_ROW_ENTRY_OFFSET_DP, 0.0001f)
        assertEquals(72f, PageMotionSpec.SWIPE_COMMIT_THRESHOLD_DP, 0.0001f)
        assertEquals(0.68f, PageMotionSpec.SWIPE_RUBBER_BAND_RESISTANCE, 0.0001f)
        assertTrue(PageMotionSpec.COMMIT_ON_RELEASE_ONLY)
        assertEquals(1.60f, PageMotionSpec.COVER_AURA_SCALE_MULTIPLIER, 0.0001f)
        assertEquals(4, PageMotionSpec.COVER_AURA_ALPHA_STOPS.size)
        assertEquals(0.24f, PageMotionSpec.COVER_AURA_ALPHA_STOPS[0], 0.0001f)
        assertEquals(0.09f, PageMotionSpec.COVER_AURA_ALPHA_STOPS[1], 0.0001f)
        assertEquals(0.02f, PageMotionSpec.COVER_AURA_ALPHA_STOPS[2], 0.0001f)
        assertEquals(0.00f, PageMotionSpec.COVER_AURA_ALPHA_STOPS[3], 0.0001f)

        assertEquals(1f, PageMotionSpec.computeLibraryRecedeScale(0f), 0.0001f)
        assertEquals(0.94f, PageMotionSpec.computeLibraryRecedeScale(1f), 0.0001f)
        assertEquals(0L, PageMotionSpec.computeStaggerDelayMs(0))
        assertEquals(105L, PageMotionSpec.computeStaggerDelayMs(3))
        assertEquals(1.60f, NowPlayingView.CoverAuraMath.HALO_SCALE, 0.0001f)
    }
}
