package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingPacket
import java.util.concurrent.atomic.AtomicInteger
import androidx.annotation.GuardedBy
import com.msp1974.vacompanion.wyoming.WyomingProtocolState

/**
 * Co-ordinates voice interaction sessions, ensuring they are executed in order
 * and that rapid-fire wake words are serialized correctly to satisfy backend protocol constraints.
 */
class SatelliteSessionCoordinator(
    private val log: Logger,
    private val callback: SatelliteVoiceSession.Callback
) {
    private val sessionIdGenerator = AtomicInteger(0)

    @GuardedBy("this")
    private var protocolState: WyomingProtocolState = WyomingProtocolState.Idle
    
    @GuardedBy("this")
    private val sessionRegistry = mutableMapOf<Int, SatelliteVoiceSession>()
    
    @GuardedBy("this")
    private var activeId: Int? = null

    @GuardedBy("this")
    private var pendingSession: SatelliteVoiceSession? = null

    val activeSession: SatelliteVoiceSession?
        @Synchronized get() = activeId?.let { sessionRegistry[it] }

    val currentProtocolState: WyomingProtocolState
        @Synchronized get() = protocolState

    val currentPendingSession: SatelliteVoiceSession?
        @Synchronized get() = pendingSession

    val registry: Map<Int, SatelliteVoiceSession>
        @Synchronized get() = sessionRegistry.toMap()

    @Synchronized
    fun isActive() = sessionRegistry.isNotEmpty() || pendingSession != null || !protocolState.isReady

    @Synchronized
    fun reset() {
        val allSessions = sessionRegistry.values.toList()
        sessionRegistry.clear()
        pendingSession = null
        activeId = null
        protocolState = WyomingProtocolState.Idle
        allSessions.forEach { it.stop() }
    }

    fun onWakeWordDetected() {
        val nextId = sessionIdGenerator.incrementAndGet()
        val next = SatelliteVoiceSession(nextId, log, callback)
        var sessionToStop: SatelliteVoiceSession? = null
        var sessionToInitiate: SatelliteVoiceSession? = null

        synchronized(this) {
            // Interruption: stop the currently primary session if any
            val currentPrimary = activeId?.let { sessionRegistry[it] }
            if (currentPrimary != null) {
                sessionToStop = currentPrimary
            }

            // Can we start the new pipeline immediately?
            // Protocol is free if it's Idle OR if it's occupied by a session that already finished logic.
            val occupyingId = (protocolState as? WyomingProtocolState.Occupied)?.sessionId
            val canStartNow = protocolState.isReady || (occupyingId != null && sessionRegistry[occupyingId]?.logicFinished == true)

            if (canStartNow) {
                activeId = next.id
                sessionRegistry[next.id] = next
                protocolState = WyomingProtocolState.Occupied(next.id)
                sessionToInitiate = next
            } else {
                pendingSession?.let { log.d("Discarding previous pending session ${it.id} for newer session ${next.id}") }
                pendingSession = next
            }
        }

        sessionToStop?.let {
            log.d("Interrupting session ${it.id} for new wake word.")
            it.stop()
        }
        sessionToInitiate?.initiate()
    }

    fun startContinueConversation() {
        val nextId = sessionIdGenerator.incrementAndGet()
        val next = SatelliteVoiceSession(nextId, log, callback)
        val sessionToInitiate = synchronized(this) {
            // Protocol check for continuation (usually Idle because previous prompt ended logic)
            if (protocolState.isReady) {
                activeId = next.id
                sessionRegistry[next.id] = next
                protocolState = WyomingProtocolState.Occupied(next.id)
                next
            } else {
                pendingSession?.let { log.d("Discarding previous pending session ${it.id} for newer session ${next.id}") }
                pendingSession = next
                null
            }
        }
        sessionToInitiate?.initiate(isContinue = true)
    }

    @Synchronized
    fun setForceContinue(id: Int, value: Boolean) {
        sessionRegistry[id]?.forceContinue = value
    }

    fun processPacket(packet: WyomingPacket) {
        var sessionToProcess: SatelliteVoiceSession? = null
        var sessionToInitiate: SatelliteVoiceSession? = null

        synchronized(this) {
            // 1. Precise Routing: route by sessionId if present, otherwise fallback to primary active
            val sid = packet.sessionId
            sessionToProcess = if (sid != null) {
                sessionRegistry[sid]
            } else {
                activeId?.let { sessionRegistry[it] }
            }

            if (sessionToProcess == null && isStaleEvent(packet.type)) {
                log.d("Ignoring generic event ${packet.type} during serialization wait.")
            }

            // 2. Protocol State Transition
            // Even if the session is "stale" from our registry's perspective (e.g. interrupted),
            // we MUST process pipeline-ended to free the protocol state for the next session.
            if (packet.type == "pipeline-ended") {
                if (sid == null || protocolState.isOccupiedBy(sid)) {
                    protocolState = WyomingProtocolState.Idle
                    // Protocol is free! Try to promote pending session.
                    promotePending()?.let {
                        sessionToInitiate = it
                    }
                }
            }
        }

        sessionToInitiate?.let {
            log.d("Server refined. Initiating pending session ${it.id}.")
            it.initiate()
        }
        sessionToProcess?.processPacket(packet)
    }

    private fun isStaleEvent(type: String) = 
        type == "transcribe" || type == "transcript" || type == "audio-start"

    @GuardedBy("this")
    private fun promotePending(): SatelliteVoiceSession? {
        val next = pendingSession ?: return null
        pendingSession = null
        activeId = next.id
        sessionRegistry[next.id] = next
        protocolState = WyomingProtocolState.Occupied(next.id)
        return next
    }

    @Synchronized
    fun onSessionFinalized(session: SatelliteVoiceSession) {
        sessionRegistry.remove(session.id)
        if (activeId == session.id) {
            activeId = null
        }
        
        // Note: We used to force-release here, but that broke interruption flows 
        // where we must wait for the server's pipeline-ended. The protocol 
        // itself (via processPacket) is now the single source of truth for freeing the lock.
    }
}


