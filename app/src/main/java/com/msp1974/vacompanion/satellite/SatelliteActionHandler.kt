package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Logger
import kotlinx.serialization.json.*

/**
 * Dispatches high-level actions (e.g. playing media, showing messages, alarms) received from the server.
 */
class SatelliteActionHandler(
    private val context: Context,
    private val config: APPConfig,
    private val mediaHandler: SatelliteMediaHandler,
    private val log: Logger = Logger()
) {
    fun handleAction(action: String, payloadStr: String, alarmCallback: (Boolean, String) -> Unit) {
        runCatching {
            when (action) {
                "play-media" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    mediaHandler.musicPlayer.play(payload["url"]?.jsonPrimitive?.content ?: "")
                    mediaHandler.musicPlayer.setVolume(payload["volume"]?.jsonPrimitive?.intOrNull ?: 100)
                }
                "play" -> mediaHandler.musicPlayer.resume()
                "pause" -> mediaHandler.musicPlayer.pause()
                "stop" -> mediaHandler.musicPlayer.stop()
                "set-volume" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    mediaHandler.musicPlayer.setVolume(payload["volume"]?.jsonPrimitive?.intOrNull ?: 100)
                }
                "toast-message" -> if (payloadStr.isNotEmpty()) {
                    val msg = Json.parseToJsonElement(payloadStr).jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    BroadcastSender.sendBroadcast(context, BroadcastSender.TOAST_MESSAGE, msg)
                }
                "refresh" -> config.eventBroadcaster.notifyEvent(Event("refresh", "", ""))
                "screen-wake" -> config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
                "screen-sleep" -> config.eventBroadcaster.notifyEvent(Event("screenSleep", "", ""))
                "wake" -> config.eventBroadcaster.notifyEvent(Event("wakeWordTrigger", "", ""))
                "alarm" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    alarmCallback(payload["activate"]?.jsonPrimitive?.booleanOrNull ?: false, payload["url"]?.jsonPrimitive?.contentOrNull ?: "")
                }
            }
        }.onFailure { log.e("Failed to handle custom action $action: $it") }
    }
}
