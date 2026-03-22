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
import org.junit.Assert.*

class SessionHangRegressionTest {

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
        clearMocks(messenger)
    }

    @After
    fun tearDown() { 
        clientHandler.stop()
        unmockkStatic(LocalBroadcastManager::class)
    }

    /**
     * Regression test for a hang where a new wake word interrupt occurred after the server 
     * already sent 'pipeline-ended'. In this case, the coordinator would wait forever 
     * for a packet it already received.
     */
    @Test
    fun testHangWhenPipelineEndedArrivesBeforeWakeWordInterrupt() {
        // 1. Start Session 1 and reach logic completion (pipeline-ended received)
        // but keep it playing audio (AWAITING_TTS or STREAMING)
        clientHandler.onWakeWordDetected() // Starts Session 1
        
        // Verify Session 1 initiation
        verify { 
            messenger.sendEvent(match { it.type == "detection" && it.sessionId == 1 }) 
            messenger.sendEvent(match { it.type == "run-pipeline" && it.sessionId == 1 }) 
        }

        clientHandler.processPacket(WyomingPacket("transcribe", buildJsonObject {}, sessionId = 1))
        clientHandler.processPacket(WyomingPacket("synthesize", buildJsonObject {}, sessionId = 1))
        clientHandler.processPacket(WyomingPacket("audio-start", buildJsonObject {}, sessionId = 1))
        
        // 2. SERVER sends pipeline-ended for Session 1.
        // Session stays active because it's still streaming TTS.
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 1))
        
        assertEquals(PipelineStage.STREAMING, clientHandler.pipelineStage)
        
        // 3. New Wake word detected.
        // This should interrupt Session 1 and start Session 2 immediately.
        clientHandler.onWakeWordDetected()
        
        // Verify that Session 2 starts exactly once immediately
        verify(exactly = 1) { 
            messenger.sendEvent(match { it.type == "detection" && it.sessionId == 2 }) 
            messenger.sendEvent(match { it.type == "run-pipeline" && it.sessionId == 2 }) 
        }
        
        // Final sanity check: confirm we don't have other unexplained initiation packets
        confirmVerified(messenger)
    }
}
