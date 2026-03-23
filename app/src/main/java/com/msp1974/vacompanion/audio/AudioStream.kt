package com.msp1974.vacompanion.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.provider.Settings

/**
 * Centralized mapping of VACA audio features to Android hardware streams and usage types.
 * 
 * This singleton ensures that all audio engines (Voice, Media, Alarm) consistently 
 * target the correct physical hardware sliders on the device, according to the 
 * Layered Volume Model.
 */
object AudioStream {
    /**
     * Settings for general media playback (music, video, etc.).
     * Controlled by the 'Music' slider and synchronized with HA 'music_volume'.
     */
    object Media {
        const val STREAM = AudioManager.STREAM_MUSIC
        const val USAGE = AudioAttributes.USAGE_MEDIA
        const val SETTING = "volume_music"
    }

    /**
     * Settings for system voice interaction (TTS).
     * Controlled by the 'Notification' slider and synchronized with HA 'notification_volume'.
     */
    object Voice {
        const val STREAM = AudioManager.STREAM_NOTIFICATION
        const val USAGE = AudioAttributes.USAGE_NOTIFICATION
        const val SETTING = "volume_notification"
    }

    /**
     * Settings for device alarms.
     * Controlled by the 'Alarm' slider and synchronized with HA 'alarm_volume'.
     */
    object Alarm {
        const val STREAM = AudioManager.STREAM_ALARM
        const val USAGE = AudioAttributes.USAGE_ALARM
        const val SETTING = "volume_alarm"
    }
    
    /**
     * Settings for short interaction feedback sounds (beeps, wake word detection).
     * These are tied to the Voice/Notification stream to ensure consistent volume control.
     */
    object Feedback {
        const val USAGE = Voice.USAGE
    }
}
