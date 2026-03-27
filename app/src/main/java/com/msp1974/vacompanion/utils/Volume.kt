package com.msp1974.vacompanion.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.msp1974.vacompanion.audio.AudioStream

/**
 * Observes system stream volume changes for sync to Home Assistant.
 *
 * [ContentObserver] on [Settings.System] volume URIs is unreliable for hardware keys on many
 * devices. System broadcast [ACTION_VOLUME_CHANGED] is sent when stream levels change.
 */
private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

class VolumeObserver(
    private val context: Context,
    private val onVolumeChanged: (musicVolume: Int, notificationVolume: Int, alarmVolume: Int) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val applicationContext = context.applicationContext

    private val volumeBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_VOLUME_CHANGED) return
            dispatchCurrentVolumes()
        }
    }

    private val volumeIntentFilter = IntentFilter(ACTION_VOLUME_CHANGED)

    private fun dispatchCurrentVolumes() {
        val mediaVolume = audioManager.getStreamVolume(AudioStream.Media.STREAM)
        val voiceVolume = audioManager.getStreamVolume(AudioStream.Voice.STREAM)
        val alarmVolume = audioManager.getStreamVolume(AudioStream.Alarm.STREAM)
        onVolumeChanged(mediaVolume, voiceVolume, alarmVolume)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        dispatchCurrentVolumes()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun register() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // System volume broadcasts are implicit; NOT_EXPORTED drops them on some OEM/API combos.
            applicationContext.registerReceiver(
                volumeBroadcastReceiver,
                volumeIntentFilter,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            applicationContext.registerReceiver(volumeBroadcastReceiver, volumeIntentFilter)
        }
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(AudioStream.Media.SETTING),
            false,
            this
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(AudioStream.Voice.SETTING),
            false,
            this
        )
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(AudioStream.Alarm.SETTING),
            false,
            this
        )
    }

    fun unregister() {
        applicationContext.unregisterReceiver(volumeBroadcastReceiver)
        context.contentResolver.unregisterContentObserver(this)
    }
}
