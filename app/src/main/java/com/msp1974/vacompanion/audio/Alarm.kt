package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.settings.APPConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.core.net.toUri



class Alarm(val context: Context, val config: APPConfig) {

    var mediaPlayer: MediaPlayer? = null

    fun isSounding(): Boolean {
        runCatching {
            return mediaPlayer!!.isPlaying
        }
        return false
    }

    fun startAlarm(url: String = "") {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                if (url != "") {
                    setDataSource(url)
                } else {
                    val mediaPath =
                        ("android.resource://" + context.packageName + "/" + R.raw.alarm_sound).toUri()
                    setDataSource(context, mediaPath)
                }
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                )
            }
            mediaPlayer?.prepare()
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
            stopOnTimeout(10)
        }
    }

    fun stopAlarm() {
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
    }

    fun stopOnTimeout(timeout: Long) {
        Executors.newSingleThreadScheduledExecutor().schedule({
            stopAlarm()
        }, timeout, TimeUnit.MINUTES)
    }
}