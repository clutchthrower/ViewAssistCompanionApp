package com.msp1974.vacompanion.wyoming

import kotlinx.serialization.json.*
import com.msp1974.vacompanion.utils.asIntOrNull
import java.time.format.DateTimeFormatter
import java.time.Instant

data class WyomingPacket (
    val type: String,
    val data: JsonObject = buildJsonObject {},
    var payload: ByteArray = ByteArray(0),
    val sessionId: Int? = null
) {
    /**
     * Extracts a string property from the packet data.
     * Returns an empty string if the property is missing or not a primitive.
     */
    fun getProp(prop: String): String {
        val element = data[prop] ?: return ""
        return if (element is JsonPrimitive) {
            element.contentOrNull ?: ""
        } else {
            element.toString() // Standard stringification for non-primitives
        }
    }

    /**
     * Extracts a JsonObject from the packet data.
     */
    fun getJsonObject(prop: String): JsonObject? {
        return data[prop] as? JsonObject
    }

    /**
     * Extracts a JsonArray from the packet data.
     */
    fun getJsonArray(prop: String): JsonArray? {
        return data[prop] as? JsonArray
    }

    /**
     * Extracts a boolean from the packet data.
     */
    fun getBool(prop: String, default: Boolean = false): Boolean {
        return data[prop]?.jsonPrimitive?.booleanOrNull ?: default
    }

    /**
     * Extracts an integer from the packet data.
     */
    fun getInt(prop: String, default: Int = 0): Int {
        return data[prop].asIntOrNull() ?: default
    }

    fun toMap(): Map<String, Any> {
        return mapOf("type" to type, "data" to data)
    }

    companion object {
        private val isoFormatter = DateTimeFormatter.ISO_INSTANT
        fun isoNow(): String = isoFormatter.format(Instant.now())
    }
}