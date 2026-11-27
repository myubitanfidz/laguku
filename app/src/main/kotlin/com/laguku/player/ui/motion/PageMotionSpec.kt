package com.laguku.player.ui.motion

/**
 * Calibrated full-page transition, library stagger entrance, swipe commit-on-release,
 * and cover radial aura constants for Laguku.
 */
object PageMotionSpec {
    const val PAGE_MORPH_STIFFNESS = 160f
    const val PAGE_MORPH_DAMPING_RATIO = 0.68f
    const val LIBRARY_RECEDE_SCALE = 0.94f
    const val LIBRARY_STAGGER_STEP_MS = 35L
    const val LIBRARY_ROW_ENTRY_OFFSET_DP = 24f
    const val SWIPE_COMMIT_THRESHOLD_DP = 72f
    const val SWIPE_RUBBER_BAND_RESISTANCE = 0.68f
    const val COMMIT_ON_RELEASE_ONLY = true
    const val COVER_AURA_SCALE_MULTIPLIER = 1.60f
    val COVER_AURA_ALPHA_STOPS = floatArrayOf(0.24f, 0.09f, 0.02f, 0.00f)

    fun computeLibraryRecedeScale(openProgress: Float): Float {
        val t = openProgress.coerceIn(0f, 1f)
        return 1f - (1f - LIBRARY_RECEDE_SCALE) * t
    }

    fun computeStaggerDelayMs(visibleIndex: Int): Long =
        visibleIndex.coerceAtLeast(0).toLong() * LIBRARY_STAGGER_STEP_MS
}
