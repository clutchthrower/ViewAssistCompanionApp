package com.msp1974.vacompanion.satellite

import android.Manifest
import android.content.Context
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Permissions
import com.msp1974.vacompanion.wakeword.WakeWordEngine
import com.msp1974.vacompanion.wakeword.WakeWordEngineModel
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.LinkedList
import javax.inject.Inject
import kotlin.collections.set

enum class WakeWordHandlerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING
}

interface IWakeWordHandler {
    fun onStateChange(state: WakeWordHandlerState)
    fun onAudio(audio: ByteArray)
    suspend fun onWakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection)

    suspend fun onStopWordDetected(detection: WakeWordEngineProvider.WakeWordDetection)

    fun onDiagnostics(level: Float, lastDetectionLevel: Float)
}

abstract class SatelliteWakeWorkHandler(val context: Context, val config: APPConfig, val scope: CoroutineScope): IWakeWordHandler {

    val firebase = FirebaseManager.getInstance(context)

    var state: WakeWordHandlerState = WakeWordHandlerState.STOPPED
        set(value) {
            field = value
            onStateChange(value)
        }

    var streamAudio: Boolean = false

    private var wakeWordJob: Job? = null
    var engine: WakeWordEngine? = null
    private var holdDetectionLevelJob: Job? = null
    private var lastWakeWordDetectionScore = 0f
    private val detectionCooldowns = mutableMapOf<String, Long>()
    private val detectionCooldownMs: Long = 2000L
    private val msPerChunk: Long = (MicrophoneInput.BUFFER_SIZE_IN_SHORTS.toLong() * 1000L) / MicrophoneInput.DEFAULT_SAMPLE_RATE_IN_HZ.toLong()

    private val audioHistoryBuffer = LinkedList<WakeWordEngineProvider.AudioResult.Audio>()
    private val historyBufferTargetDurationMs = 1000L
    private val historyBufferMaxSize = (historyBufferTargetDurationMs / msPerChunk).toInt()
    /**
     * Lookback window (in ms) to include when flushing the history buffer to the server.
     * This 200ms margin ensures we capture the transition between the wake-word and the
     * user's command. This is crucial because wake-word detections (especially with
     * sliding windows) often trigger on an average of the last several frames (N-2, N-1, N).
     * By looking back, we avoid clipping the end of the wake-word or the start of
     * the intent and provide the STT engine with enough acoustic context for a clean start.
     *
     * Note: This value is an initial estimate and requires empirical testing across different
     * Android devices. Feedback from actual STT results is necessary to determine if this
     * value is appropriate or if it results in excessive "pre-speech" noise.
     */
    private val historyBufferLookBackMs = 200L
    private var lastWakeDetectionTimestamp = 0L

