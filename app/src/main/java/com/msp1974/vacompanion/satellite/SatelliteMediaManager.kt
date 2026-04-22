package com.msp1974.vacompanion.satellite

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import com.msp1974.vacompanion.players.AlarmService
import com.msp1974.vacompanion.players.SoundEffectsPlayer
import com.msp1974.vacompanion.players.MusicPlayerService
import com.msp1974.vacompanion.players.VoicePlayerService
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

@SuppressLint("UnsafeOptInUsageError")
class SatelliteMediaManager(val context: Context, val config: APPConfig) {
    val soundPlayer = SoundEffectsPlayer(context)

    val voicePlayer = VoiceManager(context)
    val musicPlayer = MusicManager(context)
    val alarmPlayer = AlarmManager(context)

    suspend fun stopAll() {
        Timber.d("Stopping media manager")
        soundPlayer.stop()
        voicePlayer.stop()
        musicPlayer.stop()
        alarmPlayer.stop()
    }

    class AlarmManager(val context: Context) {
        val alarmService = Intent(context, AlarmService::class.java)

        fun start(url: String) {
            alarmService.putExtra("url", url)
            context.startService(alarmService)
        }

        fun stop() {
            context.stopService(alarmService)
        }

        fun isSounding(): Boolean {
            return AlarmService.sInstance != null
        }
    }

    class MusicManager(val context: Context) {
        val musicService = Intent(context, MusicPlayerService::class.java)

        fun play(url: String, volume: Float) {
            musicService.putExtra("url", url)
            musicService.putExtra("volume", volume)
            context.startService(musicService)
        }

        fun pause() {
            MusicPlayerService.sInstance?.pause()
        }

        fun resume() {
            MusicPlayerService.sInstance?.resume()
        }

        fun stop() {
            context.stopService(musicService)
        }

        fun setVolume(volume: Float) {
            MusicPlayerService.sInstance?.setVolume(volume)
        }
    }


    class VoiceManager(val context: Context) {
        val voiceService = Intent(context, VoicePlayerService::class.java)

        fun start(rate: Int, width: Int, channels: Int) {
            if (!isRunning()) {
                voiceService.putExtra("rate", rate)
                voiceService.putExtra("width", width)
                voiceService.putExtra("channels", channels)
                context.startService(voiceService)
              }
        }

        fun flush() {
            if (isRunning()) {
                VoicePlayerService.sInstance?.stop(false)
            }
        }

        fun stop() {
            try {
                VoicePlayerService.sInstance?.stop(true)
                context.stopService(voiceService)
            } catch (e: Exception) {
                Timber.e(e)
            }
        }

        fun writeData(data: ByteArray) {
            VoicePlayerService.sInstance?.writeAudio(data)
        }

        fun isPlaying(): Boolean {
            return try {
                VoicePlayerService.sInstance!!.isPlaying
            } catch (e: Exception) {
                false
            }
        }

        fun isRunning(): Boolean {
            return VoicePlayerService.sInstance != null
        }

    }
}