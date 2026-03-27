package com.msp1974.vacompanion.audio

import android.content.Context
import android.os.Handler
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Logger

class AlarmPlayer(val context: Context) {
    private val log = Logger()
    private val config: APPConfig = APPConfig.getInstance(context)
    private val mainHandler = Handler(context.mainLooper)

    var isVolumeDucked: Boolean = false
    var isSounding: Boolean = false
    var mediaPlayer: ExoPlayer? = null
    private var unduckThread: Thread? = null
    private var stopTimeoutRunnable: Runnable? = null

    fun startAlarm(url: String = "") {
        mainHandler.post {
            if (mediaPlayer != null) return@post

            try {
                val inputMode = config.audioInputProcessingMode
                val enableRenderTap = MicrophoneInput.shouldEnableWebRtcRenderTap(inputMode)
                log.d("Creating alarm ExoPlayer (inputMode=$inputMode, renderTapEnabled=$enableRenderTap)")
                val player = ApmTappedExoPlayerFactory.create(
                    context = context,
                    enableRenderTap = enableRenderTap,
                )
                mediaPlayer = player

                val mediaUri = if (url.isNotBlank()) {
                    url.toUri()
                } else {
                    "android.resource://${context.packageName}/${R.raw.alarm_sound}".toUri()
                }

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioStream.Alarm.USAGE_EXO)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
                    .build()

                // Media3 only supports automatic focus handling for USAGE_MEDIA/USAGE_GAME.
                // Alarm playback uses USAGE_ALARM, so auto-focus would crash at runtime.
                // We disable automatic focus here and keep alarm behavior app-controlled.
                player.setAudioAttributes(audioAttributes, false)
                player.setMediaItem(MediaItem.fromUri(mediaUri))
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.volume = getTargetVolume()
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        this@AlarmPlayer.isSounding = isPlaying
                    }
                })
                player.prepare()
                player.play()
                isSounding = true
                stopOnTimeout(10)
            } catch (e: Exception) {
                log.e("Failed to start alarm: ${e.message}")
                try {
                    mediaPlayer?.release()
                } catch (_: Exception) {
                }
                mediaPlayer = null
                isSounding = false
            }
        }
    }

    fun stopAlarm() {
        mainHandler.post {
            stopTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            stopTimeoutRunnable = null

            mediaPlayer?.let { player ->
                try {
                    player.stop()
                } catch (_: Exception) {
                }
                player.release()
            }
            unduckThread?.interrupt()
            unduckThread = null
            mediaPlayer = null
            isSounding = false
        }
    }

    fun stopOnTimeout(timeout: Long) {
        mainHandler.post {
            stopTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            stopTimeoutRunnable = Runnable { stopAlarm() }
            mainHandler.postDelayed(stopTimeoutRunnable!!, timeout * 60_000L)
        }
    }

    fun duckVolume() {
        mainHandler.post {
            synchronized(this) {
                if (isVolumeDucked) return@synchronized
                isVolumeDucked = true

                mediaPlayer?.let { player ->
                    val startVol = player.volume
                    val targetVol = getTargetVolume()

                    log.d("Ducking Alarm volume to $targetVol (Animating)")
                    unduckThread?.interrupt()
                    unduckThread = VolumeAnimator.animate(startVol, targetVol) { vol ->
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

                    log.i("Restoring Alarm volume to $targetVol (Animating)")
                    unduckThread?.interrupt()
                    unduckThread = VolumeAnimator.animate(startVol, targetVol) { vol ->
                        mediaPlayer?.volume = vol
                    }
                }
            }
        }
    }

    private fun getTargetVolume(): Float {
        return if (isVolumeDucked) {
            1.0f - (config.duckingVolume / 100.0f)
        } else {
            1.0f
        }
    }
}