package com.laguku.player.audio

import com.laguku.player.model.AudioDspConfig
import com.laguku.player.model.LosslessAuditStatus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure Kotlin zero-allocation PCM DSP chain coordinating:
 * - 10-band Biquad EQ
 * - Stereo Crossfeed (~300us ring-buffer delay + 700 Hz 1-pole LPF)
 * - Smart Loudness Leveler (adaptive RMS when ReplayGain tag is absent)
 * - Auto-Trim Silence (-65 dB leading/trailing)
 * - L/R Balance & Mono Downmix
 * - Seamless Fade-In / Fade-Out / Morning Alarm Ramp
 * - Bluetooth wake-up silent pre-roll (~150ms)
 * - Normalized PCM Ring Visualizer feed
 */
class AudioDspChain {

    val eq = TenBandBiquadEq()
    val auditor = LosslessCutoffAuditor()

    private var sampleRateHz: Int = 48_000
    private var trackReplayGainLinear: Float? = null

    private var crossfeedLevel: Float = 0f
    private var crossfeedDelaySamples: Int = 14
    private var crossfeedLpAlpha: Float = 0.087f
    private val delayBufL = FloatArray(CROSSFEED_BUF_SIZE)
    private val delayBufR = FloatArray(CROSSFEED_BUF_SIZE)
    private var delayPos: Int = 0
    private var lpL: Float = 0f
    private var lpR: Float = 0f

    private var smartLoudnessEnabled: Boolean = false
    private var rmsSq: Float = TARGET_RMS * TARGET_RMS
    private var currentLevelGain: Float = 1.0f

    private var autoTrimSilenceEnabled: Boolean = false
    private var leadingSilenceActive: Boolean = false
    private var leadingSilenceFramesChecked: Int = 0
    private var maxLeadingSilenceFrames: Int = 48_000 * 3

    private var balance: Float = 0f
    private var monoDownmix: Boolean = false
    private var btPreRollEnabled: Boolean = true
    private var btPreRollFramesRemaining: Int = 0

    // Seamless fade state (in frames)
    private var fadeGain: Float = 1.0f
    private var fadeStep: Float = 0f
    private var fadeTarget: Float = 1.0f
    private var fadeFramesRemaining: Int = 0
    private var fadingOut: Boolean = false

    @Volatile
    var visualizerEnabled: Boolean = false

    @Volatile
    var visualizerLevel: Float = 0f
        private set

    fun configure(sampleRateHz: Int, codec: String, replayGainDb: Float?) {
        val safeRate = sampleRateHz.coerceIn(8_000, 384_000)
        this.sampleRateHz = safeRate
        eq.configure(safeRate)
        auditor.reset(codec, safeRate)
        trackReplayGainLinear = if (replayGainDb != null && replayGainDb.isFinite()) {
            AudioEngine.computeReplayGainLinear(replayGainDb)
        } else {
            null
        }
        crossfeedDelaySamples = (safeRate * CROSSFEED_DELAY_SEC).toInt().coerceIn(4, CROSSFEED_BUF_SIZE - 1)
        crossfeedLpAlpha = (1.0 - exp(-2.0 * PI * CROSSFEED_LP_HZ / safeRate.toDouble())).toFloat()
        maxLeadingSilenceFrames = safeRate * MAX_TRIM_WINDOW_SEC
        resetTrackTransientState()
    }

    fun resetTrackTransientState() {
        for (i in 0 until CROSSFEED_BUF_SIZE) {
            delayBufL[i] = 0f
            delayBufR[i] = 0f
        }
        delayPos = 0
        lpL = 0f
        lpR = 0f
        rmsSq = TARGET_RMS * TARGET_RMS
        currentLevelGain = trackReplayGainLinear ?: 1.0f
        leadingSilenceActive = autoTrimSilenceEnabled
        leadingSilenceFramesChecked = 0
        eq.resetState()
    }

    fun applyConfig(config: AudioDspConfig) {
        eq.setEnabled(config.eqEnabled)
        eq.setAllBandsDb(config.eqBandsDb)
        crossfeedLevel = config.crossfeedLevel.coerceIn(0f, 1f)
        smartLoudnessEnabled = config.smartLoudnessEnabled
        autoTrimSilenceEnabled = config.autoTrimSilenceEnabled
        balance = config.balance.coerceIn(-1f, 1f)
        monoDownmix = config.monoDownmix
        btPreRollEnabled = config.btPreRollEnabled
    }

    fun setCrossfeedLevel(level: Float) {
        crossfeedLevel = level.coerceIn(0f, 1f)
    }

    fun getCrossfeedLevel(): Float = crossfeedLevel

    fun setSmartLoudnessEnabled(enabled: Boolean) {
        smartLoudnessEnabled = enabled
    }

    fun setAutoTrimSilenceEnabled(enabled: Boolean) {
        autoTrimSilenceEnabled = enabled
        if (!enabled) leadingSilenceActive = false
    }

