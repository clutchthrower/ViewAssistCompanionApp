package com.msp1974.vacompanion.wyoming

import kotlinx.serialization.json.*
import java.time.format.DateTimeFormatter
import java.time.Instant

data class WyomingPacket (
    val type: String,
    val data: JsonObject = buildJsonObject {},
    var payload: ByteArray = ByteArray(0),
    val sessionId: Int? = null
) {
    fun getProp(prop: String): String {
        // TODO: Implement a more robust parsing mechanism for complex JSON properties.
        // Currently, we stringify objects/arrays to ensure they are readable by the consumer.
        val value = data[prop] ?: return ""
        return if (value is JsonPrimitive) {
            value.contentOrNull ?: ""
        } else {
            value.toString()
        }
    }

    fun toMap(): Map<String, Any> {
        return mapOf("type" to type, "data" to data)
    }

    companion object {
        private val isoFormatter = DateTimeFormatter.ISO_INSTANT
        fun isoNow(): String = isoFormatter.format(Instant.now())
    }
}