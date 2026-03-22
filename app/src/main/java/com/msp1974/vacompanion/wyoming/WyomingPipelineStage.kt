package com.msp1974.vacompanion.wyoming

/**
 * Represents the current stage of an active voice pipeline.
 * Part of the Wyoming protocol state machine.
 */
enum class WyomingPipelineStage { 
    IDLE, 
    LISTENING, 
    PROCESSING,
    AWAITING_TTS,
    STREAMING
}
