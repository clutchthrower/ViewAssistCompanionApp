package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingPacket
import com.msp1974.vacompanion.wyoming.WyomingPipelineStage
import kotlinx.serialization.json.*
import androidx.annotation.GuardedBy

/**
 * Tracks the state and manages the logic of a single voice interaction pipeline.
 * Encapsulates session-specific variables and state transitions.
 */
class SatelliteVoiceSession(
    val id: Int,
    private val log: Logger,
    private val callback: Callback
) {
    interface Callback {
        fun sendEvent(packet: WyomingPacket)
        fun onRequestAudioStream()
        fun onReleaseAudioStream()
        fun onStartMediaPlayback()
        fun onWriteMediaChunk(payload: ByteArray)
        fun onStopMediaPlayback()
        fun onUpdateVolumeDucking(key: String, duck: Boolean)
        fun notifyContinueConversation(phrase: String = "")
        fun notifyRecognitionError(code: String, text: String)
        fun setPipelineTimeout(seconds: Int)
        fun cancelPipelineTimeout()
        fun initiatePipeline(session: SatelliteVoiceSession, isContinue: Boolean = false)
        fun onSessionFinalized(session: SatelliteVoiceSession)
    }

    private data class SessionStatus(
        val stage: WyomingPipelineStage = WyomingPipelineStage.IDLE,
        val serverLogicFinished: Boolean = false,
        val localAudioFinished: Boolean = false,
        val isExpectingTts: Boolean = false,
        val forceContinue: Boolean = false,
        val isInterrupted: Boolean = false,
        val isFinalized: Boolean = false,
        val isAudioStreamRequested: Boolean = false
    ) {
        val isNaturallyFinished: Boolean
            get() = serverLogicFinished && (localAudioFinished || !isExpectingTts)

        val needsContinue: Boolean
            get() = isExpectingTts && forceContinue && !isInterrupted
    }

    @GuardedBy("this")
    private var status = SessionStatus()

    val stage: WyomingPipelineStage
        @Synchronized get() = status.stage
    
    val logicFinished: Boolean
        @Synchronized get() = status.serverLogicFinished
        
    val finalized: Boolean
        @Synchronized get() = status.isFinalized

    var forceContinue: Boolean
        get() = synchronized(this) { status.forceContinue }
        set(value) = synchronized(this) { status = status.copy(forceContinue = value) }

    val needsContinue: Boolean
        @Synchronized get() = status.needsContinue

    /**
     * Terminate the session immediately, usually due to interruption or reset.
     */
    fun stop(sendAudioStop: Boolean = true) {
        val (wasListening, wasAudioRequested) = synchronized(this) {
            val s = status
            if (s.isFinalized || s.isInterrupted) return // Already ending
            
            val listening = s.stage == WyomingPipelineStage.LISTENING
            val requested = s.isAudioStreamRequested
            
            status = s.copy(
                stage = WyomingPipelineStage.IDLE,
                isInterrupted = true,
                isAudioStreamRequested = false
            )
            Pair(listening, requested)
        }

        cleanupResources()

        if (sendAudioStop && wasListening) {
            callback.sendEvent(WyomingPacket("audio-stop", buildJsonObject { 
                put("timestamp", WyomingPacket.isoNow()) 
            }, sessionId = id))
        }
        
        if (wasAudioRequested) {
            callback.onReleaseAudioStream()
        }
        
        checkFinalize()
    }

    private fun cleanupResources() {
        callback.onUpdateVolumeDucking("all", false)
        callback.onStopMediaPlayback()
        callback.cancelPipelineTimeout()
    }

    fun initiate(isContinue: Boolean = false) {
        val isAborting = synchronized(this) { status.isInterrupted }
        if (isAborting) {
            log.d("Session $id was stopped before it could be initiated. Aborting.")
            return
        }
        callback.onUpdateVolumeDucking("all", true)
        callback.setPipelineTimeout(15)
        callback.initiatePipeline(this, isContinue)
    }

    fun processPacket(packet: WyomingPacket) {
        when (packet.type) {
            "transcribe" -> handleTranscribe()
            "voice-started" -> callback.setPipelineTimeout(30)
            "voice-stopped" -> callback.setPipelineTimeout(15)
            "transcript" -> handleTranscript(packet)
            "synthesize" -> handleSynthesize(packet)
            "audio-start" -> handleAudioStart()
            "audio-chunk" -> handleAudioChunk(packet)
            "audio-stop" -> handleAudioStop()
            "pipeline-ended" -> handlePipelineEnded(packet)
            "error" -> handlePipelineError(packet)
        }
    }

    private fun handleTranscribe() {
        val shouldProceed = synchronized(this) {
            if (status.stage != WyomingPipelineStage.IDLE) {
                log.d("Ignoring unexpected transcribe in stage ${status.stage} (Session $id)")
                false
            } else {
                status = status.copy(stage = WyomingPipelineStage.LISTENING, isAudioStreamRequested = true)
                true
            }
        }
        if (shouldProceed) {
            callback.onUpdateVolumeDucking("all", true)
            callback.onRequestAudioStream()
            callback.setPipelineTimeout(10)
        }
    }

    private fun handleTranscript(packet: WyomingPacket) {
        val shouldReleaseAudio = synchronized(this) {
            if (status.stage != WyomingPipelineStage.LISTENING) {
                log.d("Ignoring unexpected transcript in stage ${status.stage} (Session $id)")
                false
            } else {
                val wasRequested = status.isAudioStreamRequested
                status = status.copy(stage = WyomingPipelineStage.PROCESSING, isAudioStreamRequested = false)
                wasRequested
            }
        }
        if (shouldReleaseAudio) {
            callback.onReleaseAudioStream()
        }
        callback.setPipelineTimeout(15)
    }

    private fun handleSynthesize(packet: WyomingPacket) {
        var forceValue: Boolean? = null
        
        val shouldRelease = synchronized(this) {
            if (status.stage != WyomingPipelineStage.PROCESSING && status.stage != WyomingPipelineStage.LISTENING) {
                 log.d("Ignoring unexpected synthesize in stage ${status.stage} (Session $id)")
                 return
            }

            // Extract continue_conversation from nested intent_output
            packet.data["intent_output"]?.jsonObject?.let { output ->
                output["continue_conversation"]?.jsonPrimitive?.booleanOrNull?.let { 
                    forceValue = it
                }
            }

            val wasRequested = status.isAudioStreamRequested
            status = status.copy(
                stage = WyomingPipelineStage.AWAITING_TTS, 
                isExpectingTts = true,
                isAudioStreamRequested = false,
                forceContinue = forceValue ?: status.forceContinue
            )
            
            wasRequested
        }
        
        if (shouldRelease) {
            callback.onReleaseAudioStream()
        }
        
        forceValue?.let { log.d("Continue conversation set to $it from synthesize event (Session $id)") }
        callback.setPipelineTimeout(20)
    }

    private fun handleAudioStart() {
        synchronized(this) {
            if (status.stage != WyomingPipelineStage.AWAITING_TTS) {
                log.d("Ignoring unexpected audio-start in stage ${status.stage} (Session $id)")
                return
            }
            status = status.copy(stage = WyomingPipelineStage.STREAMING)
        }
        callback.cancelPipelineTimeout()
        callback.onUpdateVolumeDucking("all", true)
        callback.onStartMediaPlayback()
    }

    private fun handleAudioChunk(packet: WyomingPacket) {
        if (synchronized(this) { status.stage == WyomingPipelineStage.STREAMING }) {
            callback.onWriteMediaChunk(packet.payload)
        }
    }

    private fun handleAudioStop() {
        synchronized(this) {
            if (status.stage != WyomingPipelineStage.AWAITING_TTS && status.stage != WyomingPipelineStage.STREAMING) {
                log.d("Ignoring unexpected audio-stop in stage ${status.stage} (Session $id)")
                return
            }

            if (status.stage == WyomingPipelineStage.STREAMING) {
                callback.sendEvent(WyomingPacket("played", buildJsonObject {}))
            }
            
            if (status.isExpectingTts && status.forceContinue) {
                log.d("Requested conversation continuation (Session $id)")
            }
            
            status = status.copy(localAudioFinished = true)
        }
        checkFinalize()
    }

    private fun handlePipelineEnded(packet: WyomingPacket) {
        synchronized(this) {
            if (status.stage == WyomingPipelineStage.IDLE || status.stage == WyomingPipelineStage.LISTENING) {
                log.d("Ignoring stale pipeline-ended in stage ${status.stage} (Session $id)")
                return
            }
            status = status.copy(serverLogicFinished = true)
            if (!status.isNaturallyFinished) {
                log.d("Pipeline logic processing complete. Waiting for local audio/TTS playback (Session $id)")
            }
        }
        checkFinalize()
    }

    private fun handlePipelineError(packet: WyomingPacket) {
        val code = packet.getProp("code")
        val text = packet.getProp("text")
        callback.notifyRecognitionError(code, text.ifEmpty { "Error: $code" })
        
        stop()
    }

    private fun checkFinalize() {
        val shouldFinalize = synchronized(this) {
            val s = status
            if ((s.isNaturallyFinished || s.isInterrupted) && !s.isFinalized) {
                status = s.copy(isFinalized = true)
                true
            } else false
        }

        if (shouldFinalize) {
            finalizeAndCleanup()
            callback.onSessionFinalized(this)
        }
    }

    private fun finalizeAndCleanup() {
        // Naturally finalizing doesn't need to send audio-stop 
        // as it's either already sent or we are in a state that doesn't need it.
        stop(sendAudioStop = false)
    }
}
