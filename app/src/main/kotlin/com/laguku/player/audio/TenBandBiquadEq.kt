package com.laguku.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 10-band cascaded Peaking Biquad IIR Equalizer using Robert Bristow-Johnson's Audio EQ Cookbook
 * in Transposed Direct Form II with automatic preamp headroom compensation and soft clipping.
 * Zero allocations occur in [processInterleaved].
 */
class TenBandBiquadEq {

    private var sampleRateHz: Int = 48_000
    private var enabled: Boolean = false
    private var autoPreampEnabled: Boolean = true
    private var anyBandActive: Boolean = false
    private var preampLinear: Float = 1.0f

    private val gainsDb = FloatArray(BAND_COUNT)
    private val b0 = FloatArray(BAND_COUNT)
    private val b1 = FloatArray(BAND_COUNT)
    private val b2 = FloatArray(BAND_COUNT)
    private val a1 = FloatArray(BAND_COUNT)
    private val a2 = FloatArray(BAND_COUNT)
    private val bandActive = BooleanArray(BAND_COUNT)

    // Transposed Direct Form II delay states for L (ch 0) and R (ch 1)
    private val z1L = FloatArray(BAND_COUNT)
    private val z2L = FloatArray(BAND_COUNT)
    private val z1R = FloatArray(BAND_COUNT)
    private val z2R = FloatArray(BAND_COUNT)

    init {
        recomputeAllCoefficients()
    }

    fun configure(sampleRateHz: Int) {
        val safeRate = sampleRateHz.coerceIn(8_000, 384_000)
        if (this.sampleRateHz != safeRate) {
            this.sampleRateHz = safeRate
            recomputeAllCoefficients()
            resetState()
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled != enabled) {
            this.enabled = enabled
            if (!enabled) resetState()
        }
    }

    fun isEnabled(): Boolean = enabled

    fun setAutoPreampEnabled(enabled: Boolean) {
        if (autoPreampEnabled != enabled) {
            autoPreampEnabled = enabled
            recomputePreamp()
        }
    }

    fun isAutoPreampEnabled(): Boolean = autoPreampEnabled

    fun getPreampLinear(): Float = preampLinear

    fun setBandGainDb(band: Int, gainDb: Float) {
        if (band !in 0 until BAND_COUNT) return
        val clamped = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        if (gainsDb[band] != clamped) {
            gainsDb[band] = clamped
            recomputeBand(band)
            recomputePreamp()
        }
    }

    fun setAllBandsDb(gains: FloatArray) {
        val count = minOf(BAND_COUNT, gains.size)
        for (i in 0 until count) {
            gainsDb[i] = gains[i].coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        }
        recomputeAllCoefficients()
    }

    fun getBandGainDb(band: Int): Float = if (band in 0 until BAND_COUNT) gainsDb[band] else 0f

    fun getBandsDb(out: FloatArray) {
        val count = minOf(BAND_COUNT, out.size)
        for (i in 0 until count) {
            out[i] = gainsDb[i]
        }
    }

    fun applyPreset(presetName: String): Boolean {
        val preset = PRESETS[presetName] ?: return false
        setAllBandsDb(preset)
        return true
    }

    fun resetState() {
        for (i in 0 until BAND_COUNT) {
            z1L[i] = 0f
            z2L[i] = 0f
            z1R[i] = 0f
            z2R[i] = 0f
        }
    }

    fun processInterleaved(samples: FloatArray, frameCount: Int, channels: Int) {
        if (!enabled || !anyBandActive || frameCount <= 0 || channels <= 0) return
        val totalSamples = frameCount * channels
        if (totalSamples > samples.size) return

        val preamp = preampLinear
        if (channels == 1) {
            for (i in 0 until frameCount) {
                var x = samples[i] * preamp
                for (b in 0 until BAND_COUNT) {
                    if (!bandActive[b]) continue
                    val y = b0[b] * x + z1L[b]
                    z1L[b] = b1[b] * x - a1[b] * y + z2L[b]
                    z2L[b] = b2[b] * x - a2[b] * y
                    x = y
                }
                samples[i] = softClip(x)
            }
            return
        }

        if (channels == 2) {
            var idx = 0
            for (f in 0 until frameCount) {
                var xL = samples[idx] * preamp
                var xR = samples[idx + 1] * preamp
                for (b in 0 until BAND_COUNT) {
                    if (!bandActive[b]) continue
                    val cb0 = b0[b]
                    val cb1 = b1[b]
                    val cb2 = b2[b]
                    val ca1 = a1[b]
                    val ca2 = a2[b]

                    val yL = cb0 * xL + z1L[b]
                    z1L[b] = cb1 * xL - ca1 * yL + z2L[b]
                    z2L[b] = cb2 * xL - ca2 * yL
                    xL = yL

                    val yR = cb0 * xR + z1R[b]
                    z1R[b] = cb1 * xR - ca1 * yR + z2R[b]
                    z2R[b] = cb2 * xR - ca2 * yR
                    xR = yR
                }
                samples[idx] = softClip(xL)
                samples[idx + 1] = softClip(xR)
                idx += 2
            }
            return
        }

        // Multichannel fallback: apply preamp and softClip cleanly across all channels, filter L/R
        var idx = 0
        for (f in 0 until frameCount) {
            var xL = samples[idx] * preamp
            var xR = samples[idx + 1] * preamp
            for (b in 0 until BAND_COUNT) {
                if (!bandActive[b]) continue
                val yL = b0[b] * xL + z1L[b]
                z1L[b] = b1[b] * xL - a1[b] * yL + z2L[b]
                z2L[b] = b2[b] * xL - a2[b] * yL
                xL = yL

                val yR = b0[b] * xR + z1R[b]
                z1R[b] = b1[b] * xR - a1[b] * yR + z2R[b]
                z2R[b] = b2[b] * xR - a2[b] * yR
                xR = yR
            }
            samples[idx] = softClip(xL)
            samples[idx + 1] = softClip(xR)
            for (ch in 2 until channels) {
                samples[idx + ch] = softClip(samples[idx + ch] * preamp)
            }
            idx += channels
        }
    }

