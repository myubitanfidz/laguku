package com.laguku.player

import com.laguku.player.audio.TenBandBiquadEq
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class TenBandEqTest {

    private fun generateStereoSine(freqHz: Float, sampleRate: Int, frames: Int, amplitude: Float = 0.25f): FloatArray {
        val buf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val s = amplitude * sin(2.0 * PI * freqHz * i / sampleRate.toDouble()).toFloat()
            buf[i * 2] = s
            buf[i * 2 + 1] = s
        }
        return buf
    }

    private fun rmsLeftAfterWarmup(buf: FloatArray, warmupFrames: Int): Float {
        var sum = 0.0
        var count = 0
        for (f in warmupFrames until buf.size / 2) {
            val v = buf[f * 2].toDouble()
            sum += v * v
            count++
        }
        return sqrt(sum / count.coerceAtLeast(1)).toFloat()
    }

    @Test
    fun zeroDbAndDisabledEqProduceBitExactBypass() {
        val eq = TenBandBiquadEq()
        eq.configure(48_000)
        val input = generateStereoSine(1000f, 48_000, 2048, 0.4f)

        val disabledOut = input.copyOf()
        eq.setEnabled(false)
        eq.setBandGainDb(5, 9f)
        eq.processInterleaved(disabledOut, 2048, 2)
        assertArrayEquals(input, disabledOut, 0f)

        val flatOut = input.copyOf()
        eq.setEnabled(true)
        eq.setAllBandsDb(FloatArray(10))
        eq.processInterleaved(flatOut, 2048, 2)
        assertArrayEquals(input, flatOut, 0f)
    }

    @Test
    fun boostsAndCuts31HzAnd1kHzSinesAccurately() {
        val sr = 48_000
        val frames = 16_384
        val eq = TenBandBiquadEq().apply {
            configure(sr)
            setEnabled(true)
            setAutoPreampEnabled(false)
        }

        // 31 Hz boost (+9 dB) & cut (-9 dB)
        val base31 = generateStereoSine(31f, sr, frames, 0.2f)
        val ref31Rms = rmsLeftAfterWarmup(base31, 4096)

        val boosted31 = base31.copyOf()
        eq.setBandGainDb(0, 9f)
        eq.processInterleaved(boosted31, frames, 2)
        val boosted31Rms = rmsLeftAfterWarmup(boosted31, 4096)
        assertTrue("Expected 31Hz +9dB RMS > 2.2x ref, got ${boosted31Rms / ref31Rms}", boosted31Rms > ref31Rms * 2.2f)

        eq.resetState()
        val cut31 = base31.copyOf()
        eq.setBandGainDb(0, -9f)
        eq.processInterleaved(cut31, frames, 2)
        val cut31Rms = rmsLeftAfterWarmup(cut31, 4096)
        assertTrue("Expected 31Hz -9dB RMS < 0.45x ref, got ${cut31Rms / ref31Rms}", cut31Rms < ref31Rms * 0.45f)

        // 1 kHz (band 5) boost (+9 dB) & cut (-9 dB) while 31 Hz stays near unity
        eq.setAllBandsDb(FloatArray(10))
        eq.setBandGainDb(5, 9f)
        eq.resetState()
        val sine1k = generateStereoSine(1000f, sr, frames, 0.2f)
        val ref1kRms = rmsLeftAfterWarmup(sine1k, 2048)
        val boosted1k = sine1k.copyOf()
        eq.processInterleaved(boosted1k, frames, 2)
        val boosted1kRms = rmsLeftAfterWarmup(boosted1k, 2048)
        assertTrue("Expected 1kHz +9dB RMS > 2.3x ref", boosted1kRms > ref1kRms * 2.3f)

        eq.resetState()
        val unaffected31 = base31.copyOf()
        eq.processInterleaved(unaffected31, frames, 2)
        val unaffected31Rms = rmsLeftAfterWarmup(unaffected31, 4096)
        assertEquals(ref31Rms, unaffected31Rms, ref31Rms * 0.08f)
    }

    @Test
    fun preampAndSoftClipperPreventDigitalClippingAtMaxBoost() {
        val eq = TenBandBiquadEq().apply {
            configure(48_000)
            setEnabled(true)
            setAutoPreampEnabled(true)
            setAllBandsDb(FloatArray(10) { 12f })
        }
        assertTrue(eq.getPreampLinear() < 0.30f)

        val loud = generateStereoSine(1000f, 48_000, 4096, 0.98f)
        eq.processInterleaved(loud, 4096, 2)
        var maxPeak = 0f
        for (s in loud) {
            val a = abs(s)
            if (a > maxPeak) maxPeak = a
        }
        assertTrue("Peak must stay <= 1.0f, got $maxPeak", maxPeak <= 1.0f)
        assertTrue("Soft clipper direct check", TenBandBiquadEq.softClip(3.5f) <= 1.0f)
        assertTrue("Soft clipper negative check", TenBandBiquadEq.softClip(-3.5f) >= -1.0f)
    }

    @Test
    fun mapsTenBandCurveToSystemFiveBandEqualizer() {
        val eq = TenBandBiquadEq().apply {
            setBandGainDb(0, 6f)
            setBandGainDb(1, 6f)
            setBandGainDb(5, -3f)
            setBandGainDb(9, 9f)
        }
        val sysCenters = intArrayOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000)
        val mapped = eq.mapToSystemEqBands(sysCenters, -1500, 1500)
        assertEquals(5, mapped.size)
        assertTrue(mapped[0] > 300)
        assertTrue(mapped[2] < 0)
        assertTrue(mapped[4] > 600)
    }
}
