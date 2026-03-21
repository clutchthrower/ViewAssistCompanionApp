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
        fun initiatePipeline(session: VoiceSession)
        fun onSessionFinalized(session: VoiceSession)
    }

    var stage = PipelineStage.IDLE
        private set
    
    @Volatile var logicFinished = false
    @Volatile var audioFinished = false
    @Volatile var finalized = false
    @Volatile var forceContinue = false
    @Volatile var isExpectingTtsAudio = false

    private var isAudioStreamRequested = false

    /**
     * Resets the entire session, notifying the environment if needed.
     */
    fun stop(sendAudioStop: Boolean = true) {
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

    fun initiate() {
        callback.onUpdateVolumeDucking("all", true)
        callback.setPipelineTimeout(15)
        callback.initiatePipeline(this)
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
        callback.onUpdateVolumeDucking("all", true)
        if (stage != PipelineStage.LISTENING) {
            stage = PipelineStage.LISTENING
            isAudioStreamRequested = true
            callback.onRequestAudioStream()
        }
        callback.setPipelineTimeout(10)
    }

    private fun handleTranscript(packet: WyomingPacket) {
        if (isAudioStreamRequested) {
            isAudioStreamRequested = false
            callback.onReleaseAudioStream()
        }
        stage = PipelineStage.IDLE // Done listening
        if (packet.getProp("text").lowercase().contains("never mind")) {
            callback.onUpdateVolumeDucking("all", false)
            stop()
            callback.onSessionFinalized(this)
        } else {
            callback.setPipelineTimeout(15) // Waiting for synthesis
        }
    }

    private fun handleSynthesize(packet: WyomingPacket) {
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
        if (stage == PipelineStage.STREAMING) {
            callback.sendEvent(WyomingPacket("played", buildJsonObject {}))
        }
        
        val hadSynthesize = isExpectingTtsAudio
        audioFinished = true
        
        checkFinalize()
        
        if (hadSynthesize && forceContinue) {
            log.d("Continuing conversation as requested by server (Session $id)")
            callback.notifyContinueConversation()
        }
    }

    private fun handlePipelineEnded(packet: WyomingPacket) {
        logicFinished = true

        if (stage == PipelineStage.AWAITING_TTS || stage == PipelineStage.STREAMING) {
            log.d("Pipeline ended but TTS is in stage $stage. Waiting for audio to complete (Session $id).")
            return
        }

        checkFinalize()
    }

    private fun handlePipelineError(packet: WyomingPacket) {
        val code = packet.getProp("code")
        val text = packet.getProp("text")
        
        if (code != "duplicate_wake_up_detected") {
             val toastMessage = text.ifEmpty { "Error: $code" }
             callback.notifyRecognitionError(code, toastMessage)
        }
        
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
