package com.msp1974.vacompanion.service

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskDiagnosticsPayloadTest {

    @Test
    fun `payload includes live AEC metric fields when available`() {
        val payload = buildAudioInputDiagnosticsSensors(
            AudioInputDiagnosticsSnapshot(
                micAudioSource = "voice_communication",
                configuredInputProcessingMode = "webrtc",
                activeProcessingPipeline = "webrtc",
                hardwareAecAvailable = true,
                hardwareAecEnabled = false,
                activePipelineAecEnabled = true,
                activePipelineAgcEnabled = true,
                activePipelineNsEnabled = true,
                webRtcApmReady = true,
                currentApmStreamDelayMs = 120,
                renderFeedAgeMs = 45L,
                audioEngine = "openwakeword",
                audioEngineStarted = true,
                audioEngineMuted = false,
                audioStreamingToServer = true,
                wakeWordAudioRoute = "stream",
                renderTapSinkActive = true,
                outputPlaybackActive = true,
            )
        )

        assertEquals(120, payload["current_apm_stream_delay_ms"]!!.jsonPrimitive.int)
        assertEquals(45, payload["render_feed_age_ms"]!!.jsonPrimitive.int)
        assertTrue(payload["active_pipeline_aec_enabled"]!!.jsonPrimitive.boolean)
        assertEquals("stream", payload["wake_word_audio_route"]!!.jsonPrimitive.contentOrNull)
        assertTrue(payload["render_tap_sink_active"]!!.jsonPrimitive.boolean)
        assertTrue(payload["output_playback_active"]!!.jsonPrimitive.boolean)
        assertFalse(payload.containsKey("effective_aec_enabled"))
        assertFalse(payload.containsKey("audio_output_playing"))
    }

    @Test
    fun `payload omits optional live AEC metric fields when unavailable`() {
        val payload = buildAudioInputDiagnosticsSensors(
            AudioInputDiagnosticsSnapshot(
                micAudioSource = "voice_recognition",
                configuredInputProcessingMode = "hardware",
                activeProcessingPipeline = "hardware",
                hardwareAecAvailable = true,
                hardwareAecEnabled = true,
                activePipelineAecEnabled = true,
                activePipelineAgcEnabled = true,
                activePipelineNsEnabled = true,
                webRtcApmReady = false,
                currentApmStreamDelayMs = null,
                renderFeedAgeMs = null,
                audioEngine = "openwakeword",
                audioEngineStarted = false,
                audioEngineMuted = false,
                audioStreamingToServer = false,
                wakeWordAudioRoute = "none",
                renderTapSinkActive = false,
                outputPlaybackActive = false,
            )
        )

        assertFalse(payload.containsKey("current_apm_stream_delay_ms"))
        assertFalse(payload.containsKey("render_feed_age_ms"))
    }
}
