package com.msp1974.vacompanion.audio

import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.media.AudioManager
import com.msp1974.vacompanion.satellite.SatelliteServer
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.VolumeObserver
import timber.log.Timber

/**
 * Manages synchronization of system volumes (Music, Notification, Alarm)
 * between the Android device and Home Assistant.
 */
class VolumeSyncManager(private val context: Context, private val server: SatelliteServer) {
    private val config = APPConfig.getInstance(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val streamManager = StreamVolumeManager(context)
    
    // Observer for physical hardware button changes
    private val volumeObserver = VolumeObserver(context) { media, voice, alarm ->
        syncToHomeAssistant(media, voice, alarm)
    }

    /**
     * Initializes current truth values on connection.
     */
    fun onConnected() {
        if (!DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)) {
            config.voiceVolume = audioManager.getStreamVolume(AudioStream.Voice.STREAM)
        }
        config.mediaVolume = audioManager.getStreamVolume(AudioStream.Media.STREAM)
        config.alarmVolume = audioManager.getStreamVolume(AudioStream.Alarm.STREAM)
        config.doNotDisturb = DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)

        // Push initial truth to HA (hardware-mastered streams only)
        server.sendSetting("media_volume", config.mediaVolume)
        server.sendSetting("voice_volume", config.voiceVolume)
        server.sendSetting("alarm_volume", config.alarmVolume)
        server.sendSetting("do_not_disturb", config.doNotDisturb)
        
        volumeObserver.register()
    }

    /**
     * Cleans up on disconnection.
     */
    fun onDisconnected() {
        volumeObserver.unregister()
    }

    /**
     * Responds to volume and system status changes from Home Assistant.
     */
    fun onSettingChange(name: String, value: Any) {
        try {
            when (name) {
                "media_volume" -> {
                    streamManager.setVolume(AudioStream.Media.STREAM, value as Int)
                }
                "voice_volume" -> {
                    if (!DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)) {
                        streamManager.setVolume(AudioStream.Voice.STREAM, value as Int)
                    }
                }
                "alarm_volume" -> {
                    streamManager.setVolume(AudioStream.Alarm.STREAM, value as Int)
                }
                "media_player_gain" -> {
                    // Software gain for the Home Assistant media player entity
                    // The property was already updated via processSettings, we just need to notify the UI/Players.
                    server.pipelineClient?.updateVolume()
                }
                "do_not_disturb" -> {
                    setDoNotDisturb(value as Boolean)
                    server.sendSetting("do_not_disturb", value)
                }
            }
        } catch (e: Exception) {
            Timber.e("Error applying setting change $name ($value): ${e.message}")
        }
    }

    private fun setDoNotDisturb(enable: Boolean) {
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val isInDND = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (isInDND != enable) {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                Timber.d("Setting do not disturb to $enable")
                if (enable) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } else {
                Timber.w("Unable to set do not disturb, notification policy access not granted")
                config.eventBroadcaster.notifyEvent(
                    Event(
                        "showToastMessage",
                        "",
                        "Unable to set do not disturb. Permission not granted."
                    )
                )
            }
        }
    }

    private fun syncToHomeAssistant(media: Int, voice: Int, alarm: Int) {
        if (config.mediaVolume != media) {
            Timber.d("VolumeSyncManager - Local media volume change: $media (was ${config.mediaVolume})")
            config.mediaVolume = media
            server.sendSetting("media_volume", media)
        }

        if (config.voiceVolume != voice) {
            Timber.d("VolumeSyncManager - Local voice volume change: $voice (was ${config.voiceVolume})")
            config.voiceVolume = voice
            server.sendSetting("voice_volume", voice)
        }

        if (config.alarmVolume != alarm) {
            Timber.d("VolumeSyncManager - Local alarm volume change: $alarm (was ${config.alarmVolume})")
            config.alarmVolume = alarm
            server.sendSetting("alarm_volume", alarm)
        }
    }
}
