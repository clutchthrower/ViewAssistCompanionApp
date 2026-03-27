package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingPacket
import com.msp1974.vacompanion.wyoming.WyomingPipelineStage
import com.msp1974.vacompanion.wyoming.WyomingProtocolState
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SatelliteSessionCoordinatorTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK(relaxed = true)
    lateinit var log: Logger

    @MockK(relaxed = true)
    lateinit var callback: SatelliteVoiceSession.Callback

    private lateinit var coordinator: SatelliteSessionCoordinator

    @Before
    fun setUp() {
        coordinator = SatelliteSessionCoordinator(log, callback)
    }

    @Test
    fun `test state transitions from Idle to Occupied to Idle`() {
        assertTrue(coordinator.currentProtocolState is WyomingProtocolState.Idle)

        // 1. Wake word initiates Session 1
        coordinator.onWakeWordDetected()
        val s1 = coordinator.activeSession!!
        assertEquals(1, s1.id)
        assertTrue(coordinator.currentProtocolState.isOccupiedBy(1))

        // 2. Logic ends for Session 1 (pipeline-ended)
        coordinator.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 1))
        
        // Protocol should return to Idle even if audio (Session 1 object) still exists
        assertTrue(coordinator.currentProtocolState is WyomingProtocolState.Idle)
        assertNotNull(coordinator.activeSession) // It's still active as an object until finalized
    }

    @Test
    fun `test pending session promotion on completion`() {
        // 1. Start Session 1
        coordinator.onWakeWordDetected()
        assertTrue(coordinator.currentProtocolState.isOccupiedBy(1))

        // 2. Wake word for Session 2 while 1 is occupied
        // In the handler, calling onWakeWordDetected will interrupt S1
        coordinator.onWakeWordDetected() 
        
        // State should still be Occupied by S1 (pending its cleanup)
        assertTrue(coordinator.currentProtocolState.isOccupiedBy(1))
        assertNotNull(coordinator.activeSession)
        assertEquals(1, coordinator.activeSession!!.id)
        
        // S2 should be pending
        assertEquals(2, coordinator.currentPendingSession?.id)

        // 3. Server sends pipeline-ended for Session 1
        coordinator.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 1))
        
        // S2 should have been initiated immediately in the same call
        verify { callback.initiatePipeline(match { it.id == 2 }, any()) }
        assertTrue(coordinator.currentProtocolState.isOccupiedBy(2))
        assertEquals(2, coordinator.activeSession!!.id)
        assertNull(coordinator.currentPendingSession)
    }

    @Test
    fun `test stale pipeline-ended frees the protocol state`() {
        // Session 1 is already gone or unknown, but server sends pipeline-ended
        // This can happen if a connection is lost and resumed, or if there's a protocol mismatch
        coordinator.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 99))
        
        // The coordinator should ensure the protocol is Idle
        assertTrue(coordinator.currentProtocolState is WyomingProtocolState.Idle)
    }

    @Test
    fun `test continue conversation starts immediately if protocol is idle`() {
        // 1. Simulate Session 1 ending and requesting continue
        coordinator.onWakeWordDetected()
        coordinator.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 1))
        
        // Now protocol is Idle, but S1 object is still finalizing (playing audio)
        assertTrue(coordinator.currentProtocolState is WyomingProtocolState.Idle)
        
        // 2. Session 1 finalized with needsContinue=true
        val s1 = coordinator.activeSession!!
        // Mock Session behavior (normally we'd need to send synthesize event, but we can verify coordination)
        
        coordinator.startContinueConversation()
        val s2 = coordinator.activeSession
        assertNotNull(s2)
        assertEquals(2, s2!!.id)
        assertTrue(coordinator.currentProtocolState.isOccupiedBy(2))
    }

    @Test
    fun `test continue conversation is queued if protocol is occupied`() {
        // 1. Session 1 is playing audio (logic already finished)
        coordinator.onWakeWordDetected()
        coordinator.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}, sessionId = 1))
        assertTrue(coordinator.currentProtocolState is WyomingProtocolState.Idle)

        // 2. UNRELATED new session starts (e.g. user pressed a button)
        coordinator.onWakeWordDetected() // S2 starts
        assertTrue(coordinator.currentProtocolState.isOccupiedBy(2))
        
        // 3. Session 1 finalizes and wants to continue
        coordinator.startContinueConversation()
        
        assertEquals(2, coordinator.activeSession?.id)
        assertEquals(3, coordinator.currentPendingSession?.id)
        assertTrue(coordinator.currentProtocolState.isOccupiedBy(2))
    }
}
