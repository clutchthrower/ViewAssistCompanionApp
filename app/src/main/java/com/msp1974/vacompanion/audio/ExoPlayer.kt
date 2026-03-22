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


class VAMediaPlayer(val context: Context) {
    private val config: APPConfig = APPConfig.getInstance(context)
    private var mediaPlayer: ExoPlayer? = null
    
    @GuardedBy("this")
    private var isVolumeDucked: Boolean = false
    
    private var playRequested: Boolean = false
    
    private val mainHandler = Handler(context.mainLooper)
    private var unduckThread: Thread? = null
    @Volatile private var isActuallyPlaying: Boolean = false

    val isPlaying: Boolean
        get() = isActuallyPlaying

    companion object {
        @Volatile
        private var instance: VAMediaPlayer? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: VAMediaPlayer(context).also { instance = it }
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
                
                // Initialize volume based on current ducking status
                val baseVol = if (synchronized(this) { isVolumeDucked }) {
                    (1.0 - config.duckingVolume / 100.0).toFloat()
                } else 1.0f
                player.volume = baseVol
                
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@VAMediaPlayer.isActuallyPlaying = isPlaying
                    }
                })
                
                player.prepare()
                player.play()
                Timber.i("Music started (Initial volume: $baseVol)")
            } catch (ex: Exception) {
                Timber.e("Error playing music: $ex")
                playRequested = false
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
            mediaPlayer?.play()
            Timber.i("Music resumed")
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                playRequested = false
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                Timber.i("Music stopped")
            } catch (e: Exception) {
                Timber.e("Error stopping music: $e")
            }
        }
    }


    fun duckVolume() {
        mainHandler.post {
            // Session-level duck: set isVolumeDucked as soon as a voice interaction starts, even if the
            // current track is already quiet. That way setVolume() only updates currentVolume while the
            // player stays low (no jump during TTS), and play() can cap initial level for music that
            // starts mid-session. unDuckVolume() clears the flag even when no fade was needed.
            // synchronization ensures visibility across the unduck thread vs main thread.
            synchronized(this) {
                if (isVolumeDucked) return@synchronized
                isVolumeDucked = true
                
                // Cancel any pending unduck animation
                unduckThread?.interrupt()
                unduckThread = null
            }

            // Apply ducking to currently playing media
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val reduction = config.duckingVolume / 100.0
                    val targetVol = (1.0 - reduction).toFloat()
                    Timber.d("Ducking music volume to relative level $targetVol (${config.duckingVolume}% reduction)")
                    player.volume = targetVol
                }
            }
        }
    }

    fun unDuckVolume() {
        mainHandler.post {
            // synchronization here protects against race conditions with concurrent duck requests
            // and ensures the fade thread captures a stable snapshot of the volume state.
            val (vStart, vTarget) = synchronized(this) {
                if (!isVolumeDucked) return@post
                isVolumeDucked = false
                unduckThread?.interrupt()
                
                val player = mediaPlayer
                if (player == null || !player.isPlaying) {
                    Timber.d("Music not playing, skipping unduck fade.")
                    return@post
                }
                
                // Snapshot the volumes (as relative gain) to be used by the background thread
                val vReduction = config.duckingVolume / 100.0
                val vStart = (1.0 - vReduction).toFloat()
                val vTarget = 1.0f
                vStart to vTarget
            }
            
            if (vStart >= vTarget) {
                Timber.d("Volume already at target level ($vStart vs $vTarget).")
                return@post
            }

            val thread = thread(name = "volumeUnducking", start = false) {
                try {
                    val steps = 4
                    val diffStep = (vTarget - vStart) / steps
                    
                    for (i in 1..steps) {
                        val v = vStart + (diffStep * i)
                        mainHandler.post {
                            mediaPlayer?.volume = v
                        }
                        if (i < steps) {
                            Thread.sleep(200)
                        }
                    }
                    Timber.i("Restored music volume to $vTarget")
                } catch (ex: InterruptedException) {
                    Timber.d("Unducking animation interrupted")
                }
            }
            
            synchronized(this) {
                unduckThread = thread
            }
            thread.start()
        }
    }
}
