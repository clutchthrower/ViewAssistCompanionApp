package com.msp1974.vacompanion.audio

import android.content.Context.AUDIO_SERVICE
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.C.USAGE_NOTIFICATION
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.min

internal class AudioManager(context: Context) {
    private val audioManager = context.getSystemService(AUDIO_SERVICE) as AudioManager

    fun getStreamMaxVolume(stream: Int): Int {
        return audioManager.getStreamMaxVolume(stream)
    }

    fun setVolume(stream: Int, volume: Int) {
        audioManager.setStreamVolume(stream, min(getStreamMaxVolume(stream) ,volume).toInt(), 0)
    }

    fun getVolume(stream: Int): Float {
        return audioManager.getStreamVolume(stream).toFloat() / getStreamMaxVolume(stream).toFloat()
    }
}

/**
 * Manages playback of short sound effects. Uses a pool of pre-prepared ExoPlayer instances 
 * to eliminate buffering latency on first playback and ensure immediate audio feedback.
 */
internal class SoundClipPlayer(private val context: Context) {
    private val players = mutableMapOf<Int, ExoPlayer>()

    fun prepare(resId: Int) {
        if (players.containsKey(resId)) return
        
        Handler(context.mainLooper).post {
            try {
                val player = createPlayer(resId)
                player.prepare()
                players[resId] = player
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    fun play(resId: Int) {
        Handler(context.mainLooper).post {
            try {
                // Ensure only one feedback sound plays at a time
                stopAllInternal()

                val player = players[resId]
                if (player != null) {
                    player.seekTo(0)
                    player.play()
                } else {
                    // Fallback for non-prepared sounds
                    val newPlayer = createPlayer(resId)
                    newPlayer.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                newPlayer.release()
                            }
                        }
                    })
                    newPlayer.prepare()
                    newPlayer.play()
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun createPlayer(resId: Int): ExoPlayer {
        val player = ExoPlayer.Builder(context).build()
        val mediaItem = MediaItem.fromUri("android.resource://${context.packageName}/$resId".toUri())
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(USAGE_NOTIFICATION)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(audioAttributes, false)
        player.setMediaItem(mediaItem)
        return player
    }

    fun stopAll() {
        Handler(context.mainLooper).post {
            stopAllInternal()
        }
    }

    private fun stopAllInternal() {
        players.values.forEach {
            if (it.isPlaying) {
                it.pause()
                it.seekTo(0)
            }
        }
    }

    fun release() {
        Handler(context.mainLooper).post {
            players.values.forEach { it.release() }
            players.clear()
        }
    }
}





