package com.msp1974.vacompanion.wyoming

import kotlinx.serialization.json.*

class WyomingPacket (
    val type: String,
    val data: JsonObject = buildJsonObject {},
    var payload: ByteArray = ByteArray(0)
) {
    fun getProp(prop: String): String {
        return data[prop]?.jsonPrimitive?.contentOrNull ?: ""
    }

    fun toMap(): Map<String, Any> {
        return mapOf("type" to type, "data" to data)
    }
}