package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingPacket
import com.msp1974.vacompanion.wyoming.PipelineStage
import kotlinx.serialization.json.*

/**
 * Tracks the state and manages the logic of a single voice interaction pipeline.
 * Encapsulates session-specific variables and state transitions.
 */
class VoiceSession(
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
        fun initiatePipeline(session: VoiceSession, isContinue: Boolean = false)
        fun onSessionFinalized(session: VoiceSession)
    }

    @Volatile
    var stage = PipelineStage.IDLE
        private set
    
    @Volatile var logicFinished = false
    @Volatile var audioFinished = false
    @Volatile var finalized = false
    @Volatile var forceContinue = false
    @Volatile var isExpectingTtsAudio = false
    @Volatile var needsContinue = false
        private set

    private var isAudioStreamRequested = false

    @Volatile
    private var isStopped = false

    /**
     * Resets the entire session, notifying the environment if needed.
     */
    fun stop(sendAudioStop: Boolean = true) {
        isStopped = true
        val wasListening = stage == PipelineStage.LISTENING
        
        stage = PipelineStage.IDLE
        callback.onUpdateVolumeDucking("all", false)
        callback.onStopMediaPlayback()
        if (isAudioStreamRequested) {
            isAudioStreamRequested = false
            callback.onReleaseAudioStream()
        }
        callback.cancelPipelineTimeout()

        if (sendAudioStop && wasListening) {
            callback.sendEvent(WyomingPacket("audio-stop", buildJsonObject { 
                put("timestamp", WyomingPacket.isoNow()) 
            }, sessionId = id))
        }
    }

    fun initiate(isContinue: Boolean = false) {
        if (isStopped) {
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
        if (stage != PipelineStage.IDLE) {
            log.d("Ignoring unexpected transcribe in stage $stage (Session $id)")
            return
        }
        callback.onUpdateVolumeDucking("all", true)
        stage = PipelineStage.LISTENING
        isAudioStreamRequested = true
        callback.onRequestAudioStream()
        callback.setPipelineTimeout(10)
    }

    private fun handleTranscript(packet: WyomingPacket) {
        if (stage != PipelineStage.LISTENING) {
            log.d("Ignoring unexpected transcript in stage $stage (Session $id)")
            return
        }
        
        if (isAudioStreamRequested) {
            isAudioStreamRequested = false
            callback.onReleaseAudioStream()
        }
        
        if (packet.getProp("text").lowercase().contains("never mind")) {
            callback.onUpdateVolumeDucking("all", false)
            stop(sendAudioStop = true)
            callback.onSessionFinalized(this)
        } else {
            stage = PipelineStage.PROCESSING // Waiting for synthesis or pipeline-ended
            callback.setPipelineTimeout(15)
        }
    }

    private fun handleSynthesize(packet: WyomingPacket) {
        // Allow transitions from LISTENING as some Wyoming servers might send synthesis 
        // before the client has received the final transcript (VAD).
        if (stage != PipelineStage.PROCESSING && stage != PipelineStage.LISTENING) {
             log.d("Ignoring unexpected synthesize in stage $stage (Session $id)")
             return
        }
        
        if (stage == PipelineStage.LISTENING) {
            // Force release audio stream if we jumped directly to synthesis phase
            if (isAudioStreamRequested) {
                isAudioStreamRequested = false
                callback.onReleaseAudioStream()
            }
        }
        stage = PipelineStage.AWAITING_TTS
        isExpectingTtsAudio = true
        
        // Extract continue_conversation from nested intent_output
        packet.data["intent_output"]?.jsonObject?.let { output ->
            output["continue_conversation"]?.jsonPrimitive?.booleanOrNull?.let { 
                log.d("Continue conversation set to $it from synthesize event (Session $id)")
                forceContinue = it
            }
        }
        callback.setPipelineTimeout(20)
    }

    private fun handleAudioStart() {
        if (stage != PipelineStage.AWAITING_TTS) {
            log.d("Ignoring unexpected audio-start in stage $stage (Session $id)")
            return
        }
        callback.cancelPipelineTimeout()
        stage = PipelineStage.STREAMING
        callback.onUpdateVolumeDucking("all", true)
        callback.onStartMediaPlayback()
    }

    private fun handleAudioChunk(packet: WyomingPacket) {
        if (stage == PipelineStage.STREAMING) {
            callback.onWriteMediaChunk(packet.payload)
        }
    }

    private fun handleAudioStop() {
        if (stage != PipelineStage.AWAITING_TTS && stage != PipelineStage.STREAMING) {
            log.d("Ignoring unexpected audio-stop in stage $stage (Session $id)")
            return
        }

        if (stage == PipelineStage.STREAMING) {
            callback.sendEvent(WyomingPacket("played", buildJsonObject {}))
        }
        
        val hadSynthesize = isExpectingTtsAudio
        if (hadSynthesize && forceContinue) {
            log.d("Requested conversation continuation (Session $id)")
            needsContinue = true
        }
        
        audioFinished = true
        checkFinalize()
    }

    private fun handlePipelineEnded(packet: WyomingPacket) {
        if (stage == PipelineStage.IDLE || stage == PipelineStage.LISTENING) {
            log.d("Ignoring stale pipeline-ended in stage $stage (Session $id)")
            return
        }
        
        logicFinished = true

        if (stage == PipelineStage.PROCESSING || stage == PipelineStage.AWAITING_TTS || stage == PipelineStage.STREAMING) {
            log.d("Pipeline ended. Waiting for audio completion if needed (Session $id, stage: $stage).")
            if (stage == PipelineStage.PROCESSING) {
                 // If we were processing and it ended without synthesize, we're done.
                 checkFinalize()
            }
            return
        }
    }

    private fun handlePipelineError(packet: WyomingPacket) {
        val code = packet.getProp("code")
        val text = packet.getProp("text")
        
        if (code != "duplicate_wake_up_detected") {
             val toastMessage = text.ifEmpty { "Error: $code" }
             callback.notifyRecognitionError(code, toastMessage)
        }
        needsContinue = false
        stop()
        callback.onSessionFinalized(this)
    }

    private fun checkFinalize() {
        val audioDone = audioFinished || !isExpectingTtsAudio
        if (logicFinished && audioDone && !finalized) {
            finalized = true
            finalizeAndCleanup()
            callback.onSessionFinalized(this)
        }
    }

    private fun finalizeAndCleanup() {
        stop(false)
    }
}
