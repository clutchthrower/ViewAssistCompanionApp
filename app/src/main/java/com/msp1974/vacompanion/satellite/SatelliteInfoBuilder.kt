package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.DeviceCapabilitiesData
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.WakeWords
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

/**
 * Builds the 'info' response for the Wyoming server, containing capabilities and satellite metadata.
 */
class SatelliteInfoBuilder(
    private val context: Context,
    private val config: APPConfig,
    private val deviceInfo: DeviceCapabilitiesData
) {
    @OptIn(ExperimentalSerializationApi::class)
    fun buildInfo(): JsonObject {
        val owwWakeWords = WakeWords(context).getWakeWords()
        
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
                    put("channels", config.audioChannels)
                    put("rate", config.sampleRate)
                    put("width", config.audioWidth)
                }
                putJsonArray("active_wake_words") { add(JsonPrimitive(config.wakeWord)) }
                put("max_active_wake_words", 1)
                
                put("capabilities", DeviceCapabilitiesManager.toJson(deviceInfo))
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
