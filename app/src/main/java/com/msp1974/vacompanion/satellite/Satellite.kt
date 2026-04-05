package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.audio.SoundClipPlayer
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.Camera
import com.msp1974.vacompanion.device.SensorUpdatesCallback
import com.msp1974.vacompanion.device.Sensors
import com.msp1974.vacompanion.device.VolumeObserver
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.device.DeviceCapabilitiesData
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import com.msp1974.vacompanion.device.ScreenUtils
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wyoming.SatelliteState
import com.msp1974.vacompanion.wyoming.WyomingPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

interface ISatelliteEvent {
    fun onEvent(event: String, data: JsonObject)
    fun sendSatelliteMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0))
}

enum class AudioRouteOption { NONE, DETECT, PROCESS_NO_DETECT, STREAM}


abstract class Satellite(var context: Context, val config: APPConfig, val scope: CoroutineScope, clientIdString: String, val deviceInfo: DeviceCapabilitiesData): ISatelliteEvent {

    private val firebase = FirebaseManager.getInstance(context)

    private val json = Json { ignoreUnknownKeys = true }
    var clientId = clientIdString
    private val mediaManager: SatelliteMediaManager = SatelliteMediaManager(context, config)
    val soundClipPlayer = SoundClipPlayer(context)


    private var hasInitSettings: Boolean = false

    private var sensorRunner: Sensors? = null
    var motionTask = Camera(context, config)

    private val eventHandler = SatelliteCustomEventHandler(context, config, scope, this)

    private var wakeWordHandler: SatelliteWakeWorkHandler? = null
    private var audioPipeline: SatelliteAudioPipeline? = null
    private var audioPipelineId = AtomicInteger(0)
    private var audioPipelineLastStateChange = System.currentTimeMillis()


    private var continueConversation: Boolean = false

    var state: SatelliteState = SatelliteState.STOPPED
    private var volumeObserver: VolumeObserver? = null



    suspend fun start() {
        // Add config change listeners
        Timber.d("Satellite starting...")
        state = SatelliteState.STARTING

        val loadedSettings = waitForSettings()
        if (!loadedSettings) {
            // Try 1 more time in case of timing issue
            sendSatelliteMessage(clientId,"custom-event", buildJsonObject {
                put("event_type", "settings")
            })
            if (!waitForSettings(2000)) {
                state = SatelliteState.ERROR
                return
            }
        }

        volumeObserver = VolumeObserver(context) { musicVolume, notificationVolume ->
            if (config.musicVolume != musicVolume) {
                config.musicVolume = musicVolume
                sendSetting("music_volume", musicVolume)
            }

            if (config.notificationVolume != notificationVolume) {
                config.notificationVolume = notificationVolume
                sendSetting("notification_volume", notificationVolume)
            }
        }
        volumeObserver?.register()

        val startTime = System.currentTimeMillis()
        scope.launch {
            startSensors()
            warmUpAudioResources()
            startWakeWordDetection()
            eventHandler.run()
        }.join()

        sendDeviceStates()

        Timber.d("Satellite started in ${System.currentTimeMillis() - startTime}ms")

        state = SatelliteState.RUNNING
        BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
    }


    suspend fun waitForSettings(waitTime: Long = 10000): Boolean {
        try {
            withTimeout(waitTime) {
                // Wait for settings to be processed
                while (!hasInitSettings) {
                    delay(10)
                }
                Timber.d("Initial settings downloaded")
            }
            return true
        } catch (e: Exception) {
            Timber.e("Error waiting for settings: ${e.message.toString()}")
            return false
        }
    }

