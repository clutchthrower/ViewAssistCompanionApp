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
    private var currentSession: VoiceSession? = null
    
    @GuardedBy("this")
    private var pendingSession: VoiceSession? = null
    
    @GuardedBy("this")
    private var isRestartPending = false

    val activeSession: VoiceSession?
        @Synchronized get() = currentSession

    @Synchronized
    fun isActive() = currentSession != null || isRestartPending

    fun reset() {
        val sessionToStop = synchronized(this) {
            val toStop = currentSession
            currentSession = null
            pendingSession = null
            isRestartPending = false
            toStop
        }
        sessionToStop?.stop()
    }

    fun onWakeWordDetected() {
        val sessionToStop = synchronized(this) {
            if (currentSession != null) {
                val nextId = sessionIdGenerator.incrementAndGet()
                pendingSession = VoiceSession(nextId, log, callback)
                isRestartPending = true
                
                val toStop = currentSession
                currentSession = null
                toStop
            } else if (isRestartPending) {
                val nextId = sessionIdGenerator.incrementAndGet()
                pendingSession = VoiceSession(nextId, log, callback)
                null
            } else {
                null
            }
        }
        
        if (sessionToStop != null) {
            log.d("Interrupting current session ${sessionToStop.id}. Waiting for server cleanup.")
            sessionToStop.stop()
            return
        }

        val sessionToStart = synchronized(this) {
            if (currentSession != null || isRestartPending) return@synchronized null
            val nextId = sessionIdGenerator.incrementAndGet()
            currentSession = VoiceSession(nextId, log, callback)
            currentSession
        }
        sessionToStart?.initiate()
    }

    fun startContinueConversation() {
        val sessionToStart = synchronized(this) {
            if (currentSession != null || isRestartPending) return@synchronized null
            val nextId = sessionIdGenerator.incrementAndGet()
            currentSession = VoiceSession(nextId, log, callback)
            currentSession
        }
        sessionToStart?.initiate(isContinue = true)
    }

    fun setForceContinue(id: Int, value: Boolean) {
        synchronized(this) {
            if (currentSession?.id == id) {
                currentSession?.forceContinue = value
            }
        }
    }

    fun processPacket(packet: WyomingPacket) {
        val (sessionToProcess, sessionToInitiate) = synchronized(this) {
            val session = currentSession
            
            if (session == null) {
                if (packet.type == "pipeline-ended") {
                    if (isRestartPending) {
                        isRestartPending = false
                        currentSession = pendingSession
                        pendingSession = null
                        Pair<VoiceSession?, VoiceSession?>(null, currentSession)
                    } else Pair<VoiceSession?, VoiceSession?>(null, null)
                } else {
                    if (packet.type == "transcribe" || packet.type == "transcript" || packet.type == "audio-start") {
                        log.d("Ignoring stale event ${packet.type} during serialization wait.")
                    }
                    Pair<VoiceSession?, VoiceSession?>(null, null)
                }
            } else {
                if (packet.sessionId != null && packet.sessionId != session.id) {
                     log.d("Ignoring event ${packet.type} for old session ${packet.sessionId} (current: ${session.id})")
                     Pair<VoiceSession?, VoiceSession?>(null, null)
                } else {
                     Pair<VoiceSession?, VoiceSession?>(session, null)
                }
            }
        }

        sessionToInitiate?.let {
            log.d("Cleanup received. Starting pending session ${it.id}.")
            it.initiate()
        }

        sessionToProcess?.processPacket(packet)
    }

    fun onSessionFinalized(session: VoiceSession) {
        val sessionToInitiate = synchronized(this) {
            if (currentSession == session) {
                currentSession = null
                
                if (isRestartPending) {
                     isRestartPending = false
                     currentSession = pendingSession
                     pendingSession = null
                     currentSession
                } else null
            } else null
        }
        
        sessionToInitiate?.let {
            log.d("Session finalized. Resuming pending session ${it.id}.")
            it.initiate()
        }
    }
}
