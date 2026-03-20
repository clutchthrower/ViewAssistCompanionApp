package com.msp1974.vacompanion.wyoming

import com.msp1974.vacompanion.utils.Logger
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.SocketException
import java.nio.charset.Charset

class WyomingMessenger(
    private val client_id: Int,
    private val reader: DataInputStream,
    private val writer: DataOutputStream,
    private val version: String,
    private val log: Logger = Logger()
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun sendEvent(packet: WyomingPacket, pipelineStatus: PipelineStatus) {
        if (packet.type == "audio-chunk" && pipelineStatus != PipelineStatus.LISTENING) {
            return
        }

        if (packet.type != "ping" && packet.type != "pong" && packet.type != "audio-chunk") {
            log.d("Sending to $client_id: ${packet.toMap()}")
        }
        
        val dataBytes = packet.data.toString().toByteArray(Charset.defaultCharset())
        
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
            writer.write(jsonLine.toByteArray(Charset.defaultCharset()))
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

            val packet = WyomingPacket(type, data)
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
    
    fun available(): Int = reader.available()
}
