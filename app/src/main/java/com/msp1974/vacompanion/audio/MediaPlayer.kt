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

    private fun getTargetVolume(): Float {
        val baseGain = config.mediaPlayerGain / 100.0f
        return if (isVolumeDucked) {
            val reduction = config.duckingVolume / 100.0f
            baseGain * (1.0f - reduction)
        } else {
            baseGain
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

                val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(AudioStream.Media.USAGE_EXO)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                player.setAudioAttributes(audioAttributes, true)
                
                val mediaItem = MediaItem.fromUri(url.toUri())
                player.setMediaItem(mediaItem)
                
                // Use the software gain set by the media_player entity, accounting for ducking
                player.volume = getTargetVolume()
                
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@MediaPlayer.isActuallyPlaying = isPlaying
                    }
                })
                
                player.prepare()
                player.play()
                Timber.i("Media started (Player gain: ${config.mediaPlayerGain}%, Ducked: $isVolumeDucked)")
            } catch (ex: Exception) {
                Timber.e("Error playing media: $ex")
                playRequested = false
            }
        }
    }

    fun updatePlayerVolume() {
        mainHandler.post {
            mediaPlayer?.let { player ->
                player.volume = getTargetVolume()
                Timber.d("Updated player gain to ${player.volume} (Base: ${config.mediaPlayerGain}%)")
            }
        }
    }

    fun pause() {
        mainHandler.post {
            mediaPlayer?.pause()
            Timber.i("Media paused")
        }
    }

    fun resume() {
        mainHandler.post {
            mediaPlayer?.let { player ->
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
                player.play()
                Timber.i("Media resumed")
            } ?: Timber.w("Media resume failed: No media player instance")
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                mediaPlayer?.stop()
                Timber.i("Media stopped")
            } catch (e: Exception) {
                Timber.e("Error stopping media: $e")
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
                Timber.i("Media player released")
            } catch (e: Exception) {
                Timber.e("Error releasing media player: $e")
            }
        }
    }


    private var volumeAnimationThread: Thread? = null

    fun duckVolume() {
        mainHandler.post {
            synchronized(this) {
                if (isVolumeDucked) return@synchronized
                isVolumeDucked = true
                
                mediaPlayer?.let { player ->
                    val startVol = player.volume
                    val targetVol = getTargetVolume()
                    Timber.d("Ducking media player gain from $startVol to $targetVol (Animating)")
                    
                    volumeAnimationThread?.interrupt()
                    volumeAnimationThread = VolumeAnimator.animate(startVol, targetVol) { vol ->
                        mediaPlayer?.volume = vol
                    }
                }
            }
        }
    }

    fun unDuckVolume() {
        mainHandler.post {
            synchronized(this) {
                if (!isVolumeDucked) return@post
                isVolumeDucked = false
                
                mediaPlayer?.let { player ->
                    val startVol = player.volume
                    val targetVol = getTargetVolume()
                    Timber.i("Restoring media player gain from $startVol to $targetVol (Animating)")
                    
                    volumeAnimationThread?.interrupt()
                    volumeAnimationThread = VolumeAnimator.animate(startVol, targetVol) { vol ->
                        mediaPlayer?.volume = vol
                    }
                }
            }
        }
    }
}
