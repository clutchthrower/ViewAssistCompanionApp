package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.audio.VAMediaPlayer
import com.msp1974.vacompanion.audio.Alarm
import com.msp1974.vacompanion.audio.PCMMediaPlayer
import com.msp1974.vacompanion.audio.SoundClipPlayer

/**
 * Handles audio output and system-level media state for the satellite.
 * Manages PCM playback for TTS, music, alarms, and volume ducking.
 */
class SatelliteMediaHandler(context: Context) {
    val musicPlayer = VAMediaPlayer.getInstance(context)
    val alarmPlayer = Alarm(context)
    val pcmMediaPlayer = PCMMediaPlayer(context)
    val soundClipPlayer = SoundClipPlayer(context)

    fun release() {
        musicPlayer.stop()
        alarmPlayer.stopAlarm()
        pcmMediaPlayer.stop(force = true)
        soundClipPlayer.release()
    }

    /**
     * Ducks or unducks other audio streams to prioritize voice interaction.
     */
    fun updateVolumeDucking(key: String, duck: Boolean) {
        when (key) {
            "music" -> {
                if (duck) musicPlayer.duckVolume() 
                else if (!alarmPlayer.isSounding) musicPlayer.unDuckVolume()
            }
            "alarm" -> if (duck) alarmPlayer.duckVolume() else alarmPlayer.unDuckVolume()
            "all" -> {
                if (duck) {
                    musicPlayer.duckVolume()
                    alarmPlayer.duckVolume()
                } else {
                    if (!alarmPlayer.isSounding) musicPlayer.unDuckVolume()
                    alarmPlayer.unDuckVolume()
                }
            }
        }
    }
}
