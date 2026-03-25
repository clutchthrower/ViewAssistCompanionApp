package com.msp1974.vacompanion.audio

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

object ApmTappedExoPlayerFactory {
    internal fun buildTapAudioProcessors(enableRenderTap: Boolean): Array<AudioProcessor> {
        return if (enableRenderTap) arrayOf(ApmTapAudioProcessor()) else emptyArray()
    }

    @SuppressLint("UnsafeOptInUsageError")
    fun create(context: Context, enableRenderTap: Boolean): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .setAudioProcessors(buildTapAudioProcessors(enableRenderTap))
                    .build()
            }
        }
        val player = ExoPlayer.Builder(context, renderersFactory).build()
        val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(offloadPreferences)
            .build()
        return player
    }
}
