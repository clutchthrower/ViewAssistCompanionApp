package com.msp1974.vacompanion.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneInputTest {

    @Test
    fun `mapAudioSource returns communication source`() {
        val mapped = MicrophoneInput.mapAudioSource("voice_communication")
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, mapped)
    }

    @Test
    fun `mapAudioSource returns recognition source`() {
        val mapped = MicrophoneInput.mapAudioSource("voice_recognition")
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, mapped)
    }

    @Test
    fun `mapAudioSource falls back to default for unknown value`() {
        val mapped = MicrophoneInput.mapAudioSource("invalid_source")
        assertEquals(MicrophoneInput.DEFAULT_AUDIO_SOURCE, mapped)
    }

    @Test
    fun `input processing diagnostics returns coherent snapshot`() {
        MicrophoneInput.setInputProcessingDiagnosticsForTesting(
            configuredInputProcessingMode = "software",
            activeProcessingPipeline = "software",
            hardwareAecAvailable = true,
            hardwareAecEnabled = false,
            activePipelineAecEnabled = true,
            activePipelineAgcEnabled = true,
            activePipelineNsEnabled = true,
            webRtcApmReady = true,
        )

        val snapshot = MicrophoneInput.getInputProcessingDiagnostics()

        assertEquals("software", snapshot.configuredInputProcessingMode)
        assertEquals("software", snapshot.activeProcessingPipeline)
        assertTrue(snapshot.hardwareAecAvailable)
        assertFalse(snapshot.hardwareAecEnabled)
        assertTrue(snapshot.activePipelineAecEnabled)
        assertTrue(snapshot.activePipelineAgcEnabled)
        assertTrue(snapshot.activePipelineNsEnabled)
        assertTrue(snapshot.webRtcApmReady)
    }

    @Test
    fun `adaptive delay falls back to baseline when render feed is stale`() {
        val baseDelayMs = 80
        MicrophoneInput.setLastRenderFeedTimestampForTesting(0L)

        val noRenderDelay = MicrophoneInput.estimateAdaptiveWebRtcStreamDelayMs(
            baseDelayMs = baseDelayMs,
            nowMs = 1_000L,
        )

        assertEquals(baseDelayMs, noRenderDelay)
    }

    @Test
    fun `adaptive delay keeps baseline when render is recent`() {
        val baseDelayMs = 80
        val nowMs = 5_000L
        MicrophoneInput.setLastRenderFeedTimestampForTesting(nowMs - 40L)

        val delay = MicrophoneInput.estimateAdaptiveWebRtcStreamDelayMs(
            baseDelayMs = baseDelayMs,
            nowMs = nowMs,
        )

        assertEquals(baseDelayMs, delay)
    }

    @Test
    fun `adaptive delay uses plus 30 tier for mildly stale render`() {
        val baseDelayMs = 80
        val nowMs = 5_000L
        MicrophoneInput.setLastRenderFeedTimestampForTesting(nowMs - 200L)

        val delay = MicrophoneInput.estimateAdaptiveWebRtcStreamDelayMs(
            baseDelayMs = baseDelayMs,
            nowMs = nowMs,
        )

        assertEquals(110, delay)
    }

    @Test
    fun `adaptive delay uses plus 60 tier for stale render`() {
        val baseDelayMs = 80
        val nowMs = 5_000L
        MicrophoneInput.setLastRenderFeedTimestampForTesting(nowMs - 400L)

        val delay = MicrophoneInput.estimateAdaptiveWebRtcStreamDelayMs(
            baseDelayMs = baseDelayMs,
            nowMs = nowMs,
        )

        assertEquals(140, delay)
    }

    @Test
    fun `adaptive delay clamps to 500 upper bound`() {
        val nowMs = 8_000L
        MicrophoneInput.setLastRenderFeedTimestampForTesting(nowMs - 400L)

        val delay = MicrophoneInput.estimateAdaptiveWebRtcStreamDelayMs(
            baseDelayMs = 470,
            nowMs = nowMs,
        )

        assertEquals(500, delay)
    }

    @Test
    fun `hardware platform effects are only used in hardware mode`() {
        assertTrue(MicrophoneInput.shouldUseHardwarePlatformEffects("hardware"))
        assertFalse(MicrophoneInput.shouldUseHardwarePlatformEffects("webrtc"))
        assertFalse(MicrophoneInput.shouldUseHardwarePlatformEffects("speex"))
    }

    @Test
    fun `webrtc render tap is only enabled in webrtc mode`() {
        assertFalse(MicrophoneInput.shouldEnableWebRtcRenderTap("hardware"))
        assertTrue(MicrophoneInput.shouldEnableWebRtcRenderTap("webrtc"))
        assertFalse(MicrophoneInput.shouldEnableWebRtcRenderTap("speex"))
    }

    @Test
    fun `render feed age is null when no render feed exists`() {
        MicrophoneInput.setLastRenderFeedTimestampForTesting(0L)
        assertNull(MicrophoneInput.getRenderFeedAgeMs(nowMs = 10_000L))
    }

    @Test
    fun `render feed age reports elapsed milliseconds`() {
        MicrophoneInput.setLastRenderFeedTimestampForTesting(9_500L)
        assertEquals(500L, MicrophoneInput.getRenderFeedAgeMs(nowMs = 10_000L))
    }
}
