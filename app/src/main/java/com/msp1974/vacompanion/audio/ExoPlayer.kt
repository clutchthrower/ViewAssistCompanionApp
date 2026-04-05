package com.msp1974.vacompanion.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.msp1974.vacompanion.device.AudioVolumeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class VAMediaPlayer(context: Context, val config: APPConfig) {
    private val appContext = context.applicationContext
    private var currentVolume: Int = config.musicVolume
    private var mediaPlayer: ExoPlayer? = null
    var isVolumeDucked: Boolean = false
    var playRequested: Boolean = false
    private val audioVolumeManager = AudioVolumeManager(appContext)
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

    private fun getPlayer(): ExoPlayer {
        if (mediaPlayer == null) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            
            mediaPlayer = ExoPlayer.Builder(appContext)
                .setAudioAttributes(audioAttributes, true)
                .build()
        }
        return mediaPlayer!!
    }

    fun play(url: String) {
        playRequested = true

        try {
            val player = getPlayer()
            player.stop()
            player.clearMediaItems()
            
            val mediaItem = MediaItem.fromUri(url.toUri())
            player.setMediaItem(mediaItem)
            // Prepare the player.
            player.prepare()
            // Start the playback.
            player.play()
            Timber.i("Music started")
        } catch (ex: Exception) {
            Timber.e("Error playing music: $ex")
            ex.printStackTrace()
        }
    }

    fun pause() {
        try {
            mediaPlayer?.pause()
            Timber.i("Music paused")
        } catch (ex: Exception) {
            Timber.e("Error pausing music: $ex")
        }
    }

    fun resume() {
        try {
            mediaPlayer?.play()
            Timber.i("Music resumed")
        } catch (ex: Exception) {
            Timber.e("Error resuming music: $ex")
        }
    }

    fun stop() {
        try {
            playRequested = false
            mediaPlayer?.stop()
            mediaPlayer?.clearMediaItems()
            Timber.i("Music stopped")

        } catch (e: Exception) {
            Timber.e("Error stopping music: $e")
        }
    }

    fun release() {
        try {
            mediaPlayer?.release()
            mediaPlayer = null
            Timber.i("Music player released")
        } catch (e: Exception) {
            Timber.e("Error releasing music player: $e")
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
                val player = mediaPlayer ?: return@launch
                val diffStepVolume = (currentVolume / maxVolume.toFloat() - player.volume) / steps
                for (i in 1..steps) {
                    val vol = player.volume + diffStepVolume
                    player.volume = vol
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
