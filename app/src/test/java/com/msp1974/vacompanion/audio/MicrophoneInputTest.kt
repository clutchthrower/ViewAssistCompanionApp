package com.msp1974.vacompanion.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `echo diagnostics returns coherent snapshot`() {
        MicrophoneInput.setEchoDiagnosticsForTesting(
            requestedEchoMode = "software",
            activeEchoMode = "software",
            platformAecAvailable = true,
            platformAecEnabled = false,
        )

        val snapshot = MicrophoneInput.getEchoDiagnostics()

        assertEquals("software", snapshot.requestedEchoMode)
        assertEquals("software", snapshot.activeEchoMode)
        assertTrue(snapshot.platformAecAvailable)
        assertFalse(snapshot.platformAecEnabled)
    }
}