    fun isAutoTrimSilenceEnabled(): Boolean = autoTrimSilenceEnabled

    fun setBalance(balance: Float) {
        this.balance = balance.coerceIn(-1f, 1f)
    }

    fun setMonoDownmix(enabled: Boolean) {
        monoDownmix = enabled
    }

    fun setBtPreRollEnabled(enabled: Boolean) {
        btPreRollEnabled = enabled
        if (!enabled) btPreRollFramesRemaining = 0
    }

    fun triggerBluetoothPreRoll(durationMs: Int = DEFAULT_BT_PREROLL_MS) {
        if (!btPreRollEnabled || durationMs <= 0) return
        btPreRollFramesRemaining = ((sampleRateHz.toLong() * durationMs) / 1000L).toInt().coerceAtLeast(0)
    }

    fun hasPendingPreRoll(): Boolean = btPreRollFramesRemaining > 0

    fun fillPreRollSilence(out: FloatArray, maxFrames: Int, channels: Int): Int {
        if (btPreRollFramesRemaining <= 0 || maxFrames <= 0 || channels <= 0) return 0
        val frames = min(btPreRollFramesRemaining, min(maxFrames, out.size / channels))
        val count = frames * channels
        for (i in 0 until count) {
            out[i] = 0f
        }
        btPreRollFramesRemaining -= frames
        return frames
    }

    fun triggerFadeIn(durationMs: Int = DEFAULT_FADE_IN_MS) {
        val frames = ((sampleRateHz.toLong() * durationMs) / 1000L).toInt().coerceAtLeast(1)
        fadeGain = 0f
        fadeTarget = 1.0f
        fadeStep = 1.0f / frames.toFloat()
        fadeFramesRemaining = frames
        fadingOut = false
    }

    fun triggerFadeOut(durationMs: Int = DEFAULT_FADE_OUT_MS) {
        val frames = ((sampleRateHz.toLong() * durationMs) / 1000L).toInt().coerceAtLeast(1)
        fadeTarget = 0f
        fadeStep = -fadeGain / frames.toFloat()
        fadeFramesRemaining = frames
        fadingOut = true
    }

    fun triggerVolumeRamp(durationMs: Int) {
        val frames = ((sampleRateHz.toLong() * durationMs.coerceAtLeast(1)) / 1000L).toInt().coerceAtLeast(1)
        fadeGain = 0f
        fadeTarget = 1.0f
        fadeStep = 1.0f / frames.toFloat()
        fadeFramesRemaining = frames
        fadingOut = false
    }

    fun cancelFade() {
        fadeGain = 1.0f
        fadeTarget = 1.0f
        fadeStep = 0f
        fadeFramesRemaining = 0
        fadingOut = false
    }

    fun isFadeOutComplete(): Boolean = fadingOut && fadeFramesRemaining <= 0 && fadeGain <= 0.001f

    fun getCurrentFadeGain(): Float = fadeGain

    /**
     * Returns number of leading silent frames (< -65 dB) to skip at the start of a track.
     * Stops inspecting once non-silent audio or 3-second cap is reached.
     */
    fun countLeadingSilentFrames(samples: FloatArray, frameCount: Int, channels: Int): Int {
        if (!autoTrimSilenceEnabled || !leadingSilenceActive || frameCount <= 0 || channels <= 0) return 0
        var silentFrames = 0
        var idx = 0
        for (f in 0 until frameCount) {
            if (leadingSilenceFramesChecked >= maxLeadingSilenceFrames) {
                leadingSilenceActive = false
                break
            }
            var peak = 0f
            for (ch in 0 until channels) {
                val a = abs(samples[idx + ch])
                if (a > peak) peak = a
            }
            if (peak >= SILENCE_THRESHOLD_LINEAR) {
                leadingSilenceActive = false
                break
            }
            silentFrames++
            leadingSilenceFramesChecked++
            idx += channels
        }
        return silentFrames
    }

    fun isBlockSilent(samples: FloatArray, frameCount: Int, channels: Int): Boolean {
        if (frameCount <= 0 || channels <= 0) return true
        val total = min(samples.size, frameCount * channels)
        for (i in 0 until total) {
            if (abs(samples[i]) >= SILENCE_THRESHOLD_LINEAR) return false
        }
        return true
    }

