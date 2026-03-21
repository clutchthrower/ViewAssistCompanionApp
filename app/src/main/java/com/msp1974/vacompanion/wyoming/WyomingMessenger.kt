package com.msp1974.vacompanion.wyoming

import com.msp1974.vacompanion.utils.Logger
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.SocketException

/**
 * Low-level messenger for the Wyoming protocol.
 * Responsible for packet framing, JSON serialization, and raw data transmission.
 */
class WyomingMessenger(
    private val clientId: Int,
    private val reader: DataInputStream,
    private val writer: DataOutputStream,
    private val version: String,
    private val log: Logger = Logger()
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends a Wyoming packet to the peer.
     * Performs JSON framing and optional payload serialization.
     */
    @Synchronized
    fun sendEvent(packet: WyomingPacket) {
        if (packet.type != "ping" && packet.type != "pong" && packet.type != "audio-chunk") {
            log.d("Sending to $clientId: ${packet.toMap()}")
        }
        
        // Ensure session_id is included in the data sent to the client if present in the packet.
        val outboundData = if (packet.sessionId != null && packet.data["session_id"] == null) {
            buildJsonObject {
                packet.data.forEach { (k, v) -> put(k, v) }
                put("session_id", packet.sessionId)
            }
        } else {
            packet.data
        }

        val dataBytes = outboundData.toString().toByteArray(Charsets.UTF_8)
        
        val header = buildJsonObject {
            put("type", packet.type)
            put("version", version)
            if (dataBytes.isNotEmpty()) {
                put("data_length", dataBytes.size)
            }
            if (packet.payload.isNotEmpty()) {
                put("payload_length", packet.payload.size)
            }
        }

        val jsonLine = header.toString() + "\n"

        try {
            writer.write(jsonLine.toByteArray(Charsets.UTF_8))
            if (dataBytes.isNotEmpty()) writer.write(dataBytes)
            if (packet.payload.isNotEmpty()) writer.write(packet.payload)
            writer.flush()
        } catch (ex: SocketException) {
            log.e("Error sending event: $ex. Likely just a closed socket.")
            throw ex
        } catch (ex: Exception) {
            log.e("Unknown error sending event: $ex")
            throw ex
        }
    }

    /**
     * Reads the next Wyoming packet from the peer.
     * Blocks until a full packet is available or connection is lost.
     */
    fun readEvent(): WyomingPacket? {
        try {
            val jsonString = StringBuilder()
            var byte = reader.read()
            if (byte == -1) throw java.io.EOFException("Connection closed by peer")

            while (byte != -1 && byte != '\n'.code) {
                jsonString.append(byte.toChar())
                byte = reader.read()
            }
            if (byte == -1 && jsonString.isEmpty()) return null
            if (byte == -1) throw java.io.EOFException("Connection lost during header read")

            val header = json.parseToJsonElement(jsonString.toString()).jsonObject
            val type = header["type"]?.jsonPrimitive?.content ?: return null
            
            val dataLength = header["data_length"]?.jsonPrimitive?.intOrNull ?: 0
            val data = if (dataLength > 0) {
                val dataBytes = ByteArray(dataLength)
                reader.readFully(dataBytes)
                json.parseToJsonElement(String(dataBytes)).jsonObject
            } else {
                header["data"]?.jsonObject ?: buildJsonObject {}
            }

            // Support both session_id and sessionId for compatibility
            val sessionId = data["session_id"]?.jsonPrimitive?.intOrNull 
                ?: data["sessionId"]?.jsonPrimitive?.intOrNull
                ?: header["session_id"]?.jsonPrimitive?.intOrNull

            val packet = WyomingPacket(type, data, sessionId = sessionId)
            val payloadLength = header["payload_length"]?.jsonPrimitive?.intOrNull ?: 0
            if (payloadLength != 0) {
                val payloadBytes = ByteArray(payloadLength)
                reader.readFully(payloadBytes)
                packet.payload = payloadBytes
            }
            return packet

        } catch (ex: java.io.EOFException) {
            throw ex
        } catch (ex: Exception) {
            log.e("Event read exception: ${ex.message}")
        }
        return null
    }
}
