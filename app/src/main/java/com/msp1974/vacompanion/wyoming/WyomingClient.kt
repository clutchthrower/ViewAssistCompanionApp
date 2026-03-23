package com.msp1974.vacompanion.wyoming

import kotlinx.serialization.json.JsonObject

/**
 * High-level interface for a Wyoming client connection.
 * Used to abstract the transport implementation from the rest of the application.
 */
interface WyomingClient {
    val clientId: Int
    fun start()
    fun stop()
    fun sendAudio(audio: ByteArray)
    fun sendStatus(data: JsonObject)
    fun sendSetting(name: String, value: Any)
    fun onWakeWordDetected()
    fun processPacket(packet: WyomingPacket)
    fun updateVolume()
    fun isActive(): Boolean
}
