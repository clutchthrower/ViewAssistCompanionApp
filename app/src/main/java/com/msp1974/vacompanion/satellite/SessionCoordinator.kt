package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingPacket
import java.util.concurrent.atomic.AtomicInteger
import androidx.annotation.GuardedBy

/**
 * Co-ordinates voice interaction sessions, ensuring they are executed in order
 * and that rapid-fire wake words are serialized correctly to satisfy backend protocol constraints.
 */
class SessionCoordinator(
    private val log: Logger,
    private val callback: VoiceSession.Callback
) {
    private val sessionIdGenerator = AtomicInteger(0)
    
    @GuardedBy("this")
    var currentSession: VoiceSession? = null
        private set
    
    @GuardedBy("this")
    private var pendingSession: VoiceSession? = null
    
    @GuardedBy("this")
    private var isRestartPending = false

    @Synchronized
    fun isActive() = currentSession != null || isRestartPending

    @Synchronized
    fun reset() {
        currentSession?.stop()
        currentSession = null
        pendingSession = null
        isRestartPending = false
    }

    @Synchronized
    fun onWakeWordDetected() {
        if (currentSession != null) {
            log.d("Interrupting current session ${currentSession?.id}. Waiting for server cleanup.")
            
            // Queue the new session
            val nextId = sessionIdGenerator.incrementAndGet()
            pendingSession = VoiceSession(nextId, log, callback)
            isRestartPending = true
            
            // Stop the current session (sends audio-stop if listening)
            currentSession?.stop()
            currentSession = null // Nullify immediately to stop routing packets
            
            return
        }

        // Normal start
        val nextId = sessionIdGenerator.incrementAndGet()
        currentSession = VoiceSession(nextId, log, callback)
        currentSession?.initiate()
    }

    @Synchronized
    fun processPacket(packet: WyomingPacket) {
        val session = currentSession
        
        // Serialization logic: handle late cleanup events when no session is active.
        if (session == null) {
            if (packet.type == "pipeline-ended") {
                if (isRestartPending) {
                    isRestartPending = false
                    log.d("Cleanup received. Starting pending session ${pendingSession?.id}.")
                    currentSession = pendingSession
                    pendingSession = null
                    currentSession?.initiate()
                }
            } else if (packet.type == "transcribe" || packet.type == "transcript" || packet.type == "audio-start") {
                log.d("Ignoring stale event ${packet.type} during serialization wait.")
            }
            return
        }

        // Protocol de-confliction: route by sessionId if available.
        if (packet.sessionId != null && packet.sessionId != session.id) {
             log.d("Ignoring event ${packet.type} for old session ${packet.sessionId} (current: ${session.id})")
             return
        }

        session.processPacket(packet)
    }

    @Synchronized
    fun onSessionFinalized(session: VoiceSession) {
        if (currentSession == session) {
            currentSession = null
            
            // Start pending if session ended naturally (e.g. after TTS completion)
            if (isRestartPending) {
                 isRestartPending = false
                 log.d("Session finalized. Resuming pending session ${pendingSession?.id}.")
                 currentSession = pendingSession
                 pendingSession = null
                 currentSession?.initiate()
            }
        }
    }
}