    suspend fun sendDeviceStates() {
        config.doNotDisturb = DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)
        config.screenOn = ScreenUtils(context, config).isScreenOn()

    }



    suspend fun stop() {
        state = SatelliteState.STOPPING

        stopAudioPipeline()

        volumeObserver?.unregister()

        scope.launch {
            eventHandler.stop()
            wakeWordHandler?.stop()
        }.join()


        //terminateWakeWordDetection()
        motionTask.stopCamera()
        stopSensors()
        state = SatelliteState.STOPPED
        BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
    }

    suspend fun processMessage(packet: WyomingPacket) {
        //Timber.d("SATELLITE -> Message received: ${packet.toMap()}")
        when (packet.type) {
            "custom-event" -> customEventHandler(clientId, packet)
            else -> {
                if (audioPipeline != null && audioPipeline?.pipelineStage != PipelineStage.ENDED) {
                    audioPipeline?.processAudioPipelineMessage(packet)
                }
            }
        }
    }

    suspend fun startWakeWordDetection() {
        if (wakeWordHandler != null) {
            wakeWordHandler?.stop()
            wakeWordHandler = null
        }

        if (config.wakeWord == "none") {
            return
        }

        Timber.d("Starting Wake Word Detection")

        wakeWordHandler = object: SatelliteWakeWorkHandler(context, config, scope) {
            override fun onStateChange(state: WakeWordHandlerState) {
                Timber.d("Wake word handler state: $state")
            }

            override fun onAudio(audio: ByteArray) {
                sendAudio(audio)
            }

            override suspend fun onWakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection) {
                Timber.d("Wake word detected: $detection")
                if (audioPipeline != null && audioPipeline?.pipelineStage == PipelineStage.STREAMING_TTS) {
                    onStopWordDetected(detection)
                } else {
                    handleWakeWordDetection()
                }
            }

            override suspend fun onStopWordDetected(detection: WakeWordEngineProvider.WakeWordDetection) {
                if (audioPipeline != null && audioPipeline?.pipelineStage == PipelineStage.STREAMING_TTS) {
                    continueConversation = false
                    audioPipeline?.stop()
                    audioPipeline = null
                }

                if (mediaManager.alarmPlayer.isSounding()) {
                    handleAlarmAction(false, "")
                }

                Timber.d("Stop word detected: $detection")
            }

            override fun onDiagnostics(level: Float, lastDetectionLevel: Float) {
                sendDiagnostics(level, lastDetectionLevel)
            }
        }.also {
            Timber.d("Starting Wake Word Detection")
            it.run()
        }
    }

    suspend fun stopWakeWordDetection() {
        wakeWordHandler?.stop()
        wakeWordHandler = null
    }


    suspend fun restartWakeWordDetection() {
        stopWakeWordDetection()
        startWakeWordDetection()
    }

    fun handleWakeWordDetection() {
        var startNewPipeline = audioPipeline == null || audioPipeline?.pipelineStage == PipelineStage.ENDED

        if (!startNewPipeline) {
            // Check if running pipeline is still valid.  Cancel if not.
            val currentAudioPipelineStageOrdinal = audioPipeline?.pipelineStage!!.ordinal
            val audioPipelineStateAge = ((System.currentTimeMillis() - audioPipelineLastStateChange) / 1000).toInt()

            if (currentAudioPipelineStageOrdinal <= PipelineStage.AWAITING_RESPONSE.ordinal && audioPipelineStateAge > 15) {
                startNewPipeline = true
                Timber.d("Pipeline timed out waiting response, starting new pipeline. State age: $audioPipelineStateAge")
            } else if (currentAudioPipelineStageOrdinal <= PipelineStage.STREAMING_TTS.ordinal && audioPipelineStateAge > 30) {
                startNewPipeline = true
                Timber.d("Pipeline timed out waiting TTS, starting new pipeline. State age: $audioPipelineStateAge")
            }
        }

        if (startNewPipeline) {
            if (audioPipeline != null) {
                audioPipeline?.stop()
                audioPipeline = null
            }

            // if wake up on ww, send event
            if (config.screenOnWakeWord) {
                config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
            }

            startAudioPipeline(continuation = false)
            playWakeWordDetectionSound()
        }
    }

    fun playWakeWordDetectionSound() {
        if (config.wakeWordSound != "none") {
            try {
                scope.launch {
                    soundClipPlayer.play(
                        context.resources.getIdentifier(
                            config.wakeWordSound,
                            "raw",
                            context.packageName
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.e("Error playing wake word sound: ${e.message.toString()}")
            }
        }
    }

    fun playErrorSound() {
        try {
            scope.launch {
                soundClipPlayer.play(
                    context.resources.getIdentifier(
                        "error",
                        "raw",
                        context.packageName
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e("Error playing error sound: ${e.message.toString()}")
        }
    }


    fun muteMicrophone(muted: Boolean) {
        runCatching {
            wakeWordHandler?.engine!!.setMuted(muted)
        }
    }




    fun startAudioPipeline(continuation: Boolean) {
        if (audioPipeline != null) {
            audioPipeline?.stop()
            audioPipeline = null
        }
        continueConversation = config.continueConversation
        val pipelineId = audioPipelineId.getAndAdd(1)
        audioPipeline = object: SatelliteAudioPipeline(context, scope, config, pipelineId, mediaManager, isContinuation = continuation) {
            override fun sendMessage(packet: WyomingPacket) {
                sendSatelliteMessage(clientId, packet.type, packet.data, packet.payload)
            }

            override fun onStateChange(state: PipelineStage) {
                Timber.d("Audio pipeline state: $state")
                audioPipelineLastStateChange = System.currentTimeMillis()
                when (state) {
                    PipelineStage.LISTENING -> {
                        handleVolumeDucking("all", true)
                        wakeWordHandler?.engine!!.setStreaming(true)
                    }
                    PipelineStage.VOICE_STOPPED -> { wakeWordHandler?.engine!!.setStreaming(false) }
                    PipelineStage.ENDED -> {
                        if (continueConversation) {
                            startAudioPipeline(continuation = true)
                        } else {
                            handleVolumeDucking("all", false)
                        }
                    }
                    else -> {}
                }
            }

            override fun onFinish(reason: PipelineEndReason) {
                if (reason != PipelineEndReason.END_OF_PIPELINE) {
                    continueConversation = false
                }
                if (reason == PipelineEndReason.ERRORED && config.wakeWordSound != "none") {
                    playErrorSound()
                }
            }

        }.also {
            it.run()
        }
    }

    fun handleVolumeDucking(type: String, enable: Boolean) {
        scope.launch(Dispatchers.Main) {
            mediaManager.updateVolumeDucking(type, enable)
        }
    }

    fun sendAudio(audio: ByteArray) {
        if (audioPipeline != null) {
            audioPipeline?.sendMicAudio(audio)
        }
    }

    fun stopAudioPipeline() {
        audioPipeline?.stop()
        audioPipeline = null
    }

    // *************************************************************************
    // Custom Events
    // *************************************************************************
    private suspend fun customEventHandler(clientId: String, packet: WyomingPacket) {
        val eventType = packet.getProp("event_type")

        when (eventType) {
            "action" -> handleAction(packet.getProp("action"), packet)
            "settings" -> handleSettings(packet.getProp("settings"))
            "capabilities" -> handleCapabilities(clientId)
        }
    }

    private suspend fun handleAction(action: String, packet: WyomingPacket) {
        Timber.d("Action received: $action")
        runCatching {
            val payloadStr = packet.getProp("payload")
            when (action) {
                "intent-output" -> {
                    if (audioPipeline != null && audioPipeline?.pipelineStage == PipelineStage.AWAITING_RESPONSE && !continueConversation) {
                        if (packet.getProp("data") != "") {
                            val data = json.parseToJsonElement(packet.getProp("data")).jsonObject
                            val intentOutput = data.get("intent_output")?.jsonObject
                            if (intentOutput != null) {
                                continueConversation =
                                    intentOutput["continue_conversation"]?.jsonPrimitive?.boolean
                                        ?: config.continueConversation
                            }
                            Timber.d("Continue conversation: $continueConversation")
                        }
                    }
                }
                "play-media", "play", "pause", "stop", "set-volume" -> { handleMediaPlayerAction(action, payloadStr) }
                "toast-message" -> if (payloadStr.isNotEmpty()) {
                    val msg = Json.parseToJsonElement(payloadStr).jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    BroadcastSender.sendBroadcast(context, BroadcastSender.TOAST_MESSAGE, msg)
                }
                "refresh" -> config.eventBroadcaster.notifyEvent(Event("refresh", "", ""))
                "screen-wake" -> config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
                "screen-sleep" -> config.eventBroadcaster.notifyEvent(Event("screenSleep", "", ""))
                "wake" -> scope.launch {handleWakeWordDetection()}
                "alarm" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    handleAlarmAction(payload["activate"]?.jsonPrimitive?.booleanOrNull ?: false, payload["url"]?.jsonPrimitive?.contentOrNull ?: "")
                }
            }
        }.onFailure { Timber.e("Failed to handle custom action $action: $it") }
    }

    private suspend fun handleMediaPlayerAction(action: String, payloadStr: String) {
        withContext(Dispatchers.Main) {
            when (action) {
                "play-media" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    mediaManager.musicPlayer.play(payload["url"]?.jsonPrimitive?.content ?: "")
                    mediaManager.musicPlayer.setVolume(
                        payload["volume"]?.jsonPrimitive?.intOrNull ?: 100
                    )
                }

                "play" -> mediaManager.musicPlayer.resume()
                "pause" -> mediaManager.musicPlayer.pause()
                "stop" -> mediaManager.musicPlayer.stop()
                "set-volume" -> if (payloadStr.isNotEmpty()) {
                    mediaManager.musicPlayer.setVolume(
                        Json.parseToJsonElement(payloadStr).jsonObject["volume"]?.jsonPrimitive?.intOrNull
                            ?: 100
                    )
                }
            }
        }
    }

    private suspend fun handleAlarmAction(enable: Boolean, url: String = "") {
        withContext(Dispatchers.Main) {
            if (enable) {
                mediaManager.updateVolumeDucking("music", true)
                mediaManager.alarmPlayer.startAlarm(url)
                config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
            } else {
                mediaManager.alarmPlayer.stopAlarm()
                mediaManager.updateVolumeDucking("music", false)
            }
            sendSetting("alarm", enable)
        }
    }

    private fun handleSettings(settings: String) {
        config.processSettings(settings)
        hasInitSettings = true
    }

    private fun handleCapabilities(clientId: String) {
        sendSatelliteMessage(clientId, "capabilities", DeviceCapabilitiesManager.toJson(deviceInfo))
    }

    // *************************************************************************
    // Send messages
    // *************************************************************************
    fun sendStatus(data: JsonObject) {
        sendCustomEvent("status", data)
    }

    fun sendSetting(name: String, value: Any) {
        sendCustomEvent("settings", buildJsonObject {
            put("timestamp", isoNow())
            putJsonObject("settings") {
                when (value) {
                    is String -> put(name, value)
                    is Boolean -> put(name, value)
                    is Number -> put(name, value)
                    is JsonElement -> put(name, value)
                }
            }
        })
    }

    fun sendEvent(type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        sendSatelliteMessage(clientId, type, data, payload)
    }

    fun sendCustomEvent(type: String, data: JsonObject) {
        sendSatelliteMessage(clientId, "custom-event", buildJsonObject {
            put("event_type", type)
            put("data", data)
        })
    }



    // *************************************************************************
    // ****
    // *************************************************************************
    private fun warmUpAudioResources() {
        scope.launch(Dispatchers.Default) {
            soundClipPlayer.prepare(R.raw.error)
            soundClipPlayer.prepare(R.raw.stop_word)
            if (config.wakeWordSound != "none") {
                val resId = context.resources.getIdentifier(
                    config.wakeWordSound,
                    "raw",
                    context.packageName
                )
                if (resId != 0) {
                    soundClipPlayer.prepare(resId)
                }
            }
        }
    }

    fun startSensors() {
        sensorRunner = Sensors(context, config, object : SensorUpdatesCallback {
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
                sendStatus(data)
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

    fun sendDiagnostics(audioLevel: Float, detectionLevel: Float) {
        if (config.diagnosticsEnabled) {
            val data = DiagnosticInfo(
                show = config.diagnosticsEnabled,
                engine = config.wakeWordEngine,
                audioLevel = audioLevel * 150,
                detectionLevel = detectionLevel * 10,
                detectionThreshold = config.wakeWordThreshold * 10,
                wakeWord = config.wakeWord,
                mode = if (audioPipeline != null && audioPipeline?.pipelineStage == PipelineStage.LISTENING) AudioRouteOption.STREAM else AudioRouteOption.DETECT
            )
            val event = Event("diagnosticStats", "", data)
            config.eventBroadcaster.notifyEvent(event)
        }
    }

    companion object {
        fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    }

}