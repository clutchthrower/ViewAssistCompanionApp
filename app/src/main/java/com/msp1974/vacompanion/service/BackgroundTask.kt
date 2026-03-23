package com.msp1974.vacompanion.service

import android.Manifest
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.satellite.SatelliteCallback
import com.msp1974.vacompanion.satellite.SatelliteServer
import com.msp1974.vacompanion.satellite.SatelliteZeroconf
import com.msp1974.vacompanion.wyoming.WyomingPacket
import com.msp1974.vacompanion.satellite.DeviceSyncManager
import com.msp1974.vacompanion.audio.EffectsPlayer
import com.msp1974.vacompanion.audio.StreamVolumeManager as AudManager
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.sensors.SensorUpdatesCallback
import com.msp1974.vacompanion.sensors.Sensors
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.wakeword.WakeWordEngine
import com.msp1974.vacompanion.wakeword.WakeWordEngineModel
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.util.Date
import java.util.LinkedList
import kotlin.collections.set
import kotlin.concurrent.thread

enum class AudioRouteOption { NONE, DETECT, PROCESS_NO_DETECT, STREAM}

internal class BackgroundTaskController (private val context: Context): EventListener {

    private val firebase = FirebaseManager.getInstance()
    private var config: APPConfig = APPConfig.getInstance(context)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var wakeWordJob: Job? = null
    private var holdDetectionLevelJob: Job? = null
    private var lastWakeWordDetectionScore = 0f

    private var wifiLock: WifiManager.WifiLock? = null

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
    private val historyBufferLookbackMs = 200L
    private var lastWakeDetectionTimestamp = 0L

    val zeroConf: SatelliteZeroconf = SatelliteZeroconf(context)

    var engine: WakeWordEngine? = null
    var engineStarted: Boolean = false
    var audioRoute: AudioRouteOption = AudioRouteOption.NONE
    private var sensorRunner: Sensors? = null
    lateinit var assetManager: AssetManager
    lateinit var server: SatelliteServer
    private lateinit var deviceSyncManager: DeviceSyncManager
    private val effectsPlayer = EffectsPlayer(context)

    private var motionTask = CameraBackgroundTask(context)

    fun start() {
        assetManager = context.assets

        // wifi lock
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "wallPanel:wifiLock")

        // Start satellite server
        server = SatelliteServer(context, config.serverPort, object : SatelliteCallback {
            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onSatelliteStarted() {
                Timber.i("Background Task - Connection detected")
                deviceSyncManager.onConnected()
                startSensors(context)
                runWakeWordDetection()
                warmUpAudioResources()
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
                zeroConf.unregisterService()
            }

            override fun onSatelliteStopped() {
                Timber.i("Background Task - Disconnection detected")
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
                if (sensorRunner != null) {
                    sensorRunner!!.stop()
                    sensorRunner = null
                }
                terminateWakeWordDetection()
                stopSensors()
                deviceSyncManager.onDisconnected()
                zeroConf.registerService(config.serverPort)
            }

            override fun onRequestInputAudioStream() {
                audioRoute = AudioRouteOption.STREAM
                var sentChunks = 0

                // Flush history buffer, but only audio from the last wake-up onwards (with a small lookback)
                synchronized(audioHistoryBuffer) {
                    while (audioHistoryBuffer.isNotEmpty()) {
                        val chunk = audioHistoryBuffer.removeFirst()
                        // Keep only chunks that follow the detection point (minus lookback margin for safety)
                        if (chunk.timestamp >= (lastWakeDetectionTimestamp - historyBufferLookbackMs)) {
                            server.sendAudio(chunk.audio.toByteArray())
                            sentChunks++
                        }
                    }
                }

                Timber.i("Streaming audio to server. Sent $sentChunks chunks from history (${sentChunks * msPerChunk}ms).")
                engine?.setStreaming(true)
            }

            override fun onReleaseInputAudioStream() {
                Timber.i("Stopped streaming audio to server")
                if (audioRoute == AudioRouteOption.STREAM) {
                    audioRoute = AudioRouteOption.PROCESS_NO_DETECT
                    lastWakeWordDetectionScore = 0f

                    scope.launch {
                        delay(2000)
                        audioRoute = AudioRouteOption.DETECT
                    }
                }
                if (config.processingSound != "none") {
                    try {
                        val resId = context.resources.getIdentifier(config.processingSound, "raw", context.packageName)
                        if (resId != 0) {
                            effectsPlayer.play(resId)
                        }
                    } catch (e: Exception) {
                        Timber.e("Error playing processing sound: ${e.message.toString()}")
                    }
                }
                engine?.setStreaming(false)
            }
        })
        deviceSyncManager = DeviceSyncManager(context, server)
        thread(name="WyomingServer") { server.start() }

