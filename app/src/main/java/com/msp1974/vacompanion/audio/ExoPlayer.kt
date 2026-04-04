package com.msp1974.vacompanion.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.msp1974.vacompanion.device.AudioVolumeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class VAMediaPlayer(val context: Context, val config: APPConfig) {
    private var currentVolume: Int = config.musicVolume
    private var mediaPlayer: ExoPlayer? = null
    var isVolumeDucked: Boolean = false
    var playRequested: Boolean = false
    private val audioVolumeManager = AudioVolumeManager(context)
    val maxVolume = audioVolumeManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: VAMediaPlayer? = null

        fun getInstance(context: Context, config: APPConfig) =
            instance ?: synchronized(this) {
                instance ?: VAMediaPlayer(context, config).also { instance = it }
            }
    }

    fun play(url: String) {
        try {
            if (playRequested) {
                mediaPlayer!!.stop()
            }
        } catch (e: IllegalStateException) {
            // Here is media player is stopped
        }

        playRequested = true

        try {
            mediaPlayer = ExoPlayer.Builder(context).build()
            val mediaItem = MediaItem.fromUri(url.toUri())
            mediaPlayer!!.setMediaItem(mediaItem)
            // Prepare the player.
            mediaPlayer!!.prepare()
            // Start the playback.
            mediaPlayer!!.play()
            Timber.i("Music started")
        } catch (ex: Exception) {
            Timber.e("Error playing music: $ex")
            ex.printStackTrace()
        }
    }

    fun pause() {
        try {
            mediaPlayer!!.pause()
            Timber.i("Music paused")
        } catch (ex: Exception) {
            Timber.e("Error pausing music: $ex")
        }
    }

    fun resume() {
        try {
            mediaPlayer!!.play()
            Timber.i("Music resumed")
        } catch (ex: Exception) {
            Timber.e("Error resuming music: $ex")
        }
    }

    fun stop() {
        try {
            playRequested = false
            mediaPlayer!!.stop()
            mediaPlayer!!.release()
            Timber.i("Music stopped")

        } catch (e: Exception) {
            Timber.e("Error stopping music: $e")
        }
    }

    fun setVolume(volume: Int) {
        if (!isVolumeDucked && mediaPlayer != null) {
            mediaPlayer!!.volume = volume / audioVolumeManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        }
        currentVolume = volume
        Timber.i("Music volume set to $volume")
    }

    fun duckVolume() {
        if (!isVolumeDucked && mediaPlayer != null) {
            if (mediaPlayer!!.isPlaying) {
                val vol: Float = config.duckingVolume / 10f
                if (vol < mediaPlayer!!.volume) {
                    Timber.d("Ducking music volume from $currentVolume to $vol")
                    mediaPlayer!!.volume = vol
                    isVolumeDucked = true
                } else {
                    Timber.d("Not ducking music volume as it is lower than ducking volume of $vol at ${mediaPlayer!!.volume}")
                }
            }
        }
    }

    fun unDuckVolume() {
        if (isVolumeDucked) {
            Timber.i("Restoring music volume to ${currentVolume}")
            CoroutineScope(Dispatchers.Main).launch {
                val steps = 3
                val diffStepVolume = (currentVolume - mediaPlayer!!.volume) / steps
                for (i in 1..steps) {
                    val vol = config.duckingVolume + (diffStepVolume * i)
                    mediaPlayer!!.volume = vol
                    delay(250)
                    if (i < steps) {
                        delay(250)
                    }
                }
            }
            isVolumeDucked = false
        }
    }
}