package com.laguku.player

import com.laguku.player.ui.motion.SpringAnimatorHelper
import com.laguku.player.ui.motion.SpringAnimatorHelper.GelPhysicsMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassMotionTest {

    @Test
    fun iOS26SpringTokens_convergeAt60And120Hz() {
        val profiles = listOf(
            SpringAnimatorHelper.SPRING_SNAPPY,
            SpringAnimatorHelper.SPRING_SMOOTH,
            SpringAnimatorHelper.SPRING_BOUNCY,
            SpringAnimatorHelper.SPRING_REDUCED_MOTION
        )
        assertTrue(SpringAnimatorHelper.SPRING_SNAPPY.stiffness > SpringAnimatorHelper.SPRING_SMOOTH.stiffness)
        assertEquals(0.82f, SpringAnimatorHelper.SPRING_SNAPPY.dampingRatio, 0.0001f)
        assertEquals(0.88f, SpringAnimatorHelper.SPRING_SMOOTH.dampingRatio, 0.0001f)
        assertEquals(0.68f, SpringAnimatorHelper.SPRING_BOUNCY.dampingRatio, 0.0001f)
        assertEquals(1.0f, SpringAnimatorHelper.SPRING_REDUCED_MOTION.dampingRatio, 0.0001f)

        for (profile in profiles) {
            for (dt in listOf(1f / 60f, 1f / 120f)) {
                val solver = SpringAnimatorHelper.SpringSolver(
                    value = 0f,
                    target = 1f,
                    velocity = 120f,
                    stiffness = profile.stiffness,
                    dampingRatio = profile.dampingRatio
                )
                var settled = false
                var ticks = 0
                while (!settled && ticks < 500) {
                    settled = solver.step(dt)
                    ticks++
                }
                assertTrue("Spring profile $profile failed to settle at dt=$dt", settled)
                assertEquals(1f, solver.value, 0.0001f)
                assertEquals(0f, solver.velocity, 0.0001f)
            }
        }
    }

    @Test
    fun constantVolumeGelDeformation_conservesAreaAndRespectsReducedMotion() {
        assertEquals(1.06f, GelPhysicsMath.ACTIVE_LIFT_SCALE, 0.0001f)
        assertEquals(1.24f, GelPhysicsMath.MAX_VELOCITY_STRETCH, 0.0001f)

        val idleStretch = GelPhysicsMath.computeConstantVolumeStretch(0f)
        val midStretch = GelPhysicsMath.computeConstantVolumeStretch(400f)
        val maxStretch = GelPhysicsMath.computeConstantVolumeStretch(5_000f)

        assertEquals(1f, idleStretch, 0.0001f)
        assertEquals(1.14f, midStretch, 0.0001f)
        assertEquals(1.24f, maxStretch, 0.0001f)

        val midSquash = GelPhysicsMath.computeConstantVolumeSquash(midStretch)
        val maxSquash = GelPhysicsMath.computeConstantVolumeSquash(maxStretch)
        // Constant-volume invariant: stretch * squash^2 == 1.0
        assertEquals(1f, midStretch * midSquash * midSquash, 0.001f)
        assertEquals(1f, maxStretch * maxSquash * maxSquash, 0.001f)

        // Reduced motion disables gel stretch completely
        assertEquals(1f, GelPhysicsMath.computeConstantVolumeStretch(2_000f, reducedMotion = true), 0.0001f)
    }

    @Test
    fun neighborLightBleed_decaysExponentiallyUpTo140PercentRadius() {
        val controlSize = 100f
        val atCenter = GelPhysicsMath.computeNeighborBleedIntensity(0f, controlSize)
        val atHalf = GelPhysicsMath.computeNeighborBleedIntensity(50f, controlSize)
        val atControlEdge = GelPhysicsMath.computeNeighborBleedIntensity(100f, controlSize)
        val atBoundary = GelPhysicsMath.computeNeighborBleedIntensity(140f, controlSize)
        val beyond = GelPhysicsMath.computeNeighborBleedIntensity(180f, controlSize)

        assertEquals(1f, atCenter, 0.0001f)
        assertTrue(atHalf in 0.10f..0.40f)
        assertTrue(atControlEdge > 0f && atControlEdge < atHalf)
        assertEquals(0f, atBoundary, 0.0001f)
        assertEquals(0f, beyond, 0.0001f)

        // Reduced motion disables neighbor bleed
        assertEquals(0f, GelPhysicsMath.computeNeighborBleedIntensity(20f, controlSize, reducedMotion = true), 0.0001f)
    }
}
