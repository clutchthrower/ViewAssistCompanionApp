package com.msp1974.vacompanion.wyoming

import android.content.Context
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.device.DeviceCapabilitiesData
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.WakeWords
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class WyomingInfoBuilder(private val context: Context, private val config: APPConfig, private val deviceInfo: DeviceCapabilitiesData) {

    @OptIn(ExperimentalSerializationApi::class)
    fun buildInfo(): JsonObject {
        val owwWakeWords = WakeWords(context, "onnx").getWakeWords()
        val owwRTWakeWords = WakeWords(context, "tflite").getWakeWords()

        return buildJsonObject {
            put("version", config.version)
            putJsonArray("asr") {}
            putJsonArray("tts") {}
            putJsonArray("handle") {}
            putJsonArray("intent") {}
            putJsonArray("wake") {
                add(buildJsonObject {
                    put("name", "available_wake_words")
                    putJsonObject("attribution") {
                        put("name", "")
                        put("url", "")
                    }
                    put("installed", true)
                    putJsonArray("models") {
                        owwWakeWords.forEach { (key, value) ->
                            add(buildJsonObject {
                                put("name", key)
                                putJsonObject("attribution") {
                                    put("name", "openwakeword")
                                    put("url", "")
                                }
                                put("installed", true)
                                putJsonArray("languages") { add(JsonPrimitive("en")) }
                                put("phrase", value.name)
                            })
                        }
                        owwRTWakeWords.forEach { (key, value) ->
                            add(buildJsonObject {
                                put("name", key)
                                putJsonObject("attribution") {
                                    put("name", "openwakeword-rt")
                                    put("url", "")
                                }
                                put("installed", true)
                                putJsonArray("languages") { add(JsonPrimitive("en")) }
                                put("phrase", value.name)
                            })
                        }
                        MWW_WAKE_WORDS.forEach { name ->
                            add(buildJsonObject {
                                put("name", name)
                                putJsonObject("attribution") {
                                    put("name", "microwakeword")
                                    put("url", "")
                                }
                                put("installed", true)
                                putJsonArray("languages") { add(JsonPrimitive("en")) }
                                put("phrase", name.replace("_", " "))
                            })
                        }
                    }
                })
            }
            putJsonArray("stt") {}
            putJsonObject("satellite") {
                put("name", "VACA ${config.uuid}")
                putJsonObject("attribution") {
                    put("name", "")
                    put("url", "")
                }
                put("installed", true)
                put("description", "View Assist Companion App")
                put("version", config.version)
                put("area", "")
                put("has_vad", false)
                putJsonObject("snd_format") {
                    put("channels", 1)
                    put("rate", 16000)
                    put("width", 2)
                }
                putJsonArray("active_wake_words") { add(JsonPrimitive(config.wakeWord)) }
                put("max_active_wake_words", 1)

                // TODO: Review if this nested structure should be part of the core satellite object or a custom feature.
                put("capabilities", DeviceCapabilitiesManager.Companion.toJson(deviceInfo))
            }
        }
    }

    companion object {
        private val MWW_WAKE_WORDS = listOf(
            "alexa", "hey_home_assistant", "hey_jarvis", "hey_luna",
            "hey_mycroft", "okay_computer", "okay_nabu"
        )
    }
}