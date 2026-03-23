package com.msp1974.vacompanion.utils

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.msp1974.vacompanion.audio.AudioStream

class VolumeObserver(
    private val context: Context,
    private val onVolumeChanged: (musicVolume: Int, notificationVolume: Int, alarmVolume: Int) -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)

        // We check the "music" stream specifically, but you can check others
        val mediaVolume = audioManager.getStreamVolume(AudioStream.Media.STREAM)
        val voiceVolume = audioManager.getStreamVolume(AudioStream.Voice.STREAM)
        val alarmVolume = audioManager.getStreamVolume(AudioStream.Alarm.STREAM)
        onVolumeChanged(mediaVolume, voiceVolume, alarmVolume)
    }

    fun register() {
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
        context.contentResolver.unregisterContentObserver(this)
    }
}