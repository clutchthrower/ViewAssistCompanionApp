package com.msp1974.vacompanion.wyoming

import kotlinx.serialization.json.*

class WyomingPacket (
    val type: String,
    val data: JsonObject = buildJsonObject {},
    var payload: ByteArray = ByteArray(0)
) {
    private val cachedDataLength: Int by lazy { data.toString().toByteArray().size }

    fun getProp(prop: String): String {
        return data[prop]?.jsonPrimitive?.contentOrNull ?: ""
    }

    fun toMap(): MutableMap<String, Any> {
        return mutableMapOf("type" to type, "data" to data)
    }

    fun getDataLength(): Int {
        return cachedDataLength
    }
}