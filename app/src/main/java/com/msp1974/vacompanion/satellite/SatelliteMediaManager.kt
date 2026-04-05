package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.audio.Alarm
import com.msp1974.vacompanion.audio.PCMMediaPlayer
import com.msp1974.vacompanion.audio.VAMediaPlayer
import com.msp1974.vacompanion.settings.APPConfig

class SatelliteMediaManager(private val context: Context, val config: APPConfig) {
    val alarmPlayer = Alarm(context, config)
    val pcmMediaPlayer = PCMMediaPlayer(context)
    val musicPlayer = VAMediaPlayer.Companion.getInstance(context, config)

    fun release() {
        if (pcmMediaPlayer.isPlaying) pcmMediaPlayer.stop()
        if (alarmPlayer.isSounding()) alarmPlayer.stopAlarm()
        musicPlayer.stop()
    }

    fun stopAll() {
        if (pcmMediaPlayer.isPlaying) pcmMediaPlayer.stop()
        musicPlayer.stop()
        alarmPlayer.stopAlarm()
    }

    fun updateVolumeDucking(type: String, active: Boolean) {
        val duckAlarm = type == "alarm" || type == "all"
        val duckMusic = type == "music" || type == "all"

        if (active) {
            //if (duckAlarm) alarmPlayer.duckVolume()
            if (duckMusic) musicPlayer.duckVolume()
        } else {
            //if (duckAlarm) alarmPlayer.unDuckVolume()
            if (duckMusic && !alarmPlayer.isSounding()) musicPlayer.unDuckVolume()
        }
    }
}