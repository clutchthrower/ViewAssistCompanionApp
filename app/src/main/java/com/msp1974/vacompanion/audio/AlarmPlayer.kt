package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer as AndroidMediaPlayer
import android.net.Uri
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import androidx.core.net.toUri


class AlarmPlayer(val context: Context) {
    private val log = Logger()
    private val config: APPConfig = APPConfig.getInstance(context)

    var isVolumeDucked: Boolean = false
    var isSounding: Boolean = false
    var mediaPlayer: AndroidMediaPlayer? = null
    private var unduckThread: Thread? = null

    fun startAlarm(url: String = "") {
        if (mediaPlayer == null) {
            mediaPlayer = AndroidMediaPlayer().apply {
                if (url != "") {
                    setDataSource(url)
                } else {
                    val mediaPath =
                        ("android.resource://" + context.packageName + "/" + R.raw.alarm_sound).toUri()
                    setDataSource(context, mediaPath)
                }
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioStream.Alarm.USAGE)
                        .build()
                )
            }
            mediaPlayer?.prepare()
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
            isSounding = true
            stopOnTimeout(10)
        }
    }

    fun stopAlarm() {
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer!!.stop()
            isSounding = false
            mediaPlayer!!.reset()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
    }


    fun stopOnTimeout(timeout: Long) {
        val executor = Executors.newSingleThreadScheduledExecutor()
        executor.schedule({
            stopAlarm()
        }, timeout, java.util.concurrent.TimeUnit.MINUTES)
    }

    fun duckVolume() {
        synchronized(this) {
            if (mediaPlayer != null && !isVolumeDucked) {
                if (mediaPlayer!!.isPlaying) {
                    val reduction = config.duckingVolume / 100.0f
                    val vStart = 1.0f // Alarm baseline is 1.0
                    val vTarget = (1.0f - reduction)
                    
                    log.d("Ducking Alarm volume to $vTarget (Animating)")
                    unduckThread?.interrupt()
                    unduckThread = VolumeAnimator.animate(vStart, vTarget) { vol ->
                        mediaPlayer?.setVolume(vol, vol)
                    }
                    isVolumeDucked = true
                }
            }
        }
    }

    fun unDuckVolume() {
        synchronized(this) {
            if (mediaPlayer != null && isVolumeDucked) {
                isVolumeDucked = false
                
                val reduction = config.duckingVolume / 100.0f
                val vStart = (1.0f - reduction)
                val vTarget = 1.0f
                
                log.i("Restoring Alarm volume to $vTarget (Animating)")
                unduckThread?.interrupt()
                unduckThread = VolumeAnimator.animate(vStart, vTarget) { vol ->
                    mediaPlayer?.setVolume(vol, vol)
                }
            }
        }
    }
}