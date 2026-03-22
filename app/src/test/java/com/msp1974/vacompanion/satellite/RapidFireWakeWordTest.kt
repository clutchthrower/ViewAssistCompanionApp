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
import org.junit.Assert.*

class RapidFireWakeWordTest {

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
    fun tearDown() { 
        clientHandler.stop()
        unmockkStatic(LocalBroadcastManager::class)
    }

    @Test
    fun `reproduce audio overlap and session transition issue`() {
        // 1. First wake word detection starts Session 1
        clientHandler.onWakeWordDetected()
        
        // 2. Session 1 gets to STREAMING stage (receiving TTS)
        clientHandler.processPacket(WyomingPacket("transcribe", buildJsonObject {}, sessionId = 1))
        clientHandler.processPacket(WyomingPacket("transcript", buildJsonObject { put("text", "what time is it?") }, sessionId = 1))
        clientHandler.processPacket(WyomingPacket("synthesize", buildJsonObject {
            putJsonObject("intent_output") { put("continue_conversation", true) }
        }, sessionId = 1))
        clientHandler.processPacket(WyomingPacket("audio-start", buildJsonObject {}, sessionId = 1))
        
        assertEquals(PipelineStage.STREAMING, clientHandler.pipelineStage)
        
        // 3. Rapid fire second wake word detection
        // Note: handleBroadcastIntent in real usage calls stop() on media playback before onWakeWordDetected
        // but here we are testing the coordination and callback trigger.
        clientHandler.onWakeWordDetected()
        
        // FIXED: verify that onStopMediaPlayback is called with force=true
        // This confirms the "audio bleed" is addressed by flushing the player buffers.
        verify { mediaHandler.pcmMediaPlayer.stop(force = true) } 
        
        // 4. Simulate server finally sending pipeline-ended for Session 1
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 1))
        
        // 5. Session 2 should have initiated now
        verify { messenger.sendEvent(match { it.type == "run-pipeline" && it.sessionId == 2 }) }
        
        // 6. Simulate Session 2 starting to listen
        clientHandler.processPacket(WyomingPacket("transcribe", buildJsonObject {}, sessionId = 2))
        
        assertEquals(PipelineStage.LISTENING, clientHandler.pipelineStage)
        
        // --- THE PROBLEM ---
        // At this point, Session 2 is LISTENING (expecting user input for the NEW request)
        // BUT, Session 1's audio is "still playing" out of the buffer (simulated by our knowledge of pcmMediaPlayer.stop behavior)
        // If the user waits for that audio to finish before speaking, Session 2 might time out!
        
        // Let's verify that Session 2 is indeed listening even though we just had a "question" from Session 1
        assertEquals(PipelineStage.LISTENING, clientHandler.pipelineStage)
    }

    @Test
    fun `test triple rapid fire wake words fixed behavior`() {
        val sessionIds = mutableListOf<Int>()
        
        // 1. First wake word detection starts Session 1
        clientHandler.onWakeWordDetected()
        verify(timeout = 1000) { 
            messenger.sendEvent(match { 
                if (it.type == "run-pipeline") sessionIds.add(it.sessionId!!)
                it.type == "run-pipeline" 
            }) 
        }
        val s1Id = sessionIds.last()
        clearMocks(messenger)

        // 2. Second wake word (rapid)
        clientHandler.onWakeWordDetected() // S1 stops, S2 pending, isRestartPending = true
        verify(exactly = 0) { messenger.sendEvent(match { it.type == "run-pipeline" }) }
        
        // 3. Third wake word (super rapid)
        clientHandler.onWakeWordDetected() // Should replace S2 in pendingSession
        
        // CORRECTED: Session 3 should NOT start immediately!
        verify(exactly = 0) { messenger.sendEvent(match { it.type == "run-pipeline" }) }
        
        // 4. Simulate server sending pipeline-ended for Session 1
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = s1Id))
        
        // CORRECTED: Session 3 (or the latest one) starts now. 
        verify(timeout = 1000) { messenger.sendEvent(match { it.type == "run-pipeline" }) }
    }
    @Test
    fun `test wake word interrupts active listening session`() {
        // 1. Start Session 1 and reach LISTENING stage
        clientHandler.onWakeWordDetected()
        clientHandler.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        assertEquals(PipelineStage.LISTENING, clientHandler.pipelineStage)
        
        // 2. Interrupt with new wake word
        clientHandler.onWakeWordDetected() // S1 stops.
        
        // 3. Verify S1 was stopped and audio-stop sent to server
        verify { 
            messenger.sendEvent(match { it.type == "audio-stop" && it.sessionId == 1 })
            mediaHandler.pcmMediaPlayer.stop(force = true) 
        }
        
        // 4. Simulate server cleanup of S1
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 1))
        
        // 5. Verify Session 2 starts
        verify(timeout = 1000) { messenger.sendEvent(match { it.type == "run-pipeline" && it.sessionId == 2 }) }
    }

    @Test
    fun `test wake word interrupts TTS speaking session`() {
        // 1. Start Session 1 and reach STREAMING stage
        clientHandler.onWakeWordDetected()
        clientHandler.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        clientHandler.processPacket(WyomingPacket("synthesize", buildJsonObject {}))
        clientHandler.processPacket(WyomingPacket("audio-start", buildJsonObject {}))
        assertEquals(PipelineStage.STREAMING, clientHandler.pipelineStage)
        
        // 2. Interrupt with new wake word
        clientHandler.onWakeWordDetected()
        
        // 3. Verify S1 was stopped (force-stop audio)
        verify { mediaHandler.pcmMediaPlayer.stop(force = true) }
        
        // 4. Simulate server cleanup
        clientHandler.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 1))
        
        // 5. Verify Session 2 starts
        verify(timeout = 1000) { messenger.sendEvent(match { it.type == "run-pipeline" && it.sessionId == 2 }) }
    }
}
