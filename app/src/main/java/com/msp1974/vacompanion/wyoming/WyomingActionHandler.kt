package com.msp1974.vacompanion.wyoming

import android.content.Context
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Logger
import kotlinx.serialization.json.*

class WyomingActionHandler(
    private val context: Context,
    private val config: APPConfig,
    private val mediaManager: WyomingMediaManager,
    private val log: Logger = Logger()
) {
    fun handleAction(action: String, payloadStr: String, alarmCallback: (Boolean, String) -> Unit) {
        runCatching {
            when (action) {
                "play-media" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    mediaManager.musicPlayer.play(payload["url"]?.jsonPrimitive?.content ?: "")
                    mediaManager.musicPlayer.setVolume(payload["volume"]?.jsonPrimitive?.intOrNull ?: 100)
                }
                "play" -> mediaManager.musicPlayer.resume()
                "pause" -> mediaManager.musicPlayer.pause()
                "stop" -> mediaManager.musicPlayer.stop()
                "set-volume" -> if (payloadStr.isNotEmpty()) {
                    mediaManager.musicPlayer.setVolume(Json.parseToJsonElement(payloadStr).jsonObject["volume"]?.jsonPrimitive?.intOrNull ?: 100)
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
