package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingPacket
import com.msp1974.vacompanion.wyoming.WyomingPipelineStage
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.junit4.MockKRule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SatelliteVoiceSessionTest {

    @get:Rule
    val mockkRule = MockKRule(this)

    @MockK(relaxed = true)
    lateinit var log: Logger

    @MockK(relaxed = true)
    lateinit var callback: SatelliteVoiceSession.Callback

    private lateinit var session: SatelliteVoiceSession

    @Before
    fun setUp() {
        session = SatelliteVoiceSession(1, log, callback)
    }

    @Test
    fun `test needsContinue state survives a partial cleanup`() {
        // 1. Initialized
        session.initiate()
        
        // 2. Synthesize with continue_conversation=true
        val synthesizeData = buildJsonObject {
            putJsonObject("intent_output") { put("continue_conversation", true) }
        }
        session.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        session.processPacket(WyomingPacket("synthesize", synthesizeData))
        
        // Stage is AWAITING_TTS, logic is NOT finished, audio is NOT finished
        assertEquals(WyomingPipelineStage.AWAITING_TTS, session.stage)
        assertTrue(session.needsContinue) // Should be true now

        // 3. Logic ends (pipeline-ended)
        session.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}))
        assertTrue(session.logicFinished)
        
        // Session should NOT be finalized yet because audio (TTS) hasn't started/stopped
        assertTrue(session.needsContinue)

        // 4. Audio starts and stops
        session.processPacket(WyomingPacket("audio-start", buildJsonObject {}))
        session.processPacket(WyomingPacket("audio-stop", buildJsonObject {}))
        
        // 5. Final verification: onSessionFinalized should be called, and at that point needsContinue = true
        verify { callback.onSessionFinalized(match { it.id == 1 && it.needsContinue }) }
    }

    @Test
    fun `test atomic status handles out-of-order pipeline-ended`() {
        session.initiate()
        
        // 1. Move to LISTENING to acknowledge we have an active logic path for THIS session
        session.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        
        // 2. Final transcript received, move to PROCESSING
        session.processPacket(WyomingPacket("transcript", buildJsonObject { put("text", "hello") }))
        assertEquals(WyomingPipelineStage.PROCESSING, session.stage)
        
        // 3. Server sends pipeline-ended
        session.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}))
        
        assertTrue(session.logicFinished)
        
        // Now transcribe arrives
        session.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        // If another transcribe arrives, we ignore it (idempotency)
        assertEquals(WyomingPipelineStage.PROCESSING, session.stage)
        
        // And synthesize (which would normally happen before pipeline-ended)
        session.processPacket(WyomingPacket("synthesize", buildJsonObject {}))
        assertEquals(WyomingPipelineStage.AWAITING_TTS, session.stage)
        
        // Session is still NOT finalized because no audio events yet
        assertTrue(session.logicFinished)
        
        // Complete the session
        session.processPacket(WyomingPacket("audio-start", buildJsonObject {}))
        session.processPacket(WyomingPacket("audio-stop", buildJsonObject {}))
        
        verify(exactly = 1) { callback.onSessionFinalized(any()) }
    }

    @Test
    fun `test stop interrupts correctly and prevents continuation`() {
        session.initiate()
        
        // 1. Setup session to want continuation
        session.processPacket(WyomingPacket("transcribe", buildJsonObject {}))
        session.processPacket(WyomingPacket("synthesize", buildJsonObject {
            putJsonObject("intent_output") { put("continue_conversation", true) }
        }))
        
        // 2. Interrupt halfway through
        session.stop()
        
        // 3. Complete the logic/audio events
        session.processPacket(WyomingPacket("pipeline-ended", buildJsonObject {}))
        session.processPacket(WyomingPacket("audio-start", buildJsonObject {}))
        session.processPacket(WyomingPacket("audio-stop", buildJsonObject {}))
        
        // 4. Verification: onSessionFinalized should be called during stop() or subsequent packets
        // When stop() is called, isInterrupted becomes true, triggering finalize.
        // At that point needsContinue must be false.
        verify(atLeast = 1) { callback.onSessionFinalized(any()) }
        assertFalse("Session should not want continuation after being stopped", session.needsContinue)
    }
}
