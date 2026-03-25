package com.msp1974.vacompanion.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * ExoPlayer audio processor that pass-throughs playback audio unchanged while tapping PCM
 * and feeding it to WebRTC APM render stream as 16 kHz mono PCM16 little-endian.
 */
@UnstableApi
class ApmTapAudioProcessor(
    private val renderSinkProvider: () -> ((ByteArray) -> Unit)? = { MicrophoneInput.renderStreamSink },
) : BaseAudioProcessor() {
    private companion object {
        private const val TARGET_SAMPLE_RATE_HZ = VacaAudioFormat.SAMPLE_RATE_HZ
        private const val TAG = "ApmTapAudioProcessor"
        private const val DIAGNOSTIC_INTERVAL_BYTES = TARGET_SAMPLE_RATE_HZ * VacaAudioFormat.BYTES_PER_SAMPLE
    }

    private var inputSampleRateHz = 0
    private var inputChannelCount = 0
    private var inputToOutputStep = 1.0
    private var nextOutputPos = 0.0
    private var diagnosticsEnabled = Log.isLoggable(TAG, Log.DEBUG)
    private var diagnosticsFrameBytes = 0L
    private var lowPassEnabled = false
    private var lowPassAlpha = 1.0f
    private var lowPassState = 0.0f

    private var monoBuffer = FloatArray(0)
    private var monoBufferSize = 0
    private var outputByteBuffer = ByteArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        inputSampleRateHz = inputAudioFormat.sampleRate
        inputChannelCount = inputAudioFormat.channelCount
        inputToOutputStep = inputSampleRateHz.toDouble() / TARGET_SAMPLE_RATE_HZ.toDouble()
        nextOutputPos = 0.0
        monoBufferSize = 0
        diagnosticsFrameBytes = 0L
        lowPassEnabled = inputSampleRateHz > TARGET_SAMPLE_RATE_HZ
        lowPassState = 0.0f
        lowPassAlpha = if (lowPassEnabled) {
            // One-pole LPF around 0.45 * Nyquist(target) to reduce aliasing before downsampling.
            val cutoffHz = TARGET_SAMPLE_RATE_HZ * 0.45
            val dt = 1.0 / inputSampleRateHz.toDouble()
            val rc = 1.0 / (2.0 * PI * cutoffHz)
            (dt / (rc + dt)).toFloat()
        } else {
            1.0f
        }
        if (diagnosticsEnabled) {
            Timber.tag(TAG).d(
                "Configured tap: in=%dHz/%dch enc=%d, out=%dHz mono pcm16, lpf=%s",
                inputSampleRateHz,
                inputChannelCount,
                inputAudioFormat.encoding,
                TARGET_SAMPLE_RATE_HZ,
                lowPassEnabled,
            )
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val sink = renderSinkProvider()
        if (sink != null && inputBuffer.hasRemaining()) {
            tapAndFeedRenderStream(inputBuffer.duplicate(), sink)
        }

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        monoBufferSize = 0
        nextOutputPos = 0.0
        diagnosticsFrameBytes = 0L
        lowPassState = 0.0f
    }

    override fun onReset() {
        super.onReset()
        inputSampleRateHz = 0
        inputChannelCount = 0
        inputToOutputStep = 1.0
        nextOutputPos = 0.0
        diagnosticsFrameBytes = 0L
        lowPassEnabled = false
        lowPassAlpha = 1.0f
        lowPassState = 0.0f
        monoBuffer = FloatArray(0)
        monoBufferSize = 0
        outputByteBuffer = ByteArray(0)
    }

    private fun tapAndFeedRenderStream(
        pcm16Input: java.nio.ByteBuffer,
        sink: (ByteArray) -> Unit,
    ) {
        val inputShorts = pcm16Input.asShortBuffer()
        val frameCount = inputShorts.remaining() / inputChannelCount
        if (frameCount <= 0) return

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

        val estimatedOutputSamples =
            ((frameCount.toLong() * TARGET_SAMPLE_RATE_HZ) / inputSampleRateHz).toInt() + 4
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

        if (outIndex > 0) {
            try {
                sink(outputByteBuffer.copyOf(outIndex))
                if (diagnosticsEnabled) {
                    diagnosticsFrameBytes += outIndex
                    if (diagnosticsFrameBytes >= DIAGNOSTIC_INTERVAL_BYTES) {
                        val samples = diagnosticsFrameBytes / VacaAudioFormat.BYTES_PER_SAMPLE
                        Timber.tag(TAG).d("Fed render tap %d samples (%d bytes)", samples, diagnosticsFrameBytes)
                        diagnosticsFrameBytes = 0L
                    }
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "Render tap sink failed; skipping chunk")
            }
        }
    }

    private fun ensureMonoCapacity(requiredCapacity: Int) {
        if (monoBuffer.size >= requiredCapacity) return
        var newCapacity = if (monoBuffer.isEmpty()) 1024 else monoBuffer.size
        while (newCapacity < requiredCapacity) {
            newCapacity *= 2
        }
        monoBuffer = monoBuffer.copyOf(newCapacity)
    }
}
