package com.msp1974.vacompanion.wyoming

import android.content.Context
import android.os.Handler
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.Socket

class ClientHandlerTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK(relaxed = true)
    lateinit var context: Context

    @MockK(relaxed = true)
    lateinit var server: WyomingTCPServer

    @MockK(relaxed = true)
    lateinit var socket: Socket

    @MockK(relaxed = true)
    lateinit var log: Logger

    @MockK(relaxed = true)
    lateinit var config: APPConfig

    @MockK(relaxed = true)
    lateinit var messenger: WyomingMessenger

    @MockK(relaxed = true)
    lateinit var mediaManager: WyomingMediaManager

    @MockK(relaxed = true)
    lateinit var actionHandler: WyomingActionHandler

    @MockK(relaxed = true)
    lateinit var infoBuilder: WyomingInfoBuilder

    @MockK(relaxed = true)
    lateinit var mainHandler: Handler

    private lateinit var clientHandler: ClientHandler

    @Before
    fun setUp() {
        mockkStatic(LocalBroadcastManager::class)
        val lbm = mockk<LocalBroadcastManager>(relaxed = true)
        every { LocalBroadcastManager.getInstance(any()) } returns lbm

        // Basic socket mocks
        every { socket.port } returns 12345
        val mockAddress = mockk<InetAddress>()
        every { mockAddress.hostAddress } returns "127.0.0.1"
        every { socket.inetAddress } returns mockAddress
        
        // Config mocks
        every { config.version } returns "1.0.0"
        every { config.sampleRate } returns 16000
        every { config.audioWidth } returns 2
        every { config.audioChannels } returns 1
        every { config.uuid } returns "test-uuid"
        every { config.wakeWord } returns "hey_jarvis"
        every { config.minRequiredApkVersion } returns "1.0.0"
        every { config.pairedDeviceID } returns "127.0.0.1"

        clientHandler = ClientHandler(
            context = context,
            server = server,
            client = socket,
            log = log,
            config = config,
            messenger = messenger,
            mediaManager = mediaManager,
            actionHandler = actionHandler,
            infoBuilder = infoBuilder,
            mainHandler = mainHandler
        )
        
        // Ensure satellite is running for event processing tests
        clientHandler.startSatellite()
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
            messenger.sendEvent(match { it.type == "pong" }, any(), any())
        }
    }

    @Test
    fun `test info is sent on describe event`() {
        val mockInfo = buildJsonObject { put("test", "info") }
        every { infoBuilder.buildInfo() } returns mockInfo
        
        clientHandler.processEvent(WyomingPacket("describe", buildJsonObject {}))
        
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "info" && it.data == mockInfo }, any(), any())
        }
    }

    @Test
    fun `test initiatePipeline sends detection and run-pipeline by default`() {
        clientHandler.initiatePipeline()
        
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "detection" }, any(), any())
            messenger.sendEvent(match { it.type == "run-pipeline" }, any(), any())
        }
    }

    @Test
    fun `test stop cleans up resources`() {
        clientHandler.stop()
        verify {
            mediaManager.release()
            socket.close()
        }
    }

    @Test
    fun `test onWakeWordDetected initiates pipeline when idle`() {
        clientHandler.onWakeWordDetected()
        
        verify { mediaManager.updateVolumeDucking("all", true) }
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "detection" }, any(), any())
            messenger.sendEvent(match { it.type == "run-pipeline" }, any(), any())
        }
    }

    @Test
    fun `test onWakeWordDetected interrupts response and initiates pipeline when already streaming`() {
        clientHandler.initiatePipeline()
        clearMocks(messenger)
        
        // Simulate synthesizer stage to reach AWAITING_TTS
        clientHandler.processEvent(WyomingPacket("synthesize", buildJsonObject {}))
        
        clientHandler.onWakeWordDetected()
        
        verify(timeout = 1000) {
            log.d(match { it.contains("Interrupting response") })
            messenger.sendEvent(match { it.type == "detection" }, any(), any())
            messenger.sendEvent(match { it.type == "run-pipeline" }, any(), any())
        }
    }

    @Test
    fun `test handleAudioStart plays audio`() {
        clientHandler.initiatePipeline()
        clientHandler.handleAudioStart()
        
        verify {
            mediaManager.pcmMediaPlayer.play()
            mediaManager.updateVolumeDucking("all", true)
        }
    }

    @Test
    fun `test handleAudioChunk writes audio to player`() {
        clientHandler.initiatePipeline()
        clientHandler.handleAudioStart()
        every { mediaManager.pcmMediaPlayer.isPlaying } returns true
        
        val payload = byteArrayOf(0, 1, 2)
        clientHandler.handleAudioChunk(WyomingPacket("audio-chunk", buildJsonObject {}, payload))
        
        verify { mediaManager.pcmMediaPlayer.writeAudio(payload) }
    }

    @Test
    fun `test handleAudioStop sends played event`() {
        clientHandler.initiatePipeline()
        clientHandler.handleAudioStart()
        every { mediaManager.pcmMediaPlayer.isPlaying } returns true
        
        clientHandler.handleAudioStop()
        
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "played" }, any(), any())
        }
    }

    @Test
    fun `test handleTranscript with never mind resets pipeline`() {
        clientHandler.initiatePipeline()
        clientHandler.processEvent(WyomingPacket("transcript", buildJsonObject { put("text", "never mind") }))
        
        verify {
            mediaManager.updateVolumeDucking("all", false)
            mediaManager.pcmMediaPlayer.stop()
        }
    }

    @Test
    fun `test handlePipelineEnded without audio cleans up`() {
        clientHandler.initiatePipeline()
        clientHandler.processEvent(WyomingPacket("pipeline-ended", buildJsonObject {}))
        
        verify {
            mainHandler.removeCallbacks(any())
            mediaManager.updateVolumeDucking("all", false)
        }
    }

    @Test
    fun `test handleSynthesize extracts continue_conversation flag`() {
        clientHandler.initiatePipeline()
        val synthesizeData = buildJsonObject {
            putJsonObject("intent_output") {
                put("continue_conversation", true)
            }
        }
        
        clientHandler.processEvent(WyomingPacket("synthesize", synthesizeData))
        clientHandler.handleAudioStop()
        
        verify(timeout = 1000) {
            messenger.sendEvent(match { it.type == "run-pipeline" }, any(), any())
        }
    }

    @Test
    fun `test handleAudioStop releases input audio stream if logic ended`() {
        clientHandler.initiatePipeline()
        
        // Advance to LISTENING
        clientHandler.processEvent(WyomingPacket("transcribe", buildJsonObject {}))
        
        // Simulate synthesize to make audioDone = false until audioStop
        clientHandler.processEvent(WyomingPacket("synthesize", buildJsonObject {}))
        
        // Advance to STREAMING
        clientHandler.handleAudioStart()
        
        // Mark logic as finished
        clientHandler.processEvent(WyomingPacket("pipeline-ended", buildJsonObject {}))
        
        // Preliminary checks
        verify(exactly = 0) { server.releaseInputAudioStream() }
        
        // Act
        clientHandler.handleAudioStop()
        
        // Verify. Bug was: manual IDLE set prevented this call.
        verify(exactly = 1) { server.releaseInputAudioStream() }
    }
}