    fun processInterleaved(samples: FloatArray, frameCount: Int, channels: Int): LosslessAuditStatus {
        if (frameCount <= 0 || channels <= 0) return auditor.getStatus()
        val totalSamples = frameCount * channels
        if (totalSamples > samples.size) return auditor.getStatus()

        // 1. Audit lossless spectral cutoff before EQ/crossfeed coloration
        val auditStatus = auditor.feedInterleaved(samples, frameCount, channels)

        // 2. 10-band cascaded biquad EQ
        eq.processInterleaved(samples, frameCount, channels)

        // 3. Stereo Crossfeed + Smart Loudness / ReplayGain + Balance + Mono + Fade + Visualizer
        val applyCrossfeed = channels == 2 && crossfeedLevel > 0.001f
        val cfMix = crossfeedLevel * 0.35f
        val cfNorm = if (applyCrossfeed) 1.0f / (1.0f + cfMix) else 1.0f

        val fixedRg = trackReplayGainLinear
        val useAdaptiveRms = fixedRg == null && smartLoudnessEnabled
        val staticGain = fixedRg ?: 1.0f

        val leftBalGain = if (balance > 0f) (1.0f - balance).coerceIn(0f, 1f) else 1.0f
        val rightBalGain = if (balance < 0f) (1.0f + balance).coerceIn(0f, 1f) else 1.0f
        val applyBalance = channels >= 2 && abs(balance) > 0.001f

        val trackVis = visualizerEnabled
        var visSumSq = 0f

        var idx = 0
        for (f in 0 until frameCount) {
            val fGain = nextFadeGain()
            if (channels == 1) {
                var s = samples[idx]
                val levelGain = if (useAdaptiveRms) nextRmsGain(s) else staticGain
                s = TenBandBiquadEq.softClip(s * levelGain * fGain)
                samples[idx] = s
                if (trackVis) visSumSq += s * s
                idx++
            } else {
                var l = samples[idx]
                var r = samples[idx + 1]

                if (applyCrossfeed) {
                    val readPos = (delayPos - crossfeedDelaySamples + CROSSFEED_BUF_SIZE) % CROSSFEED_BUF_SIZE
                    val delayedL = delayBufL[readPos]
                    val delayedR = delayBufR[readPos]
                    delayBufL[delayPos] = l
                    delayBufR[delayPos] = r
                    delayPos = (delayPos + 1) % CROSSFEED_BUF_SIZE

                    lpL += crossfeedLpAlpha * (delayedR - lpL)
                    lpR += crossfeedLpAlpha * (delayedL - lpR)
                    l = (l + lpL * cfMix) * cfNorm
                    r = (r + lpR * cfMix) * cfNorm
                }

                if (monoDownmix) {
                    val mono = (l + r) * 0.5f
                    l = mono
                    r = mono
                }

                val monoSample = (l + r) * 0.5f
                val levelGain = if (useAdaptiveRms) nextRmsGain(monoSample) else staticGain

                if (applyBalance) {
                    l *= leftBalGain
                    r *= rightBalGain
                }

                l = TenBandBiquadEq.softClip(l * levelGain * fGain)
                r = TenBandBiquadEq.softClip(r * levelGain * fGain)
                samples[idx] = l
                samples[idx + 1] = r

                if (trackVis) {
                    val m = (l + r) * 0.5f
                    visSumSq += m * m
                }

                for (ch in 2 until channels) {
                    samples[idx + ch] = TenBandBiquadEq.softClip(samples[idx + ch] * levelGain * fGain)
                }
                idx += channels
            }
        }

        if (trackVis) {
            val rms = sqrt((visSumSq / frameCount.toFloat()).toDouble()).toFloat()
            val normalized = (rms * 2.6f).coerceIn(0f, 1f)
            val prev = visualizerLevel
            val coeff = if (normalized > prev) 0.45f else 0.18f
            visualizerLevel = prev + (normalized - prev) * coeff
        } else if (visualizerLevel != 0f) {
            visualizerLevel = 0f
        }

        return auditStatus
    }

    private fun nextFadeGain(): Float {
        if (fadeFramesRemaining <= 0) return fadeGain
        fadeGain += fadeStep
        fadeFramesRemaining--
        if (fadeFramesRemaining <= 0) {
            fadeGain = fadeTarget
            fadeStep = 0f
        } else {
            fadeGain = fadeGain.coerceIn(0f, 1f)
        }
        return fadeGain
    }

    private fun nextRmsGain(mono: Float): Float {
        rmsSq += RMS_ALPHA * (mono * mono - rmsSq)
        val rms = sqrt(rmsSq.toDouble()).toFloat().coerceAtLeast(0.01f)
        val target = (TARGET_RMS / rms).coerceIn(0.25f, 3.0f)
        currentLevelGain += GAIN_SMOOTH_ALPHA * (target - currentLevelGain)
        return currentLevelGain
    }

    companion object {
        const val DEFAULT_FADE_IN_MS = 280
        const val DEFAULT_FADE_OUT_MS = 220
        const val DEFAULT_BT_PREROLL_MS = 150
        const val SHUFFLE_CROSSFADE_MS = 1800
        const val SILENCE_THRESHOLD_LINEAR = 0.00056234f // -65 dBFS

        private const val CROSSFEED_BUF_SIZE = 128
        private const val CROSSFEED_DELAY_SEC = 0.0003f
        private const val CROSSFEED_LP_HZ = 700.0
        private const val MAX_TRIM_WINDOW_SEC = 3
        private const val TARGET_RMS = 0.20f // ~ -14 dBFS
        private const val RMS_ALPHA = 0.0008f
        private const val GAIN_SMOOTH_ALPHA = 0.0004f
    }
}
