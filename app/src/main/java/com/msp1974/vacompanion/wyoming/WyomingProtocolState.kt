package com.msp1974.vacompanion.wyoming

/**
 * Tracks the server's pipeline availability as defined by the Wyoming protocol rules.
 * 
 * In this protocol, a server is 'Occupied' from the moment 'run-pipeline' is sent 
 * until 'pipeline-ended' is received. Starting a new pipeline before the previous 
 * one finishes server-side logic can lead to out-of-order execution or backend failures.
 */
sealed class WyomingProtocolState {
    object Idle : WyomingProtocolState()
    
    data class Occupied(val sessionId: Int) : WyomingProtocolState()

    val isReady: Boolean
        get() = this is Idle

    fun isOccupiedBy(id: Int): Boolean = (this as? Occupied)?.sessionId == id
}
