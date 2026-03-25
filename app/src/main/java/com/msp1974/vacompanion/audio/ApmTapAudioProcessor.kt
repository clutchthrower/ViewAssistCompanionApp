package com.msp1974.vacompanion.audio

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber

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
    private var diagnosticsEnabled = Log.isLoggable(TAG, Log.DEBUG)
    private var diagnosticsFrameBytes = 0L
    private val tapResampler = RenderTapResampler(TARGET_SAMPLE_RATE_HZ)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        inputSampleRateHz = inputAudioFormat.sampleRate
        inputChannelCount = inputAudioFormat.channelCount
        tapResampler.configure(
            sampleRateHz = inputAudioFormat.sampleRate,
            channelCount = inputAudioFormat.channelCount,
            bytesPerSample = VacaAudioFormat.BYTES_PER_SAMPLE,
        )
        diagnosticsFrameBytes = 0L
        if (diagnosticsEnabled) {
            Timber.tag(TAG).d(
                "Configured tap: in=%dHz/%dch enc=%d, out=%dHz mono pcm16",
                inputSampleRateHz,
                inputChannelCount,
                inputAudioFormat.encoding,
                TARGET_SAMPLE_RATE_HZ,
            )
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val sink = renderSinkProvider()
        if (sink != null && inputBuffer.hasRemaining()) {
            tapAndFeedRenderStream(inputBuffer.duplicate(), sink)
        }

        val remaining = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(remaining)
        // ExoPlayer may reuse/directly share ByteBuffer instances across stages.
        // Copying from a duplicate prevents IllegalArgumentException ("source buffer is this buffer")
        // if output and input end up referencing the same underlying object.
        outputBuffer.put(inputBuffer.duplicate())
        outputBuffer.flip()
        // Preserve AudioProcessor contract: queued input is considered fully consumed.
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        super.onFlush(streamMetadata)
        tapResampler.resetState()
        diagnosticsFrameBytes = 0L
    }

    override fun onReset() {
        super.onReset()
        inputSampleRateHz = 0
        inputChannelCount = 0
        diagnosticsFrameBytes = 0L
        tapResampler.clear()
    }

    private fun tapAndFeedRenderStream(
        pcm16Input: java.nio.ByteBuffer,
        sink: (ByteArray) -> Unit,
    ) {
        val resampled = tapResampler.process(pcm16Input) ?: return
        if (resampled.isNotEmpty()) {
            try {
                sink(resampled)
                if (diagnosticsEnabled) {
                    diagnosticsFrameBytes += resampled.size
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
}
