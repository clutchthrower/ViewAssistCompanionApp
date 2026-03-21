package com.msp1974.vacompanion.wyoming

/**
 * Represents the current stage of an active voice pipeline.
 * Part of the Wyoming protocol state machine.
 */
enum class PipelineStage { 
    IDLE, 
    LISTENING, 
    STREAMING, 
    AWAITING_TTS 
}
