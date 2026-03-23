package com.msp1974.vacompanion.audio

import android.content.Context
import android.os.Handler
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import kotlin.concurrent.thread
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.annotation.GuardedBy
import androidx.media3.common.Player


class MediaPlayer(val context: Context) {
    private val config: APPConfig = APPConfig.getInstance(context)
    private var mediaPlayer: ExoPlayer? = null

    @GuardedBy("this")
    private var isVolumeDucked: Boolean = false
    
    private var playRequested: Boolean = false
    
    private val mainHandler = Handler(context.mainLooper)
    private var originalMusicVolume: Int? = null
    @Volatile private var isActuallyPlaying: Boolean = false

    val isPlaying: Boolean
        get() = isActuallyPlaying

    companion object {
        @Volatile
        private var instance: MediaPlayer? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: MediaPlayer(context).also { instance = it }
            }
    }

    fun play(url: String) {
        mainHandler.post {
            try {
                if (playRequested) {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                }
            } catch (e: Exception) {
                Timber.e("Error stopping existing player: $e")
            }

            playRequested = true

            try {
                val player = ExoPlayer.Builder(context).build()
                mediaPlayer = player
                
                val mediaItem = MediaItem.fromUri(url.toUri())
                player.setMediaItem(mediaItem)
                
                // Use the software gain set by the media_player entity
                player.volume = config.playerVolume / 100.0f
                
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@MediaPlayer.isActuallyPlaying = isPlaying
                    }
                })
                
                player.prepare()
                player.play()
                Timber.i("Music started (Player gain: ${config.playerVolume}%)")
            } catch (ex: Exception) {
                Timber.e("Error playing music: $ex")
                playRequested = false
            }
        }
    }

    fun updatePlayerVolume() {
        mainHandler.post {
            mediaPlayer?.let { player ->
                player.volume = config.playerVolume / 100.0f
                Timber.d("Updated player gain to ${config.playerVolume}%")
            }
        }
    }

    fun pause() {
        mainHandler.post {
            mediaPlayer?.pause()
            Timber.i("Music paused")
        }
    }

    fun resume() {
        mainHandler.post {
            mediaPlayer?.let { player ->
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.play()
                Timber.i("Music resumed")
            } ?: Timber.w("Music resume failed: No media player instance")
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                mediaPlayer?.stop()
                Timber.i("Music stopped")
            } catch (e: Exception) {
                Timber.e("Error stopping music: $e")
            }
        }
    }

    fun release() {
        mainHandler.post {
            try {
                playRequested = false
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                Timber.i("Music player released")
            } catch (e: Exception) {
                Timber.e("Error releasing music player: $e")
            }
        }
    }


    fun duckVolume() {
        mainHandler.post {
            // Use system volume level for ducking as per the layered model
            synchronized(this) {
                if (isVolumeDucked) return@synchronized
                isVolumeDucked = true
                
                // Save current system volume
                originalMusicVolume = config.musicVolume
                
                val reduction = config.duckingVolume / 100.0
                val targetVol = (originalMusicVolume!! * (1.0 - reduction)).toInt()
                
                Timber.d("Ducking system volume from $originalMusicVolume to $targetVol (${config.duckingVolume}% reduction)")
                config.musicVolume = targetVol
            }
        }
    }

    fun unDuckVolume() {
        mainHandler.post {
            synchronized(this) {
                if (!isVolumeDucked) return@post
                isVolumeDucked = false
                
                originalMusicVolume?.let { restoredVol ->
                    Timber.i("Restoring system volume to $restoredVol")
                    config.musicVolume = restoredVol
                }
                originalMusicVolume = null
            }
        }
    }
}