    suspend fun run() {
        val startTime = System.currentTimeMillis()
        scope.launch (context = Dispatchers.Default) {
            start()
        }
        withTimeout(5000) {
            try {
                while (state != WakeWordHandlerState.RUNNING) {
                    delay(100)
                }
                Timber.d("Wake word detection started in ${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                Timber.e("Error waiting for wake word detection to start: ${e.message.toString()}")
            }
        }
    }

    suspend fun start() {
        try {
            if (config.wakeWordEngine != "none") {
                state = WakeWordHandlerState.STARTING
                engine = WakeWordEngine(context, config,  if (config.wakeWordEngine == "openwakeword") WakeWordEngineModel.OPENWAKEWORD else WakeWordEngineModel.MICROWAKEWORD)
                engine?.setActiveWakeWords(listOf(config.wakeWord))
                engine?.setActiveStopWords(listOf("stop"))
                runWakeWordDetection()
            }
            //TODO: Openwakeword reports running too soon
            state = WakeWordHandlerState.RUNNING
            awaitCancellation()
        } finally {
            terminateWakeWordDetection()
        }
    }

    fun stop() {
        if (wakeWordJob != null && wakeWordJob!!.isActive) {
            state = WakeWordHandlerState.STOPPING
            wakeWordJob?.cancel()
            wakeWordJob = null
        }
        engine = null
        onDiagnostics(0f, 0f)
        state = WakeWordHandlerState.STOPPED
        Timber.d("Wake word detection stopped")
    }


    fun runWakeWordDetection() {
        if (!Permissions(context, config).hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return
        }

        //sendDiagnostics(0f, 0f)
        val flow = engine!!.start()
        wakeWordJob = scope.launch { flow.cancellable().collect {
            when (it) {
                is WakeWordEngineProvider.AudioResult.WakeDetected -> {
                    holdLastDetectionLevel(it.detection.score)
                    if (it.detection.score >= config.wakeWordThreshold) {
                        val now = System.currentTimeMillis()
                        val lastDetection = detectionCooldowns[it.detection.wakeWordId]

                        if (lastDetection == null || detectionCooldownMs == 0L || now - lastDetection >= detectionCooldownMs) {
                            Timber.i("Wake word detected: ${it.detection.wakeWord}")
                            synchronized(audioHistoryBuffer) {
                                audioHistoryBuffer.clear()
                            }
                            wakeWordDetected(it.detection, engine!!.isStreaming())
                            detectionCooldowns[it.detection.wakeWordId] = now
                        }
                    }
                }

                is WakeWordEngineProvider.AudioResult.StopDetected -> {
                    if (it.detection.detected) {
                        Timber.d("Stop word detected: ${it.detection.wakeWord}")
                        if (it.detection.score > 0.5) {
                            onStopWordDetected(it.detection)
                            BroadcastSender.sendBroadcast(
                                context,
                                BroadcastSender.STOP_WORD_DETECTED
                            )
                        }
                    }
                }

                is WakeWordEngineProvider.AudioResult.Audio -> {
                    if (it.audio.size() > 0) {
                        if (engine!!.isStreaming()) {
                            onAudio(it.audio.toByteArray())
                        } else {
                            // Add to history buffer even if not streaming
                            synchronized(audioHistoryBuffer) {
                                audioHistoryBuffer.addLast(it)
                                if (audioHistoryBuffer.size > historyBufferMaxSize) {
                                    audioHistoryBuffer.removeFirst()
                                }
                            }
                        }
                    }
                }

                is WakeWordEngineProvider.AudioResult.AudioLevel -> {
                    if (config.diagnosticsEnabled) {
                        onDiagnostics(it.level, lastWakeWordDetectionScore)
                    }
                }
                is WakeWordEngineProvider.AudioResult.EngineStatus -> {
                    Timber.i("Engine status: ${it.status}")
                    if (it.status == "Started") {
                        state = WakeWordHandlerState.RUNNING
                    }
                }

            }
        }}
    }

    fun terminateWakeWordDetection() {

    }

    fun restartWakeWordDetection() {
        Timber.d("Restarting wake word detection")
        terminateWakeWordDetection()
        //runWakeWordDetection()
    }

    private suspend fun wakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection, isStreaming: Boolean) {
        Timber.i("${detection.wakeWord} wake word detected at ${detection.score}, threshold is ${config.wakeWordThreshold}")
        lastWakeDetectionTimestamp = detection.timestamp
        firebase.logEvent(
            FirebaseManager.WAKE_WORD_DETECTED, mapOf(
                "wake_word" to config.wakeWord,
                "threshold" to config.wakeWordThreshold.toString(),
                "prediction" to detection.score.toString()
            )
        )

        holdLastDetectionLevel(detection.score)
        //BroadcastSender.sendBroadcast(context, BroadcastSender.WAKE_WORD_DETECTED)
        onWakeWordDetected(detection)
    }

    private fun holdLastDetectionLevel(detectionLevel: Float, duration: Long = 2000) {
        if (detectionLevel > lastWakeWordDetectionScore) {
            lastWakeWordDetectionScore = detectionLevel
            if (holdDetectionLevelJob != null && holdDetectionLevelJob!!.isActive) {
                holdDetectionLevelJob?.cancel()
            }
            holdDetectionLevelJob = scope.launch {
                delay(duration)
                if (!streamAudio) {
                    lastWakeWordDetectionScore = 0f
                }
            }
        }
    }

}