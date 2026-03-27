package com.msp1974.vacompanion.audio

import android.os.Handler
import android.os.Looper
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * Utility to smoothly transition audio volume/gain values over time.
 * Can be used by any audio player to implement ducking/unducking fades.
 */
object VolumeAnimator {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Animates a volume value from start to end over durationMs.
     * 
     * @param start Initial volume (0.0 - 1.0)
     * @param end Target volume (0.0 - 1.0)
     * @param durationMs Time in milliseconds for the animation
     * @param onUpdate Callback executed on the main thread for each step
     * @return The animation thread, which can be interrupted if needed
     */
    fun animate(
        start: Float,
        end: Float,
        durationMs: Long = 500,
        onUpdate: (Float) -> Unit
    ): Thread {
        val thread = thread(name = "VolumeAnimation", start = false) {
            try {
                val steps = 15
                val delay = durationMs / steps
                val stepVolume = (end - start) / steps
                
                for (i in 1..steps) {
                    val currentVol = start + (stepVolume * i)
                    mainHandler.post {
                        onUpdate(currentVol)
                    }
                    if (i < steps) {
                        Thread.sleep(delay)
                    }
                }
                
                // Ensure we hit the exact target at the end
                mainHandler.post {
                    onUpdate(end)
                }
            } catch (e: InterruptedException) {
                // Animation interrupted
            }
        }
        thread.start()
        return thread
    }
}
