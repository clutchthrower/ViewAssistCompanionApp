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

/**
 * Specifically tests for race conditions and session management in Wyoming client handling. 
 */
class RaceConditionTest {

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

        every { socket.port } returns 12345
        val mockAddress = mockk<InetAddress>()
        every { mockAddress.hostAddress } returns "127.0.0.1"
        every { socket.inetAddress } returns mockAddress
        
        every { config.version } returns "1.0.0"
        every { config.sampleRate } returns 16000
        every { config.audioWidth } returns 2
        every { config.audioChannels } returns 1
        every { config.uuid } returns "test-uuid"
        every { config.wakeWord } returns "alexa"
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
        
        // Explicitly set server.pipelineClient to avoid takeover logic calling stop()
        every { server.pipelineClient } returns null
        clientHandler.startSatellite()
    }

    @After
    fun tearDown() {
        clientHandler.stop()
        unmockkStatic(LocalBroadcastManager::class)
    }

    @Test
    fun `test rapid-fire wake word wait for previous session to end`() {
        // 1. Initial wake word detection (Session 1)
        clientHandler.onWakeWordDetected()
        verify(exactly = 1) { messenger.sendEvent(match { it.type == "run-pipeline" }, any(), any()) }
        
        // 2. Rapid-fire second wake word detection. 
        // This should NOT call initiatePipeline immediately (count remains 1).
        clientHandler.onWakeWordDetected()
        verify(exactly = 1) { messenger.sendEvent(match { it.type == "run-pipeline" }, any(), any()) }

        // 3. Simulate the 'pipeline-ended' event arriving for the old session.
        // This should trigger the pending start.
        val oldSessionEnded = WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = null)
        clientHandler.processEvent(oldSessionEnded)
        
        // 4. Verify that initiatePipeline was now called for the second time.
        verify(exactly = 2) { messenger.sendEvent(match { it.type == "run-pipeline" }, any(), any()) }
        
        // 5. Simulate the 'transcribe' event for the NEW session
        val newSessionTranscribe = WyomingPacket("transcribe", buildJsonObject {}, sessionId = null)
        clientHandler.processEvent(newSessionTranscribe)
        
        // 6. Verify that it proceeded to listening stage
        verify(exactly = 1) { server.requestInputAudioStream() }
    }
}
