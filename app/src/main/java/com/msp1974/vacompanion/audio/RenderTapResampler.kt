package com.msp1974.vacompanion.audio

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Converts interleaved PCM16 playback audio to 16 kHz mono PCM16 for AEC render reference.
 *
 * Keeps streaming state across calls so chunked input has stable output pacing.
 */
internal class RenderTapResampler(
    private val targetSampleRateHz: Int = VacaAudioFormat.SAMPLE_RATE_HZ,
) {
    private var inputSampleRateHz = 0
    private var inputChannelCount = 0
    private var bytesPerSample = VacaAudioFormat.BYTES_PER_SAMPLE
    private var inputToOutputStep = 1.0
    private var nextOutputPos = 0.0
    private var lowPassEnabled = false
    private var lowPassAlpha = 1.0f
    private var lowPassState = 0.0f

    private var monoBuffer = FloatArray(0)
    private var monoBufferSize = 0
    private var outputByteBuffer = ByteArray(0)

    fun configure(sampleRateHz: Int, channelCount: Int, bytesPerSample: Int): Boolean {
        if (sampleRateHz <= 0 || channelCount <= 0 || bytesPerSample != VacaAudioFormat.BYTES_PER_SAMPLE) {
            return false
        }
        this.inputSampleRateHz = sampleRateHz
        this.inputChannelCount = channelCount
        this.bytesPerSample = bytesPerSample
        this.inputToOutputStep = sampleRateHz.toDouble() / targetSampleRateHz.toDouble()
        this.lowPassEnabled = sampleRateHz > targetSampleRateHz
        this.lowPassAlpha = if (lowPassEnabled) {
            val cutoffHz = targetSampleRateHz * 0.45
            val dt = 1.0 / sampleRateHz.toDouble()
            val rc = 1.0 / (2.0 * PI * cutoffHz)
            (dt / (rc + dt)).toFloat()
        } else {
            1.0f
        }
        resetState()
        return true
    }

    fun resetState() {
        nextOutputPos = 0.0
        monoBufferSize = 0
        lowPassState = 0.0f
    }

    fun clear() {
        inputSampleRateHz = 0
        inputChannelCount = 0
        bytesPerSample = VacaAudioFormat.BYTES_PER_SAMPLE
        inputToOutputStep = 1.0
        lowPassEnabled = false
        lowPassAlpha = 1.0f
        resetState()
        monoBuffer = FloatArray(0)
        outputByteBuffer = ByteArray(0)
    }

    fun process(inputBuffer: ByteBuffer): ByteArray? {
        if (!isConfigured() || !inputBuffer.hasRemaining()) return null
        val inputShorts = inputBuffer.asShortBuffer()
        val frameCount = inputShorts.remaining() / inputChannelCount
        if (frameCount <= 0) return null

        ensureMonoCapacity(monoBufferSize + frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (ch in 0 until inputChannelCount) {
                sum += inputShorts.get().toInt()
            }
            var mono = (sum / inputChannelCount).toFloat()
            if (lowPassEnabled) {
                lowPassState += lowPassAlpha * (mono - lowPassState)
                mono = lowPassState
            }
            monoBuffer[monoBufferSize++] = mono
        }
        return drainOutput(frameCount)
    }

    fun process(inputBytes: ByteArray): ByteArray? {
        if (!isConfigured()) return null
        val inputFrameBytes = inputChannelCount * bytesPerSample
        val frameCount = inputBytes.size / inputFrameBytes
        if (frameCount <= 0) return null

        ensureMonoCapacity(monoBufferSize + frameCount)
        var byteIndex = 0
        repeat(frameCount) {
            var sum = 0
            repeat(inputChannelCount) {
                val lo = inputBytes[byteIndex].toInt() and 0xFF
                val hi = inputBytes[byteIndex + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toInt()
                sum += sample
                byteIndex += 2
            }
            var mono = (sum / inputChannelCount).toFloat()
            if (lowPassEnabled) {
                lowPassState += lowPassAlpha * (mono - lowPassState)
                mono = lowPassState
            }
            monoBuffer[monoBufferSize++] = mono
        }
        return drainOutput(frameCount)
    }

    private fun drainOutput(frameCount: Int): ByteArray? {
        val estimatedOutputSamples =
            ((frameCount.toLong() * targetSampleRateHz) / inputSampleRateHz).toInt() + 4
        val requiredOutputBytes = estimatedOutputSamples * VacaAudioFormat.BYTES_PER_SAMPLE
        if (outputByteBuffer.size < requiredOutputBytes) {
            outputByteBuffer = ByteArray(requiredOutputBytes.coerceAtLeast(256))
        }

        var outIndex = 0
        while (nextOutputPos + 1.0 < monoBufferSize.toDouble()) {
            val leftIndex = nextOutputPos.toInt()
            val frac = (nextOutputPos - leftIndex).toFloat()
            val left = monoBuffer[leftIndex]
            val right = monoBuffer[leftIndex + 1]
            val sample = (left + ((right - left) * frac)).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

            outputByteBuffer[outIndex++] = (sample and 0xFF).toByte()
            outputByteBuffer[outIndex++] = ((sample ushr 8) and 0xFF).toByte()
            nextOutputPos += inputToOutputStep
        }

        val consumed = (nextOutputPos.toInt() - 1).coerceAtLeast(0)
        if (consumed > 0) {
            val remaining = monoBufferSize - consumed
            if (remaining > 0) {
                System.arraycopy(monoBuffer, consumed, monoBuffer, 0, remaining)
            }
            monoBufferSize = remaining
            nextOutputPos -= consumed.toDouble()
        }

        return if (outIndex > 0) outputByteBuffer.copyOf(outIndex) else null
    }

    private fun ensureMonoCapacity(requiredCapacity: Int) {
        if (monoBuffer.size >= requiredCapacity) return
        var newCapacity = if (monoBuffer.isEmpty()) 1024 else monoBuffer.size
        while (newCapacity < requiredCapacity) {
            newCapacity *= 2
        }
        monoBuffer = monoBuffer.copyOf(newCapacity)
    }

    private fun isConfigured(): Boolean =
        inputSampleRateHz > 0 && inputChannelCount > 0 && bytesPerSample == VacaAudioFormat.BYTES_PER_SAMPLE
}