        // Add config change listeners
        config.eventBroadcaster.addListener(this)

        // Start mdns server
        zeroConf.registerService(config.serverPort)

        Timber.d("Background task initialisation completed")
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "micEnabled" -> {
                try {
                    val micEnabled = event.newValue as Boolean
                    engine?.setMuted(!micEnabled)
                    if (micEnabled) {
                        if (config.micOnSound != "none") {
                            try {
                                val resId = context.resources.getIdentifier(config.micOnSound, "raw", context.packageName)
                                if (resId != 0) {
                                    effectsPlayer.play(resId)
                                }
                            } catch (e: Exception) {
                                Timber.e("Error playing mic on sound: ${e.message.toString()}")
                            }
                        }
                    } else {
                        if (config.micOffSound != "none") {
                            try {
                                val resId = context.resources.getIdentifier(config.micOffSound, "raw", context.packageName)
                                if (resId != 0) {
                                    effectsPlayer.play(resId)
                                }
                            } catch (e: Exception) {
                                Timber.e("Error playing mic off sound: ${e.message.toString()}")
                            }
                        }
                    }
                    sendDiagnostics(0f,0f)
                } catch (e: Exception) {
                    Timber.e("Error setting muted: ${e.message.toString()}")
                }
            }
            "voice_volume", "media_volume", "media_player_gain", "alarm_volume", "do_not_disturb" -> {
                deviceSyncManager.onSettingChange(event.eventName, event.newValue)
            }
            "continueConversationStart" -> {
                if (config.wakeWordSound != "none") {
                    try {
                        val resId = context.resources.getIdentifier(
                            config.wakeWordSound,
                            "raw",
                            context.packageName
                        )
                        if (resId != 0) {
                            effectsPlayer.play(resId)
                        }
                    } catch (e: Exception) {
                        Timber.e("Error playing continue listening sound: ${e.message.toString()}")
                    }
                }
            }
            "wakeWord", "wakeWordThreshold", "wakeWordEngine", "useVoiceEnhancer", "useAdvancedGain" -> {
                scope.launch {
                    try {
                        if (wakeWordJob != null && wakeWordJob!!.isActive) {
                            restartWakeWordDetection()
                        } else if (server.pipelineClient != null) {
                            runWakeWordDetection()
                        }
                    } catch (e: Exception) {
                        Timber.e("Error restarting wake word detection: ${e.message.toString()}")
                    }
                }
            }
            "wakeWordSound", "processingSound", "errorSound", "stopWordSound", "micOnSound", "micOffSound" -> {
                scope.launch {
                    try {
                        warmUpAudioResources()
                    } catch (e: Exception) {
                        Timber.e("Error warming up audio resources: ${e.message.toString()}")
                    }
                }
            }
            "wakeWordTrigger" -> {
                wakeWordDetected(WakeWordEngineProvider.WakeWordDetection(
                    wakeWordId =  config.wakeWord,
                    wakeWord = config.wakeWord,
                    detected =  true,
                    score =  config.wakeWordThreshold,
                    timestamp = System.currentTimeMillis()
                ),
                false
                )
            }
            "recognitionError" -> {
                val errorText = event.oldValue as? String ?: ""
                if (errorText.isNotEmpty()) {
                    config.eventBroadcaster.notifyEvent(Event("showToastError", "", errorText))
                }

                if (config.errorSound != "none") {
                    try {
                        val resId = context.resources.getIdentifier(config.errorSound, "raw", context.packageName)
                        if (resId != 0) {
                            effectsPlayer.play(resId)
                        }
                    } catch (e: Exception) {
                        Timber.e("Error playing error sound: ${e.message.toString()}")
                    }
                }
                audioRoute = AudioRouteOption.DETECT
                sendDiagnostics(0f, 0f)
            }
            "screenSaver" -> {
                server.sendSetting("screen_saver", event.newValue)
            }
            "restartZeroconf" -> {
                zeroConf.unregisterService()
                scope.launch {
                    delay(2000)
                    zeroConf.registerService(config.serverPort)
                }
            }
            "pairedDeviceID" -> {
                if (config.pairedDeviceID != "") {
                    Timber.d("Device paired, stopping Zeroconf")
                    zeroConf.unregisterService()
                } else {
                    Timber.d("Device unpaired, starting Zeroconf")
                    zeroConf.registerService(config.serverPort)
                }
            }
            "currentPath" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("current_path", event.newValue.toString())
                        })
                    }
                )
            }
            "screenOn" -> {
                val state = event.newValue as Boolean
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("screen_on", state)
                        })
                    }
                )
            }
            "enableMotionDetection" -> {
                val state = event.newValue as Boolean
                if (state) {
                    motionTask.startCamera()
                } else {
                    motionTask.stopCamera()
                }
            }
            "lastMotion" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("motion_detected", true)
                            put("last_motion", config.lastMotion)
                        })
                    }
                )
            }
            "lastActivity" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("last_activity", config.lastActivity)
                        })
                    }
                )
            }
            "motionDetectionSensitivity" -> {
                motionTask.setSensitivity(event.newValue as Int)
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("BackgroundTask - Event: ${event.eventName} - ${event.newValue}")
        }
    }


    fun startSensors(context: Context) {
        sensorRunner = Sensors(context, object : SensorUpdatesCallback {
            override fun onUpdate(data: MutableMap<String, Any>) {
                val data = buildJsonObject {
                    put("timestamp", Date().toString())
                    putJsonObject("sensors") {
                        // TODO: Use a formalized sensor mapping schema instead of manual type checking and casting.
                        data.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> put(key, value)
                                is Number -> put(key, value.toFloat())
                                else -> {
                                    if (Helpers.isNumber(value.toString())) {
                                        put(key, value.toString().toFloat())
                                    } else {
                                        put(key, value.toString())
                                    }
                                }
                            }
                        }
                    }
                }
                server.sendStatus(data)
            }
        })
        // Start motion sensor
        if (config.enableMotionDetection) {
            motionTask.startCamera()
        }
    }

    fun stopSensors() {
        sensorRunner?.stop()
        motionTask.stopCamera()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun runWakeWordDetection() {
        wakeWordJob = scope.launch {
            delay(1000L)
            engine = WakeWordEngine(context,  if (config.wakeWordEngine == "openwakeword") WakeWordEngineModel.OPENWAKEWORD else WakeWordEngineModel.MICROWAKEWORD)
            engine?.setActiveWakeWords(listOf(config.wakeWord))
            engine?.setActiveStopWords(listOf("stop"))

            sendDiagnostics(0f, 0f)
            warmUpAudioResources()

            engine!!.start().collect {
                when (it) {
                    is WakeWordEngineProvider.AudioResult.WakeDetected -> {
                        holdLastDetectionLevel(it.detection.score)
                        if (it.detection.score >= config.wakeWordThreshold) {
                            val now = System.currentTimeMillis()
                            val lastDetection = detectionCooldowns[it.detection.wakeWordId]

                            if (lastDetection == null || detectionCooldownMs == 0L || now - lastDetection >= detectionCooldownMs) {
                                Timber.i("Wake word detected: ${it.detection.wakeWord}")
                                wakeWordDetected(it.detection, engine!!.isStreaming())
                                detectionCooldowns[it.detection.wakeWordId] = now
                            }
                        }
                    }

                    is WakeWordEngineProvider.AudioResult.StopDetected -> {
                        if (it.detection.detected) {
                            if (it.detection.score > 0.5 && server.pipelineClient?.isActive() == true) {
                                Timber.d("Stop word detected: ${it.detection.wakeWord} (Score: ${it.detection.score})")
                                BroadcastSender.sendBroadcast(
                                    context,
                                    BroadcastSender.STOP_WORD_DETECTED
                                )
                                if (config.stopWordSound != "none") {
                                    try {
                                        val resId = context.resources.getIdentifier(config.stopWordSound, "raw", context.packageName)
                                        if (resId != 0) {
                                            effectsPlayer.play(resId)
                                        }
                                    } catch (e: Exception) {
                                        Timber.e("Error playing stop word sound: ${e.message.toString()}")
                                    }
                                }
                            }
                        }
                    }

                    is WakeWordEngineProvider.AudioResult.Audio -> {
                        if (it.audio.size() > 0) {
                            if (engine!!.isStreaming()) {
                                server.sendAudio(it.audio.toByteArray())
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
                            sendDiagnostics(it.level, lastWakeWordDetectionScore)
                        }
                    }
                    is WakeWordEngineProvider.AudioResult.EngineStatus -> {
                        Timber.i("Engine status: ${it.status}")
                        engineStarted = it.status == "Started"
                    }

                }
            }
        }
    }

    fun terminateWakeWordDetection() {
        if (wakeWordJob != null && wakeWordJob!!.isActive) {
            wakeWordJob?.cancel()
            wakeWordJob = null
        }
        engine = null
        engineStarted = false
        sendDiagnostics(0f, 0f)
        Timber.d("Wake word detection terminated")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun restartWakeWordDetection() {
        Timber.d("Restarting wake word detection")
        terminateWakeWordDetection()
        runWakeWordDetection()
    }

    private fun wakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection, isStreaming: Boolean) {
        Timber.i("${detection.wakeWord} wake word detected at ${detection.score}, threshold is ${config.wakeWordThreshold}")
        lastWakeDetectionTimestamp = detection.timestamp
        firebase.logEvent(
            FirebaseManager.WAKE_WORD_DETECTED, mapOf(
                "wake_word" to config.wakeWord,
                "threshold" to config.wakeWordThreshold.toString(),
                "prediction" to detection.score.toString()
            )
        )
        // if wake up on ww, send event
        if (config.screenOnWakeWord) {
            config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
        }

        if (config.wakeWordSound != "none") {
            try {
                effectsPlayer.play(
                    context.resources.getIdentifier(
                        config.wakeWordSound,
                        "raw",
                        context.packageName
                    )
                )
            } catch (e: Exception) {
                Timber.e("Error playing wake word sound: ${e.message.toString()}")
            }
        }
        holdLastDetectionLevel(detection.score)
        BroadcastSender.sendBroadcast(context, BroadcastSender.WAKE_WORD_DETECTED)
    }

    private fun holdLastDetectionLevel(detectionLevel: Float, duration: Long = 2000) {
        if (detectionLevel > lastWakeWordDetectionScore) {
            lastWakeWordDetectionScore = detectionLevel
            if (holdDetectionLevelJob != null && holdDetectionLevelJob!!.isActive) {
                holdDetectionLevelJob?.cancel()
            }
            holdDetectionLevelJob = scope.launch {
                delay(duration)
                if (audioRoute == AudioRouteOption.DETECT) {
                    lastWakeWordDetectionScore = 0f
                }
            }
        }
    }


    fun sendDiagnostics(audioLevel: Float, detectionLevel: Float) {
        if (config.diagnosticsEnabled) {
            val data = DiagnosticInfo(
                show = config.diagnosticsEnabled,
                engine = config.wakeWordEngine,
                audioLevel = audioLevel * 150,
                detectionLevel = detectionLevel * 10,
                detectionThreshold = config.wakeWordThreshold * 10,
                wakeWord = config.wakeWord,
                mode = if (engine == null || !engineStarted || engine!!.isMuted()) AudioRouteOption.NONE else if (engine!!.isStreaming()) AudioRouteOption.STREAM else AudioRouteOption.DETECT
            )
            val event = Event("diagnosticStats", "", data)
            config.eventBroadcaster.notifyEvent(event)
        }
    }

    private fun warmUpAudioResources() {
        if (config.micOnSound != "none") {
            val resId = context.resources.getIdentifier(config.micOnSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.micOffSound != "none") {
            val resId = context.resources.getIdentifier(config.micOffSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.wakeWordSound != "none") {
            val resId = context.resources.getIdentifier(config.wakeWordSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.processingSound != "none") {
            val resId = context.resources.getIdentifier(config.processingSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.errorSound != "none") {
            val resId = context.resources.getIdentifier(config.errorSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
        if (config.stopWordSound != "none") {
            val resId = context.resources.getIdentifier(config.stopWordSound, "raw", context.packageName)
            if (resId != 0) {
                effectsPlayer.prepare(resId)
            }
        }
    }


    fun shutdown() {
        Timber.i("Shutting down")
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        config.eventBroadcaster.removeListener(this)
        zeroConf.unregisterService()
        motionTask.stopCamera()
        terminateWakeWordDetection()
        stopSensors()
        effectsPlayer.release()
        server.stop()
    }
}