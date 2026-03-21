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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService

class SessionSerializationTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK(relaxed = true) lateinit var context: Context
    @MockK(relaxed = true) lateinit var server: SatelliteServer
    @MockK(relaxed = true) lateinit var socket: Socket
    @MockK(relaxed = true) lateinit var log: Logger
    @MockK(relaxed = true) lateinit var config: APPConfig
    @MockK(relaxed = true) lateinit var messenger: WyomingMessenger
    @MockK(relaxed = true) lateinit var mediaHandler: SatelliteMediaHandler
    @MockK(relaxed = true) lateinit var actionHandler: SatelliteActionHandler
    @MockK(relaxed = true) lateinit var infoBuilder: SatelliteInfoBuilder
    @MockK(relaxed = true) lateinit var mainHandler: Handler

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
        every { config.minRequiredApkVersion } returns "1.0.0"
        every { config.pairedDeviceID } returns "127.0.0.1"
        every { config.uuid } returns "test-uuid"
        every { config.wakeWord } returns "hey_jarvis"
        every { config.sampleRate } returns 16000
        every { config.audioWidth } returns 2
        every { config.audioChannels } returns 1

        val directExecutor = object : AbstractExecutorService() {
            override fun execute(command: Runnable) = command.run()
            override fun shutdown() {}
            override fun shutdownNow(): List<Runnable> = emptyList()
            override fun isShutdown(): Boolean = false
            override fun isTerminated(): Boolean = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        }

        clientHandler = SatelliteClientHandler(
            context, server, socket, log, config, messenger, mediaHandler, actionHandler, infoBuilder, mainHandler,
            sendExecutor = directExecutor,
            broadcastExecutor = directExecutor
        )
        clientHandler.processPacket(WyomingPacket("run-satellite", buildJsonObject {}))
    }

    @After
    fun tearDown() { clientHandler.stop(); unmockkStatic(LocalBroadcastManager::class) }

    @Test
    fun `test second wake word detection waits for first session to end on server`() {
        // First detection
        clientHandler.onWakeWordDetected()
        verify(timeout = 1000) { 
            messenger.sendEvent(match { it.type == "detection" }) 
            messenger.sendEvent(match { it.type == "run-pipeline" })
        }
        
        // Mark first session started but not finished logic
        clientHandler.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        clearMocks(messenger)

        // Rapid second detection
        clientHandler.onWakeWordDetected()
        
        // Should NOT send detection/run-pipeline immediately for the second session
        verify(exactly = 0) { 
            messenger.sendEvent(match { it.type == "detection" }) 
        }

        // Simulate first session ending on server (Home Assistant may not provide session ID in pipeline-ended)
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = null))

        // Now second session should initiate
        verify { 
            messenger.sendEvent(match { it.type == "detection" }) 
            messenger.sendEvent(match { it.type == "run-pipeline" })
        }
    }

    @Test
    fun `test sendRawEvent filters audio chunks when not listening`() {
        clientHandler.onWakeWordDetected()
        verify(timeout = 1000) { messenger.sendEvent(match { it.type == "run-pipeline" }) }
        clearMocks(messenger)
        
        // Stage should be IDLE right now (waiting for transcribe or audio-start)
        clientHandler.sendRawEvent(WyomingPacket("audio-chunk", buildJsonObject {}, byteArrayOf(1, 2)))
        
        verify(exactly = 0) { messenger.sendEvent(any()) }
    }

    @Test
    fun `test sendRawEvent drops packets for inactive sessions`() {
        clientHandler.onWakeWordDetected()
        verify(timeout = 1000) { messenger.sendEvent(match { it.type == "run-pipeline" }) }
        clearMocks(messenger)
        
        // Active session ID is likely 1 since it's the first wake word
        val staleSessionId = 99
        clientHandler.sendRawEvent(WyomingPacket("info", buildJsonObject {}, sessionId = staleSessionId))
        
        verify(exactly = 0) { messenger.sendEvent(any()) }
    }
    @Test
    fun `test never mind transcript interrupts session and finalizes`() {
        clientHandler.onWakeWordDetected()
        verify(timeout = 1000) { 
            messenger.sendEvent(match { it.type == "run-pipeline" }) 
        }
        
        // Put in listening mode
        clientHandler.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        
        val transcriptPacket = WyomingPacket("transcript", buildJsonObject {
            put("text", "oh never mind")
        })
        clientHandler.processPacket(transcriptPacket)
        
        // Wait to make sure the pipeline timeout is NOT set to 15 (which is for normal transcript)
        // Check that session is finalized right away
        verify(atLeast = 1) {
            mediaHandler.updateVolumeDucking("all", false)
        }
    }
}
