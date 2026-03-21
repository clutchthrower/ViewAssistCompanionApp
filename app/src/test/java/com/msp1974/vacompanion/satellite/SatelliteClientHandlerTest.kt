package com.msp1974.vacompanion.satellite

import android.content.Context
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.*
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class SatelliteClientHandlerTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK(relaxed = true)
    lateinit var context: Context

    @MockK(relaxed = true)
    lateinit var server: SatelliteServer

    @MockK(relaxed = true)
    lateinit var socket: Socket

    @MockK(relaxed = true)
    lateinit var log: Logger

    @MockK(relaxed = true)
    lateinit var config: APPConfig

    @MockK(relaxed = true)
    lateinit var messenger: WyomingMessenger

    @MockK(relaxed = true)
    lateinit var mediaHandler: SatelliteMediaHandler

    @MockK(relaxed = true)
    lateinit var actionHandler: SatelliteActionHandler

    @MockK(relaxed = true)
    lateinit var infoBuilder: SatelliteInfoBuilder

    @MockK(relaxed = true)
    lateinit var mainHandler: Handler

    private lateinit var clientHandler: SatelliteClientHandler

    @Before
    fun setUp() {
        mockkStatic(LocalBroadcastManager::class)
        val lbm = mockk<LocalBroadcastManager>(relaxed = true)
        every { LocalBroadcastManager.getInstance(any()) } returns lbm

        every { socket.port } returns 12345
        val mockAddress = mockk<InetAddress>()
        every { mockAddress.hostAddress } returns "127.0.0.1"
        every { socket.inetAddress } returns mockAddress
        
        every { config.version } returns "1.0.0"
        every { config.sampleRate } returns 16000
        every { config.audioWidth } returns 2
        every { config.audioChannels } returns 1
        every { config.uuid } returns "test-uuid"
        every { config.wakeWord } returns "hey_jarvis"
        every { config.minRequiredApkVersion } returns "1.0.0"
        every { config.pairedDeviceID } returns "127.0.0.1"

        clientHandler = SatelliteClientHandler(
            context = context,
            server = server,
            client = socket,
            log = log,
            config = config,
            messenger = messenger,
            mediaHandler = mediaHandler,
            actionHandler = actionHandler,
            infoBuilder = infoBuilder,
            mainHandler = mainHandler
        )
        
        // Ensure satellite is running for event processing tests
        val runSatellite = WyomingPacket("run-satellite", buildJsonObject {})
        clientHandler.processPacket(runSatellite)
    }

    @After
    fun tearDown() {
        clientHandler.stop()
        unmockkStatic(LocalBroadcastManager::class)
    }

    @Test
    fun `test pong is sent on ping event`() {
        clientHandler.sendPong()
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "pong" })
        }
    }

    @Test
    fun `test info is sent on describe event`() {
        val mockInfo = buildJsonObject { put("test", "info") }
        every { infoBuilder.buildInfo() } returns mockInfo
        
        clientHandler.processPacket(WyomingPacket("describe", buildJsonObject {}))
        
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "info" && it.data == mockInfo })
        }
    }

    @Test
    fun `test onWakeWordDetected initiates pipeline`() {
        clientHandler.onWakeWordDetected()
        
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "detection" })
            messenger.sendEvent(match { it.type == "run-pipeline" })
        }
    }

    @Test
    fun `test stop cleans up resources`() {
        clientHandler.stop()
        verify {
            mediaHandler.release()
            socket.close()
        }
    }

    @Test
    fun `test handleAudioStart plays audio`() {
        clientHandler.onWakeWordDetected()
        clientHandler.processPacket(WyomingPacket("audio-start", buildJsonObject {}))
        
        verify {
            mediaHandler.pcmMediaPlayer.play()
            mediaHandler.updateVolumeDucking("all", true)
        }
    }

    @Test
    fun `test handleAudioChunk writes audio to player`() {
        clientHandler.onWakeWordDetected()
        clientHandler.processPacket(WyomingPacket("audio-start", buildJsonObject {}))
        every { mediaHandler.pcmMediaPlayer.isPlaying } returns true
        
        val payload = byteArrayOf(0, 1, 2)
        clientHandler.processPacket(WyomingPacket("audio-chunk", buildJsonObject {}, payload))
        
        verify { mediaHandler.pcmMediaPlayer.writeAudio(payload) }
    }

    @Test
    fun `test handleAudioStop sends played event`() {
        clientHandler.onWakeWordDetected()
        clientHandler.processPacket(WyomingPacket("audio-start", buildJsonObject {}))
        every { mediaHandler.pcmMediaPlayer.isPlaying } returns true
        
        clientHandler.processPacket(WyomingPacket("audio-stop", buildJsonObject {}))
        
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "played" })
        }
    }

    @Test
    fun `test handleTranscript with never mind resets pipeline`() {
        clientHandler.onWakeWordDetected()
        clientHandler.processPacket(WyomingPacket("transcript", buildJsonObject { put("text", "never mind") }))
        
        verify {
            mediaHandler.updateVolumeDucking("all", false)
            mediaHandler.pcmMediaPlayer.stop()
        }
    }

    @Test
    fun `test handleSynthesize extracts continue_conversation flag`() {
        clientHandler.onWakeWordDetected()
        verify(timeout = 1000) { messenger.sendEvent(match { it.type == "run-pipeline" }) }
        clearMocks(messenger)
        
        val synthesizeData = buildJsonObject {
            putJsonObject("intent_output") {
                put("continue_conversation", true)
            }
        }
        
        clientHandler.processPacket(WyomingPacket("synthesize", synthesizeData))
        clientHandler.processPacket(WyomingPacket("audio-stop", buildJsonObject {}))
        
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "run-pipeline" })
        }
    }

    @Test
    fun `test handleAudioStop releases input audio stream if logic ended`() {
        clientHandler.onWakeWordDetected()
        
        // Advance to LISTENING
        clientHandler.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        
        // Simulate synthesize to make audioDone = false until audioStop
        clientHandler.processPacket(WyomingPacket("synthesize", buildJsonObject {}))
        
        // Advance to STREAMING
        clientHandler.processPacket(WyomingPacket("audio-start", buildJsonObject {}))
        
        // Mark logic as finished
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}))
        
        // Preliminary checks
        verify(exactly = 0) { server.releaseInputAudioStream() }
        
        // Act
        clientHandler.processPacket(WyomingPacket("audio-stop", buildJsonObject {}))
        
        // Verify
        verify(exactly = 1) { server.releaseInputAudioStream() }
    }

    @Test
    fun `test onWakeWordDetected interrupts pipeline and initiates after cleanup`() {
        clientHandler.onWakeWordDetected()
        verify(timeout = 1000) { messenger.sendEvent(match { it.type == "run-pipeline" }) }
        clearMocks(messenger)
        
        // Simulate synthesizer stage to reach AWAITING_TTS
        clientHandler.processPacket(WyomingPacket("synthesize", buildJsonObject {}))
        
        // Interrupt
        clientHandler.onWakeWordDetected()
        
        // Verify it didn't start yet
        verify(exactly = 0) { messenger.sendEvent(match { it.type == "run-pipeline" }) }
        
        // Simulate previous session ending
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}))
        
        verify(timeout = 1000) {
            log.d(match { it.contains("Interrupting current session") })
            messenger.sendEvent(match { it.type == "detection" })
            messenger.sendEvent(match { it.type == "run-pipeline" })
        }
    }

    @Test
    fun `test pause-satellite outside RUNNING state`() {
        // Satellite is running from setUp, so pause it once 
        clientHandler.processPacket(WyomingPacket("pause-satellite", buildJsonObject {}))
        clearMocks(mediaHandler)
        clearMocks(server)
        
        // Pause again when STOPPED
        clientHandler.processPacket(WyomingPacket("pause-satellite", buildJsonObject {}))
        
        verify(exactly = 0) {
            mediaHandler.musicPlayer.stop()
            server.onSatelliteStopped()
        }
    }

    @Test
    fun `test timer-finished outside RUNNING state`() {
        clientHandler.processPacket(WyomingPacket("pause-satellite", buildJsonObject {}))
        clearMocks(mediaHandler)

        clientHandler.processPacket(WyomingPacket("timer-finished", buildJsonObject {}))

        verify(exactly = 0) {
            mediaHandler.updateVolumeDucking(any(), any())
        }
    }

    @Test
    fun `test stop on never-listening session doesnt release audio`() {
        clientHandler.onWakeWordDetected()
        clearMocks(server)
        
        // Instantly end logic before audio starts
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {})) 
        
        // It never entered LISTENING, so it should not explicitly release
        verify(exactly = 0) { server.releaseInputAudioStream() }
    }
}
