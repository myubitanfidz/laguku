package com.laguku.player

import com.laguku.player.ui.motion.SpringAnimatorHelper
import com.laguku.player.ui.player.LyricsRecyclerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MotionTimingTest {

    @Test
    fun springAnimatorHelper_exposesCalibratedLiquidEaseCurveAndTimings() {
        assertEquals(0.22f, SpringAnimatorHelper.CURVE_X1, 0.0001f)
        assertEquals(1f, SpringAnimatorHelper.CURVE_Y1, 0.0001f)
        assertEquals(0.36f, SpringAnimatorHelper.CURVE_X2, 0.0001f)
        assertEquals(1f, SpringAnimatorHelper.CURVE_Y2, 0.0001f)
        assertNotNull(SpringAnimatorHelper.LIQUID_EASE)

        assertEquals(0.97f, SpringAnimatorHelper.PRESS_SCALE, 0.0001f)
        assertEquals(140L, SpringAnimatorHelper.PRESS_IN_DURATION_MS)
        assertEquals(220L, SpringAnimatorHelper.PRESS_RELEASE_DURATION_MS)
        assertEquals(420L, SpringAnimatorHelper.CROSSFADE_DURATION_MS)
        assertEquals(340, SpringAnimatorHelper.LYRIC_SCROLL_DURATION_MS)
        assertEquals(260L, SpringAnimatorHelper.LYRIC_SCALE_DURATION_MS)
        assertEquals(300L, SpringAnimatorHelper.MODE_SWITCH_IN_DURATION_MS)
        assertEquals(240L, SpringAnimatorHelper.MODE_SWITCH_OUT_DURATION_MS)
        assertEquals(340L, SpringAnimatorHelper.SHEET_ENTER_DURATION_MS)
        assertEquals(280L, SpringAnimatorHelper.SHEET_EXIT_DURATION_MS)
    }

    @Test
    fun lyricsRecyclerAdapter_alignsScrollAndScaleDurationsWithSpringAnimatorHelper() {
        assertEquals(340, LyricsRecyclerAdapter.SCROLL_DURATION_MS)
        assertEquals(260L, LyricsRecyclerAdapter.SCALE_ANIM_MS)
        assertEquals(SpringAnimatorHelper.LYRIC_SCROLL_DURATION_MS, LyricsRecyclerAdapter.SCROLL_DURATION_MS)
        assertEquals(SpringAnimatorHelper.LYRIC_SCALE_DURATION_MS, LyricsRecyclerAdapter.SCALE_ANIM_MS)
    }

    @Test
    fun mainActivity_exposesOpenNowPlayingExtraKey() {
        assertEquals("com.laguku.player.EXTRA_OPEN_NOW_PLAYING", MainActivity.EXTRA_OPEN_NOW_PLAYING)
    }
}
