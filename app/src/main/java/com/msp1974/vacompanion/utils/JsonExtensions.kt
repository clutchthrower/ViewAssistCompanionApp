package com.msp1974.vacompanion.utils

import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/**
 * Extension to safely convert a JsonElement (if it's a primitive number) to an Int,
 * providing rounding support for floating point numbers received from JSON.
 */
fun JsonElement?.asIntOrNull(): Int? {
    val primitive = this?.jsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.doubleOrNull?.roundToInt()
}