    /**
     * Maps the 10-band curve onto Android's system 5-band Equalizer (used only on MediaPlayer fallback)
     * by interpolating gains in log-frequency space and clamping to [minMb, maxMb].
     */
    fun mapToSystemEqBands(systemCentersMilliHz: IntArray, minMb: Short, maxMb: Short): ShortArray {
        val result = ShortArray(systemCentersMilliHz.size)
        for (i in systemCentersMilliHz.indices) {
            val hz = (systemCentersMilliHz[i] / 1000f).coerceAtLeast(20f)
            val db = interpolateGainAtHz(hz)
            val mb = (db * 100f).roundToInt().coerceIn(minMb.toInt(), maxMb.toInt())
            result[i] = mb.toShort()
        }
        return result
    }

    private fun interpolateGainAtHz(hz: Float): Float {
        if (hz <= BAND_FREQUENCIES_HZ[0]) return gainsDb[0]
        val last = BAND_COUNT - 1
        if (hz >= BAND_FREQUENCIES_HZ[last]) return gainsDb[last]
        val logHz = ln(hz.toDouble())
        for (i in 0 until last) {
            val f0 = BAND_FREQUENCIES_HZ[i]
            val f1 = BAND_FREQUENCIES_HZ[i + 1]
            if (hz <= f1) {
                val l0 = ln(f0.toDouble())
                val l1 = ln(f1.toDouble())
                val t = ((logHz - l0) / (l1 - l0)).toFloat().coerceIn(0f, 1f)
                return gainsDb[i] + (gainsDb[i + 1] - gainsDb[i]) * t
            }
        }
        return 0f
    }

    private fun recomputeAllCoefficients() {
        for (i in 0 until BAND_COUNT) {
            recomputeBand(i)
        }
        recomputePreamp()
    }

    private fun recomputeBand(band: Int) {
        val fc = BAND_FREQUENCIES_HZ[band].toDouble()
        val fs = sampleRateHz.toDouble()
        val gain = gainsDb[band]
        if (fc >= fs * 0.45 || abs(gain) < 0.01f) {
            bandActive[band] = false
            b0[band] = 1f
            b1[band] = 0f
            b2[band] = 0f
            a1[band] = 0f
            a2[band] = 0f
            updateAnyBandActive()
            return
        }

        val a = 10.0.pow(gain / 40.0)
        val w0 = 2.0 * PI * fc / fs
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * DEFAULT_Q.toDouble())
        val a0 = 1.0 + alpha / a

        b0[band] = ((1.0 + alpha * a) / a0).toFloat()
        b1[band] = ((-2.0 * cosW0) / a0).toFloat()
        b2[band] = ((1.0 - alpha * a) / a0).toFloat()
        a1[band] = b1[band]
        a2[band] = ((1.0 - alpha / a) / a0).toFloat()
        bandActive[band] = true
        updateAnyBandActive()
    }

    private fun updateAnyBandActive() {
        var active = false
        for (i in 0 until BAND_COUNT) {
            if (bandActive[i]) {
                active = true
                break
            }
        }
        anyBandActive = active
    }

    private fun recomputePreamp() {
        if (!autoPreampEnabled) {
            preampLinear = 1.0f
            return
        }
        var maxBoost = 0f
        for (i in 0 until BAND_COUNT) {
            if (bandActive[i]) {
                maxBoost = max(maxBoost, gainsDb[i])
            }
        }
        preampLinear = if (maxBoost > 0f) {
            10.0.pow(-maxBoost.toDouble() / 20.0).toFloat()
        } else {
            1.0f
        }
    }

    companion object {
        const val BAND_COUNT = 10
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        const val DEFAULT_Q = 1.4142135f
        private const val SOFT_CLIP_KNEE = 0.90f
        private const val SOFT_CLIP_HEADROOM = 1.0f - SOFT_CLIP_KNEE

        val BAND_FREQUENCIES_HZ = floatArrayOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        val PRESET_NAMES = listOf("Flat", "Bass Boost", "Vocal", "Treble", "Acoustic", "Night")

        val PRESETS: Map<String, FloatArray> = mapOf(
            "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Bass Boost" to floatArrayOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 1f, 1f),
            "Vocal" to floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
            "Treble" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
            "Acoustic" to floatArrayOf(3f, 2f, 1f, 0f, 1f, 2f, 3f, 3f, 2f, 1f),
            "Night" to floatArrayOf(4f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 2f)
        )

        fun softClip(x: Float): Float {
            val ax = abs(x)
            if (ax <= SOFT_CLIP_KNEE) return x
            val excess = (ax - SOFT_CLIP_KNEE) / SOFT_CLIP_HEADROOM
            val shaped = SOFT_CLIP_KNEE + SOFT_CLIP_HEADROOM * tanh(excess.toDouble()).toFloat()
            return if (x >= 0f) shaped.coerceAtMost(1.0f) else (-shaped).coerceAtLeast(-1.0f)
        }
    }
}
