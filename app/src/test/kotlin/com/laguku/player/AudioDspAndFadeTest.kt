package com.laguku.player

import com.laguku.player.audio.AudioDspChain
import com.laguku.player.audio.AudioEngine
import com.laguku.player.audio.LosslessCutoffAuditor
import com.laguku.player.model.LosslessAuditStatus
import com.laguku.player.ui.glass.InteractiveGlassScrubView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioDspAndFadeTest {

    @Test
    fun crossfeedBleedsDelayedLowPassedSignalIntoOppositeChannel() {
        val chain = AudioDspChain()
        chain.configure(48_000, "FLAC", null)
        chain.setCrossfeedLevel(1.0f)

        val frames = 128
        val samples = FloatArray(frames * 2)
        // Impulse on Left channel at frame 0; Right channel strictly silent
        samples[0] = 0.8f
        chain.processInterleaved(samples, frames, 2)

        var maxRightAfterDelay = 0f
        for (f in 10 until frames) {
            val r = abs(samples[f * 2 + 1])
            if (r > maxRightAfterDelay) maxRightAfterDelay = r
        }
        assertTrue("Expected crossfeed bleed in R channel, got $maxRightAfterDelay", maxRightAfterDelay > 0.005f)
    }

    @Test
    fun smartLoudnessLiftsQuietTrackWhenReplayGainAbsentAndHonorsTagWhenPresent() {
        val chainNoRg = AudioDspChain()
        chainNoRg.configure(48_000, "MP3", null)
        chainNoRg.setSmartLoudnessEnabled(true)

        val frames = 24_000
        val quiet = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val s = 0.03f * sin(2.0 * PI * 440.0 * i / 48_000.0).toFloat()
            quiet[i * 2] = s
            quiet[i * 2 + 1] = s
        }
        chainNoRg.processInterleaved(quiet, frames, 2)
        val tailPeak = abs(quiet[(frames - 20) * 2])
        assertTrue("Expected quiet signal lifted > 0.04f, got $tailPeak", tailPeak > 0.04f)

        // With ReplayGain = -6.0 dB, leveler must defer to ReplayGain (~0.5x)
        val chainWithRg = AudioDspChain()
        chainWithRg.configure(48_000, "FLAC", -6.0f)
        chainWithRg.setSmartLoudnessEnabled(true)
        val fixedBuf = FloatArray(512 * 2) { 0.4f }
        chainWithRg.processInterleaved(fixedBuf, 512, 2)
        assertEquals(0.20f, fixedBuf[0], 0.02f)
    }

    @Test
    fun autoTrimSilenceCountsLeadingSilentFramesAndStopsAtSignal() {
        val chain = AudioDspChain()
        chain.setAutoTrimSilenceEnabled(true)
        chain.configure(48_000, "FLAC", null)

        val buf = FloatArray(100 * 2)
        for (f in 0 until 40) {
            buf[f * 2] = 0.0001f // < -65 dB
            buf[f * 2 + 1] = -0.0001f
        }
        for (f in 40 until 100) {
            buf[f * 2] = 0.25f
            buf[f * 2 + 1] = 0.25f
        }
        val skipped = chain.countLeadingSilentFrames(buf, 100, 2)
        assertEquals(40, skipped)
        assertTrue(chain.isBlockSilent(FloatArray(64) { 0.0001f }, 32, 2))
        assertFalse(chain.isBlockSilent(FloatArray(64) { 0.05f }, 32, 2))
    }

    @Test
    fun balanceAndMonoDownmixOperateInPlace() {
        val chain = AudioDspChain()
        chain.configure(48_000, "WAV", null)
        chain.setMonoDownmix(true)
        val monoBuf = floatArrayOf(0.8f, 0.2f, -0.4f, 0.8f)
        chain.processInterleaved(monoBuf, 2, 2)
        assertEquals(0.5f, monoBuf[0], 0.001f)
        assertEquals(0.5f, monoBuf[1], 0.001f)
        assertEquals(0.2f, monoBuf[2], 0.001f)
        assertEquals(0.2f, monoBuf[3], 0.001f)

        chain.setMonoDownmix(false)
        chain.setBalance(-1.0f) // Hard left -> Right silent
        val balBuf = floatArrayOf(0.6f, 0.6f, 0.6f, 0.6f)
        chain.processInterleaved(balBuf, 2, 2)
        assertEquals(0.6f, balBuf[0], 0.001f)
        assertEquals(0.0f, balBuf[1], 0.001f)
    }

    @Test
    fun visualizerFeedAndBtPreRollAndFadesWorkAsCalibrated() {
        val chain = AudioDspChain()
        chain.configure(48_000, "FLAC", null)

        // BT Pre-Roll: 150ms @ 48 kHz = 7200 frames
        chain.triggerBluetoothPreRoll(150)
        assertTrue(chain.hasPendingPreRoll())
        val scratch = FloatArray(4000 * 2) { 1.0f }
        val f1 = chain.fillPreRollSilence(scratch, 4000, 2)
        val f2 = chain.fillPreRollSilence(scratch, 4000, 2)
        assertEquals(4000, f1)
        assertEquals(3200, f2)
        assertFalse(chain.hasPendingPreRoll())
        assertEquals(0f, scratch[0], 0f)

        // Visualizer disabled -> 0
        chain.visualizerEnabled = false
        val tone = FloatArray(1024 * 2) { 0.35f }
        chain.processInterleaved(tone, 1024, 2)
        assertEquals(0f, chain.visualizerLevel, 0f)

        // Visualizer enabled -> > 0.1f
        chain.visualizerEnabled = true
        chain.processInterleaved(tone, 1024, 2)
        assertTrue(chain.visualizerLevel > 0.1f)

        // Fade-In 280ms
        chain.triggerFadeIn(280)
        val fadeInBuf = FloatArray(13_440 * 2) { 1.0f }
        chain.processInterleaved(fadeInBuf, 13_440, 2)
        assertTrue(fadeInBuf[0] < 0.01f)
        assertTrue(fadeInBuf[fadeInBuf.lastIndex] > 0.90f)

        // Fade-Out 220ms
        chain.triggerFadeOut(220)
        val fadeOutBuf = FloatArray(10_560 * 2) { 1.0f }
        chain.processInterleaved(fadeOutBuf, 10_560, 2)
        assertTrue(chain.isFadeOutComplete())
        assertEquals(0f, chain.getCurrentFadeGain(), 0.001f)
    }

    @Test
    fun losslessCutoffAuditorDistinguishesGenuineFromUpscaledAndLossy() {
        val sr = 44_100
        val frames = sr * 3
        val auditor = LosslessCutoffAuditor()

        // 1. Lossy MP3 -> NOT_APPLICABLE
        auditor.reset("MP3", sr)
        assertEquals(LosslessAuditStatus.NOT_APPLICABLE, auditor.getStatus())

        // 2. Genuine lossless: 10 kHz + 16 kHz + 19 kHz
        auditor.reset("FLAC", sr)
        val genuine = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val t = 2.0 * PI * i / sr.toDouble()
            val s = (0.25 * sin(10_000.0 * t) + 0.15 * sin(16_000.0 * t) + 0.10 * sin(19_000.0 * t)).toFloat()
            genuine[i * 2] = s
            genuine[i * 2 + 1] = s
        }
        val genuineStatus = auditor.feedInterleaved(genuine, frames, 2)
        assertEquals(LosslessAuditStatus.GENUINE_LOSSLESS, genuineStatus)

        // 3. Suspected upscale: 10 kHz + 15 kHz only (brickwall cutoff before 18/20 kHz)
        auditor.reset("FLAC", sr)
        val upscaled = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val t = 2.0 * PI * i / sr.toDouble()
            val s = (0.30 * sin(10_000.0 * t) + 0.20 * sin(15_000.0 * t)).toFloat()
            upscaled[i * 2] = s
            upscaled[i * 2 + 1] = s
        }
        val upscaledStatus = auditor.feedInterleaved(upscaled, frames, 2)
        assertEquals(LosslessAuditStatus.SUSPECTED_UPSCALE, upscaledStatus)

        // 4. Genuine lossless with 18 kHz + 20 kHz components
        auditor.reset("WAV", sr)
        val fullHi = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val t = 2.0 * PI * i / sr.toDouble()
            val s = (0.25 * sin(10_000.0 * t) + 0.12 * sin(18_000.0 * t) + 0.10 * sin(20_000.0 * t)).toFloat()
            fullHi[i * 2] = s
            fullHi[i * 2 + 1] = s
        }
        assertEquals(LosslessAuditStatus.GENUINE_LOSSLESS, auditor.feedInterleaved(fullHi, frames, 2))
    }

    @Test
    fun abLoopValidationEnforcesMinimumWindowAndBounds() {
        val dur = 120_000L
        fun normalizeAb(a: Long?, b: Long?, durationMs: Long): Pair<Long?, Long?> {
            val sa = a?.coerceIn(0L, durationMs)
            val sb = b?.coerceIn(0L, durationMs)
            val validB = if (sa != null && sb != null && sb > sa + 200L) sb else null
            return sa to validB
        }
        assertEquals(10_000L to 25_000L, normalizeAb(10_000L, 25_000L, dur))
        assertEquals(10_000L to null, normalizeAb(10_000L, 10_150L, dur))
        assertEquals(10_000L to null, normalizeAb(10_000L, 5_000L, dur))
        assertEquals(0L to 120_000L, normalizeAb(-500L, 999_999L, dur))
    }

    @Test
    fun wakeLockLeaseRenewsAtEightMinutesAndReleasesOnPauseOrStop() {
        var nowMs = 1_000L
        var acquireCount = 0
        var lastTimeout = 0L
        var releaseCount = 0

        val adapter = object : AudioEngine.WakeLockAdapter {
            override fun acquire(timeoutMs: Long) {
                acquireCount++
                lastTimeout = timeoutMs
            }

            override fun release() {
                releaseCount++
            }
        }
        val lease = AudioEngine.WakeLockLeaseManager(adapter = adapter, clockMs = { nowMs })

        // Initial acquire on playback start
        lease.acquireOrRenewNow()
        assertTrue(lease.isHeld)
        assertEquals(1, acquireCount)
        assertEquals(AudioEngine.WAKE_LOCK_TIMEOUT_MS, lastTimeout)

        // Before 8 minutes (479_999ms elapsed) -> no renewal
        nowMs = 1_000L + AudioEngine.WAKE_LOCK_RENEW_INTERVAL_MS - 1L
        lease.renewIfDue(isPlaying = true)
        assertEquals(1, acquireCount)

        // At 8 minutes (480_000ms elapsed) -> renews 10-minute timeout
        nowMs = 1_000L + AudioEngine.WAKE_LOCK_RENEW_INTERVAL_MS
        lease.renewIfDue(isPlaying = true)
        assertEquals(2, acquireCount)
        assertEquals(AudioEngine.WAKE_LOCK_TIMEOUT_MS, lastTimeout)

        // When paused, renewIfDue must not acquire
        nowMs += AudioEngine.WAKE_LOCK_RENEW_INTERVAL_MS * 2L
        lease.renewIfDue(isPlaying = false)
        assertEquals(2, acquireCount)

        // Pause / Stop / Release immediately drops wake lock (idempotent)
        lease.releaseNow()
        assertFalse(lease.isHeld)
        assertEquals(1, releaseCount)
        lease.releaseNow()
        assertEquals(1, releaseCount)
    }

    @Test
    fun abLoopAndVocalTempoMath_enforces500msSpanLoopJumpAndPauseSafety() {
        val math = InteractiveGlassScrubView.AbLoopAndTempoMath
        assertEquals(500L, math.MIN_AB_LOOP_SPAN_MS)
        assertEquals(listOf(0.75f, 0.90f, 1.00f, 1.10f, 1.25f), math.SUPPORTED_VOCAL_TEMPOS.toList())

        val equalBoundary = math.normalizeAbPoints(1_000L, 1_500L, 10_000L)
        assertEquals(1_000L, equalBoundary.first)
        assertEquals(null, equalBoundary.second)

        val validSpan = math.normalizeAbPoints(1_000L, 1_501L, 10_000L)
        assertEquals(1_000L, validSpan.first)
        assertEquals(1_501L, validSpan.second)
        assertTrue(math.isValidAbSpan(validSpan.first, validSpan.second, 10_000L))

        assertFalse(math.shouldTriggerLoopJump(1_500L, 1_000L, 1_501L))
        assertTrue(math.shouldTriggerLoopJump(1_501L, 1_000L, 1_501L))
        assertTrue(math.shouldTriggerLoopJump(1_750L, 1_000L, 1_501L))
        assertFalse(math.shouldTriggerLoopJump(2_000L, 1_000L, 1_300L))

        assertEquals(1.00f, math.normalizeVocalTempo(1.02f), 0.0001f)
        assertEquals(0.75f, math.normalizeVocalTempo(0.50f), 0.0001f)
        assertEquals(1.25f, math.normalizeVocalTempo(1.80f), 0.0001f)

        assertEquals(1.10f, math.nextVocalTempo(1.00f), 0.0001f)
        assertEquals(1.25f, math.nextVocalTempo(1.10f), 0.0001f)
        assertEquals(0.75f, math.nextVocalTempo(1.25f), 0.0001f)
        assertEquals(0.90f, math.nextVocalTempo(0.75f), 0.0001f)
        assertEquals(1.00f, math.nextVocalTempo(0.90f), 0.0001f)

        assertFalse(math.shouldApplyPlaybackParamsImmediately(isPlaying = false))
        assertTrue(math.shouldApplyPlaybackParamsImmediately(isPlaying = true))
    }
}
