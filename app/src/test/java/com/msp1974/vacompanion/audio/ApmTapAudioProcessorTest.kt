package com.msp1974.vacompanion.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.abs

class ApmTapAudioProcessorTest {

    @Test
    fun `resamples 48k stereo to near 16k mono`() {
        val capture = ByteArrayOutputStream()
        val processor = ApmTapAudioProcessor { { bytes -> capture.write(bytes) } }
        configureAndFlush(processor, sampleRateHz = 48_000, channelCount = 2)

        val frames = 4_800 // 100 ms at 48 kHz
        val input = ShortArray(frames * 2)
        for (i in 0 until frames) {
            input[i * 2] = 1_000
            input[i * 2 + 1] = (-1_000).toShort()
        }
        processor.queueInput(directPcm16Buffer(input))

        val output = bytesToShorts(capture.toByteArray())
        assertTrue(output.size in 1_500..1_700)
        assertTrue(output.all { abs(it.toInt()) <= 1_000 })
    }

    @Test
    fun `resamples 44_1k stereo and preserves signal`() {
        val capture = ByteArrayOutputStream()
        val processor = ApmTapAudioProcessor { { bytes -> capture.write(bytes) } }
        configureAndFlush(processor, sampleRateHz = 44_100, channelCount = 2)

        val frames = 4_410 // 100 ms at 44.1 kHz
        val input = ShortArray(frames * 2)
        for (i in 0 until frames) {
            input[i * 2] = 10_000
            input[i * 2 + 1] = 10_000
        }
        processor.queueInput(directPcm16Buffer(input))

        val output = bytesToShorts(capture.toByteArray())
        assertTrue(output.size in 1_500..1_700)
        assertTrue(output.any { abs(it.toInt()) >= 1_000 })
    }

    @Test
    fun `keeps interpolation continuity across chunk boundaries`() {
        val oneShot = ByteArrayOutputStream()
        val chunked = ByteArrayOutputStream()
        val oneShotProcessor = ApmTapAudioProcessor { { bytes -> oneShot.write(bytes) } }
        val chunkedProcessor = ApmTapAudioProcessor { { bytes -> chunked.write(bytes) } }

        configureAndFlush(oneShotProcessor, sampleRateHz = 48_000, channelCount = 2)
        configureAndFlush(chunkedProcessor, sampleRateHz = 48_000, channelCount = 2)

        val frames = 2_400 // 50 ms
        val input = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (i % 500 - 250) * 100
            input[i * 2] = v.toShort()
            input[i * 2 + 1] = v.toShort()
        }

        oneShotProcessor.queueInput(directPcm16Buffer(input))

        val splitAtFrames = 1_121
        val first = input.copyOfRange(0, splitAtFrames * 2)
        val second = input.copyOfRange(splitAtFrames * 2, input.size)
        chunkedProcessor.queueInput(directPcm16Buffer(first))
        chunkedProcessor.queueInput(directPcm16Buffer(second))

        assertEquals(oneShot.toByteArray().toList(), chunked.toByteArray().toList())
    }

    @Test
    fun `sink exceptions do not crash queueInput`() {
        val processor = ApmTapAudioProcessor {
            {
                throw IllegalStateException("sink boom")
            }
        }
        configureAndFlush(processor, sampleRateHz = 48_000, channelCount = 2)

        val frames = 480
        val input = ShortArray(frames * 2) { 1_000 }
        processor.queueInput(directPcm16Buffer(input))
        assertTrue(true)
    }

    @Test
    fun `unsupported encoding keeps processor inactive`() {
        val processor = ApmTapAudioProcessor()
        val outputFormat = processor.configure(
            AudioProcessor.AudioFormat(
                48_000,
                2,
                C.ENCODING_PCM_FLOAT,
            )
        )
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, outputFormat)
    }

    @Test
    fun `passes through input unchanged when sink missing`() {
        val processor = ApmTapAudioProcessor { null }
        configureAndFlush(processor, sampleRateHz = 48_000, channelCount = 2)

        val frames = 960
        val input = ShortArray(frames * 2) { idx -> (idx % 1024 - 512).toShort() }
        val inputBytes = shortsToBytes(input)
        val queueBuffer = ByteBuffer.allocateDirect(inputBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        queueBuffer.put(inputBytes)
        queueBuffer.flip()

        processor.queueInput(queueBuffer)
        val outBuffer = processor.output
        val actual = ByteArray(outBuffer.remaining())
        outBuffer.get(actual)

        assertTrue(Arrays.equals(inputBytes, actual))
    }

    @Test
    fun `sustained chunked input keeps stable 3 to 1 output ratio`() {
        val capture = ByteArrayOutputStream()
        val processor = ApmTapAudioProcessor { { bytes -> capture.write(bytes) } }
        configureAndFlush(processor, sampleRateHz = 48_000, channelCount = 2)

        val chunks = 600 // 60s total using 100ms chunks
        val framesPerChunk = 4_800
        val chunk = ShortArray(framesPerChunk * 2)
        for (i in chunk.indices step 2) {
            chunk[i] = 8_000
            chunk[i + 1] = 8_000
        }

        repeat(chunks) {
            processor.queueInput(directPcm16Buffer(chunk))
        }

        val outputSamples = capture.size() / VacaAudioFormat.BYTES_PER_SAMPLE
        val expected = chunks * (framesPerChunk / 3)
        assertTrue(outputSamples in (expected - 8)..(expected + 8))
    }

    @Test
    fun `intermittent tone and silence chunks keep stable output pacing`() {
        val capture = ByteArrayOutputStream()
        val processor = ApmTapAudioProcessor { { bytes -> capture.write(bytes) } }
        configureAndFlush(processor, sampleRateHz = 48_000, channelCount = 2)

        val cycles = 120 // 24s total: 120 * (100ms tone + 100ms silence)
        val framesPerChunk = 4_800
        val tone = ShortArray(framesPerChunk * 2)
        for (i in tone.indices step 2) {
            tone[i] = 6_000
            tone[i + 1] = 6_000
        }
        val silence = ShortArray(framesPerChunk * 2)

        repeat(cycles) {
            processor.queueInput(directPcm16Buffer(tone))
            processor.queueInput(directPcm16Buffer(silence))
        }

        val outputSamples = capture.size() / VacaAudioFormat.BYTES_PER_SAMPLE
        val expected = cycles * 2 * (framesPerChunk / 3)
        assertTrue(outputSamples in (expected - 12)..(expected + 12))
    }

    private fun configureAndFlush(
        processor: ApmTapAudioProcessor,
        sampleRateHz: Int,
        channelCount: Int,
    ) {
        processor.configure(
            AudioProcessor.AudioFormat(
                sampleRateHz,
                channelCount,
                C.ENCODING_PCM_16BIT,
            )
        )
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun directPcm16Buffer(samples: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) {
            buffer.putShort(s)
        }
        buffer.flip()
        return buffer
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        if (bytes.isEmpty()) return ShortArray(0)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(bytes.size / 2)
        bb.asShortBuffer().get(out)
        return out
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            bb.putShort(s)
        }
        return bb.array()
    }
}
