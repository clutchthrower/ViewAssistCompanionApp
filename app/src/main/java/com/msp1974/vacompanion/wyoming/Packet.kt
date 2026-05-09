package com.msp1974.vacompanion.wyoming

import kotlinx.serialization.json.*

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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WyomingPacket

        if (sessionId != other.sessionId) return false
        if (type != other.type) return false
        if (data != other.data) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sessionId ?: 0
        result = 31 * result + type.hashCode()
        result = 31 * result + data.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}