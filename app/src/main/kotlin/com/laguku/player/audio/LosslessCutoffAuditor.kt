package com.laguku.player.audio

import com.laguku.player.model.LosslessAuditStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * Lightweight 4-bin Goertzel spectral cutoff auditor running over the first 3 seconds
 * of lossless PCM playback (10 kHz, 16 kHz, 18 kHz, 20 kHz) to detect upscaled lossy rips
 * without heavy FFT allocation. Zero allocations occur in [feedInterleaved].
 */
class LosslessCutoffAuditor {

    private var sampleRateHz: Int = 44_100
    private var targetFrames: Int = 44_100 * 3
    private var framesAnalyzed: Int = 0
    private var blockSampleCount: Int = 0
    private var status: LosslessAuditStatus = LosslessAuditStatus.NOT_APPLICABLE

    private val coeffs = FloatArray(BIN_COUNT)
    private val s1 = FloatArray(BIN_COUNT)
    private val s2 = FloatArray(BIN_COUNT)
    private val accumEnergy = FloatArray(BIN_COUNT)
    private var completedBlocks: Int = 0
    private val hannWindow = FloatArray(BLOCK_SIZE) { n ->
        (0.5 * (1.0 - cos(2.0 * PI * n / (BLOCK_SIZE - 1).toDouble()))).toFloat()
    }

    fun reset(codec: String, sampleRateHz: Int) {
        val label = codec.trim().uppercase()
        this.sampleRateHz = sampleRateHz.coerceAtLeast(8_000)
        this.targetFrames = this.sampleRateHz * AUDIT_WINDOW_SECONDS
        this.framesAnalyzed = 0
        this.blockSampleCount = 0
        this.completedBlocks = 0
        for (i in 0 until BIN_COUNT) {
            s1[i] = 0f
            s2[i] = 0f
            accumEnergy[i] = 0f
            val freq = TARGET_FREQS_HZ[i]
            if (freq < this.sampleRateHz * 0.49f) {
                val omega = 2.0 * PI * freq / this.sampleRateHz.toDouble()
                coeffs[i] = (2.0 * cos(omega)).toFloat()
            } else {
                coeffs[i] = 0f
            }
        }
        status = if (label in LOSSLESS_CODECS && this.sampleRateHz >= 44_100) {
            LosslessAuditStatus.PENDING
        } else {
            LosslessAuditStatus.NOT_APPLICABLE
        }
    }

    fun getStatus(): LosslessAuditStatus = status

    fun feedInterleaved(samples: FloatArray, frameCount: Int, channels: Int): LosslessAuditStatus {
        if (status != LosslessAuditStatus.PENDING || frameCount <= 0 || channels <= 0) {
            return status
        }
        val totalSamples = frameCount * channels
        if (totalSamples > samples.size) return status

        var idx = 0
        for (f in 0 until frameCount) {
            if (framesAnalyzed >= targetFrames) break
            val mono = (if (channels == 1) {
                samples[idx]
            } else {
                (samples[idx] + samples[idx + 1]) * 0.5f
            }) * hannWindow[blockSampleCount]
            idx += channels

            for (b in 0 until BIN_COUNT) {
                val c = coeffs[b]
                if (c == 0f) continue
                val s0 = mono + c * s1[b] - s2[b]
                s2[b] = s1[b]
                s1[b] = s0
            }

            framesAnalyzed++
            blockSampleCount++
            if (blockSampleCount >= BLOCK_SIZE) {
                flushBlockEnergy()
            }
        }

        if (framesAnalyzed >= targetFrames) {
            finalizeAudit()
        }
        return status
    }

    fun finalizeAudit(): LosslessAuditStatus {
        if (status != LosslessAuditStatus.PENDING) return status
        if (blockSampleCount >= MIN_FINAL_BLOCK_SAMPLES) {
            flushBlockEnergy()
        }
        if (completedBlocks == 0) {
            status = LosslessAuditStatus.GENUINE_LOSSLESS
            return status
        }

        val e10 = accumEnergy[0]
        val e16 = accumEnergy[1]
        val e18 = accumEnergy[2]
        val e19 = accumEnergy[3]
        val e20 = accumEnergy[4]

        val refEnergy = max(e10, e16)
        if (refEnergy < MIN_REF_ENERGY) {
            status = LosslessAuditStatus.GENUINE_LOSSLESS
            return status
        }

        val highEnergy = max(e18, max(e19, e20))
        val ratio = highEnergy / (refEnergy + 1e-12f)
        status = if (ratio < UPSCALE_CLIFF_RATIO && e10 > MIN_REF_ENERGY * 0.2f) {
            LosslessAuditStatus.SUSPECTED_UPSCALE
        } else {
            LosslessAuditStatus.GENUINE_LOSSLESS
        }
        return status
    }

    private fun flushBlockEnergy() {
        val norm = 1.0f / (blockSampleCount.toFloat() * blockSampleCount.toFloat()).coerceAtLeast(1f)
        for (b in 0 until BIN_COUNT) {
            val c = coeffs[b]
            if (c != 0f) {
                val v1 = s1[b]
                val v2 = s2[b]
                val power = ((v1 * v1) + (v2 * v2) - (c * v1 * v2)).coerceAtLeast(0f) * norm
                accumEnergy[b] += power
            }
            s1[b] = 0f
            s2[b] = 0f
        }
        blockSampleCount = 0
        completedBlocks++
    }

    companion object {
        private const val BIN_COUNT = 5
        private const val BLOCK_SIZE = 512
        private const val MIN_FINAL_BLOCK_SAMPLES = 128
        private const val AUDIT_WINDOW_SECONDS = 3
        private const val MIN_REF_ENERGY = 1e-6f
        private const val UPSCALE_CLIFF_RATIO = 1e-5f // ~ -50 dB drop above 16 kHz

        private val TARGET_FREQS_HZ = floatArrayOf(10_000f, 16_000f, 18_000f, 19_000f, 20_000f)
        private val LOSSLESS_CODECS = setOf("FLAC", "ALAC", "WAV", "PCM", "AIFF")
    }
}
