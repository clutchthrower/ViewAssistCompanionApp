package com.msp1974.vacompanion.service

import kotlinx.serialization.json.boolean
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
                requestedInputProcessingMode = "webrtc",
                activeInputProcessingMode = "webrtc",
                platformAecAvailable = true,
                platformAecEnabled = false,
                effectiveAecEnabled = true,
                effectiveAgcEnabled = true,
                effectiveNsEnabled = true,
                webRtcApmInitialized = true,
                currentApmStreamDelayMs = 120,
                renderFeedAgeMs = 45L,
                audioEngine = "openwakeword",
                audioEngineStarted = true,
                audioEngineMuted = false,
                audioStreamingToServer = true,
                audioRoute = "stream",
                renderSinkActive = true,
                audioOutputPlaying = true,
            )
        )

        assertEquals(120, payload["current_apm_stream_delay_ms"]!!.jsonPrimitive.int)
        assertEquals(45, payload["render_feed_age_ms"]!!.jsonPrimitive.int)
        assertTrue(payload["effective_aec_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(payload["audio_output_playing"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `payload omits optional live AEC metric fields when unavailable`() {
        val payload = buildAudioInputDiagnosticsSensors(
            AudioInputDiagnosticsSnapshot(
                micAudioSource = "voice_recognition",
                requestedInputProcessingMode = "hardware",
                activeInputProcessingMode = "hardware",
                platformAecAvailable = true,
                platformAecEnabled = true,
                effectiveAecEnabled = true,
                effectiveAgcEnabled = true,
                effectiveNsEnabled = true,
                webRtcApmInitialized = false,
                currentApmStreamDelayMs = null,
                renderFeedAgeMs = null,
                audioEngine = "openwakeword",
                audioEngineStarted = false,
                audioEngineMuted = false,
                audioStreamingToServer = false,
                audioRoute = "none",
                renderSinkActive = false,
                audioOutputPlaying = false,
            )
        )

        assertFalse(payload.containsKey("current_apm_stream_delay_ms"))
        assertFalse(payload.containsKey("render_feed_age_ms"))
    }
}
