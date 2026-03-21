package com.msp1974.vacompanion.wyoming

/**
 * Represents the high-level connection state of the Wyoming satellite.
 */
enum class SatelliteState { 
    STOPPED, 
    RUNNING, 
    STARTING, 
    STOPPING 
}

/**
 * Represents the current stage of an active voice pipeline.
 */
enum class PipelineStage { 
    IDLE, 
    LISTENING, 
    STREAMING, 
    AWAITING_TTS 
}

/**
 * Tracks the state of an individual voice pipeline session.
 */
data class PipelineSession(
    val id: Int,
    @Volatile var logicFinished: Boolean = false,
    @Volatile var audioFinished: Boolean = false,
    @Volatile var finalized: Boolean = false,
    @Volatile var forceContinue: Boolean = false
)
