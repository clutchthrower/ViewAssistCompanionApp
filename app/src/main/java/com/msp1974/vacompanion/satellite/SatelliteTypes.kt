package com.msp1974.vacompanion.satellite

import com.msp1974.vacompanion.wyoming.PipelineStage

/**
 * Represents the high-level connection state of the Wyoming satellite service.
 */
enum class SatelliteState { 
    STOPPED, 
    RUNNING, 
    STARTING, 
    STOPPING 
}

