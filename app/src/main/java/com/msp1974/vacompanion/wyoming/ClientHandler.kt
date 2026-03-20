package com.msp1974.vacompanion.wyoming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Logger
import io.github.z4kn4fein.semver.toVersion
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles communication with a Wyoming client over a TCP socket.
 */
class ClientHandler(
    private val context: Context,
    private val server: WyomingTCPServer,
    private val client: Socket
) {
    private val log = Logger()
    private val config = APPConfig.getInstance(context)
    val clientId: Int = client.port
    private val reader = DataInputStream(client.getInputStream())
    private val writer = DataOutputStream(client.getOutputStream())
    private val messenger = WyomingMessenger(clientId, reader, writer, config.version, log)
    private val handler = Handler(Looper.getMainLooper())

    private val isRunning = AtomicBoolean(true)
    
    @Volatile
    private var satelliteStatus = SatelliteState.STOPPED
    
    @Volatile
    private var pipelineStatus = PipelineStatus.INACTIVE
    
    private val connectionId = client.inetAddress.hostAddress ?: "unknown"
    private var pingTimer: Timer? = null

    private val mediaManager = WyomingMediaManager(context)
    private val actionHandler = WyomingActionHandler(context, config, mediaManager, log)
    private val infoBuilder = WyomingInfoBuilder(context, config, server.deviceInfo)

    @Volatile
    private var receivedSynthesize = false
    @Volatile
    private var pendingWakeWord = false
    
    private val activePipelines = AtomicInteger(0)
    private var currentSession: PipelineSession? = null

    private class PipelineSession(val id: Int) {
        @Volatile var logicFinished = false
        @Volatile var audioFinished = false
        @Volatile var finalized = false
    }

    private fun isPipelineActive() = currentSession != null

    private val pipelineTimeoutRunnable = Runnable { handlePipelineTimeout() }

    private val broadcastExecutor = Executors.newSingleThreadExecutor()

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (satelliteStatus != SatelliteState.RUNNING) return
            
            broadcastExecutor.execute {
                when {
                    mediaManager.pcmMediaPlayer.isPlaying -> {
                        sendAudioStop()
                        mediaManager.pcmMediaPlayer.stop()
                        mediaManager.updateVolumeDucking("music", false)
                    }
                    mediaManager.alarmPlayer.isSounding -> {
                        handleAlarmAction(false)
                    }
                    intent.action == BroadcastSender.WAKE_WORD_DETECTED -> {
                        handleWakeWordDetected()
                    }
                    intent.action == BroadcastSender.STOP_WORD_DETECTED -> {
                        log.d("Stop word detected - resetting pipeline")
                        resetPipeline()
                    }
                }
            }
        }
    }

    private val intentFilter = IntentFilter().apply {
        addAction(BroadcastSender.WAKE_WORD_DETECTED)
        addAction(BroadcastSender.STOP_WORD_DETECTED)
    }

    /**
     * Main loop for processing incoming events from the client.
     */
    fun run() {
        val totalConnections = config.atomicConnectionCount.incrementAndGet()
        log.d("Client $clientId connected from $connectionId. Total connections: $totalConnections")
        startPingTimer()
        
        try {
            while (isRunning.get() && !client.isClosed) {
                // Blocking read. If readEvent returns null it means a recoverable parse error happened.
                val packet = readEvent() ?: continue
                processEvent(packet)
            }
        } catch (_: EOFException) {
            log.d("Connection $clientId closed by peer.")
        } catch (ex: Exception) {
            if (isRunning.get()) {
                log.e("Connection $clientId terminated due to exception: $ex")
            }
        } finally {
            stop()
        }
    }

    /**
     * Stops the client handler and cleans up resources.
     */
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        
        log.d("Stopping client $clientId connection handler")
        stopPingTimer()

        if (satelliteStatus == SatelliteState.RUNNING) {
            stopSatelliteInternal()
        }
        
        mediaManager.release()
        runCatching { client.close() }
        broadcastExecutor.shutdown()
        runCatching { broadcastExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
        broadcastExecutor.shutdownNow()

        val remaining = config.atomicConnectionCount.decrementAndGet()
        log.w("$connectionId:$clientId disconnected. Remaining connections: $remaining")
    }

    private fun handleWakeWordDetected() {
        mediaManager.updateVolumeDucking("all", true)
        if (pipelineStatus == PipelineStatus.LISTENING || isPipelineActive()) {
            // Abort current pipeline cleanly
            releaseInputAudioStream()
            sendAudioStop()
            pendingWakeWord = true
        } else {
            sendWakeWordDetection()
            sendStartPipeline()
        }
    }

    private fun startSatellite() {
        val currentVer = config.version.toVersion()
        val minVer = config.minRequiredApkVersion.toVersion()
        
        if (currentVer < minVer) {
            log.d("Update required. App version: $currentVer, Minimum: $minVer")
            BroadcastSender.sendBroadcast(context, BroadcastSender.VERSION_MISMATCH)
            return
        }

        synchronized(server) {
            if (config.pairedDeviceID.isEmpty()) {
                config.pairedDeviceID = connectionId
            }

            if (config.pairedDeviceID == connectionId) {
                log.d("Starting satellite service for $clientId")
                LocalBroadcastManager.getInstance(context).registerReceiver(wakeWordReceiver, intentFilter)

                config.homeAssistantConnectedIP = connectionId

                // Ensure media players are stopped on new or takeover session
                handleAlarmAction(false)
                mediaManager.musicPlayer.stop()

                val oldClient = server.pipelineClient
                if (oldClient != null && oldClient != this) {
                    log.d("Satellite session takeover by $clientId from ${oldClient.clientId}")
                    oldClient.stop()
                }
                
                server.pipelineClient = this
                satelliteStatus = SatelliteState.RUNNING
                server.satelliteStarted()
                log.d("Satellite session started for $clientId")
            } else {
                log.i("Unauthorized connection attempt from $connectionId:$clientId. Aborting.")
                stop()
            }
        }
        config.isRunning = satelliteStatus == SatelliteState.RUNNING
    }

    private fun stopSatellite() {
        stopSatelliteInternal()
        isRunning.set(false)
    }

    private fun stopSatelliteInternal() {
        log.d("Stopping satellite service for $clientId")
        LocalBroadcastManager.getInstance(context).unregisterReceiver(wakeWordReceiver)
        
        synchronized(server) {
            if (server.pipelineClient == this) {
                if (pipelineStatus == PipelineStatus.LISTENING) {
                    releaseInputAudioStream()
                }

                handleAlarmAction(false)
                mediaManager.musicPlayer.stop()

                server.pipelineClient = null
                server.satelliteStopped()
                config.homeAssistantConnectedIP = ""
            }
            
            pipelineStatus = PipelineStatus.INACTIVE
            satelliteStatus = SatelliteState.STOPPED
        }
        config.isRunning = false
    }

    private fun requestInputAudioStream() {
        if (pipelineStatus != PipelineStatus.LISTENING) {
            log.d("Requesting audio input stream for $clientId")
            pipelineStatus = PipelineStatus.LISTENING
            server.requestInputAudioStream()
        }
    }

    private fun releaseInputAudioStream() {
        if (pipelineStatus != PipelineStatus.INACTIVE) {
            log.d("Releasing audio input stream for $clientId")
            pipelineStatus = PipelineStatus.INACTIVE
            server.releaseInputAudioStream()
        }
    }

    private fun processEvent(event: WyomingPacket) {
        if (event.type !in IGNORED_LOG_EVENTS) {
            log.d("Event received - $clientId: ${event.toMap()}")
        }

        try {
            when (event.type) {
                "ping" -> sendPong()
                "describe" -> sendInfo()
                "settings",
                "custom-settings" -> processSettingsPacket(event)
                "capabilities" -> sendCapabilities()
                "run-satellite" -> startSatellite()
                "action",
                "custom-action" -> handleCustomAction(event)
                "custom-event" -> handleCustomEvent(event)
            }

            if (satelliteStatus == SatelliteState.RUNNING) {
                processSatelliteEvent(event)
            }
        } catch (ex: Exception) {
            log.e("Error processing event ${event.type}: $ex")
        }
    }

    private fun processSatelliteEvent(event: WyomingPacket) {
        when (event.type) {
            "pause-satellite" -> stopSatellite()
            "transcribe" -> {
                mediaManager.updateVolumeDucking("all", true)
                requestInputAudioStream()
                setPipelineTimeout(10)
            }
            "voice-started" -> setPipelineTimeout(30)
            "voice-stopped" -> setPipelineTimeout(15)
            "transcript" -> handleTranscript(event)
            "synthesize" -> handleSynthesize(event)
            "pipeline-ended" -> handlePipelineEnded()
            "audio-start" -> handleAudioStart()
            "audio-chunk" -> handleAudioChunk(event)
            "audio-stop" -> handleAudioStop()
            "error" -> handlePipelineError(event)
            "timer-finished" -> handleAlarmAction(true)
        }
    }

    private fun handleTranscript(event: WyomingPacket) {
        if (isPipelineActive()) {
            releaseInputAudioStream()
            if (event.getProp("text").lowercase().contains("never mind")) {
                mediaManager.updateVolumeDucking("all", false)
            } else {
                setPipelineTimeout(10)
            }
        }
    }

    private fun handleSynthesize(event: WyomingPacket) {
        if (isPipelineActive()) {
            pipelineStatus = PipelineStatus.AWAITING_TTS
            receivedSynthesize = true
            setPipelineTimeout(10)
        }
    }

    private fun handlePipelineEnded() {
        val session = currentSession ?: return

        session.logicFinished = true

        if (pipelineStatus == PipelineStatus.AWAITING_TTS || pipelineStatus == PipelineStatus.STREAMING) {
            log.d("Pipeline ended but TTS is in status $pipelineStatus. Waiting for audio to complete session ${session.id}.")
            return
        }

        checkFinalizeSession(session)
    }

    private fun checkFinalizeSession(session: PipelineSession) {
        // A session is complete if logic ended AND (audio ended OR we never expected audio)
        val audioDone = session.audioFinished || !receivedSynthesize
        
        if (session.logicFinished && audioDone && !session.finalized) {
            session.finalized = true
            if (currentSession == session) {
                activePipelines.decrementAndGet()
                finalizePipeline()
                currentSession = null
            }
        }
    }

    private fun finalizePipeline() {
        cancelPipelineTimeout()
        mediaManager.updateVolumeDucking("all", false)
        if (pipelineStatus != PipelineStatus.STREAMING) {
            releaseInputAudioStream()
        }
        processPendingWakeWord()
    }

    private fun handleAudioStart() {
        if (isPipelineActive()) {
            cancelPipelineTimeout()
            pipelineStatus = PipelineStatus.STREAMING
            mediaManager.updateVolumeDucking("all", true)
            mediaManager.pcmMediaPlayer.play()
        }
    }

    private fun handleAudioChunk(event: WyomingPacket) {
        if (isPipelineActive() && mediaManager.pcmMediaPlayer.isPlaying) {
            mediaManager.pcmMediaPlayer.writeAudio(event.payload)
        }
    }

    private fun handleAudioStop() {
        if (isPipelineActive()) {
            if (mediaManager.pcmMediaPlayer.isPlaying) {
                sendEvent("played")
                mediaManager.pcmMediaPlayer.stop()
            }
            
            pipelineStatus = PipelineStatus.INACTIVE
            val hadSynthesize = receivedSynthesize
            val session = currentSession
            
            if (session != null) {
                session.audioFinished = true
                checkFinalizeSession(session)
            }

            if (hadSynthesize) {
                if (config.continueConversation) {
                    sendStartPipeline()
                } else {
                    setPipelineTimeout(2)
                }
            } else {
                setPipelineTimeout(1) // Clean up shortly
            }
        }
    }

    private fun handlePipelineError(event: WyomingPacket) {
        config.eventBroadcaster.notifyEvent(Event("recognitionError", "", event.getProp("code")))
        resetPipeline()
        processPendingWakeWord()
    }

    private fun processPendingWakeWord() {
        if (pendingWakeWord) {
            pendingWakeWord = false
            mediaManager.updateVolumeDucking("all", true)
            sendWakeWordDetection()
            sendStartPipeline()
        }
    }

    private fun setPipelineTimeout(durationSeconds: Int) {
        cancelPipelineTimeout()
        handler.postDelayed(pipelineTimeoutRunnable, durationSeconds * 1000L)
    }

    private fun cancelPipelineTimeout() {
        handler.removeCallbacks(pipelineTimeoutRunnable)
    }

    private fun handlePipelineTimeout() {
        log.d("Pipeline timed out")
        resetPipeline()
        processPendingWakeWord()
    }

    private fun resetPipeline() {
        activePipelines.set(0)
        currentSession = null
        receivedSynthesize = false
        mediaManager.updateVolumeDucking("all", false)
        if (pipelineStatus != PipelineStatus.INACTIVE) {
            releaseInputAudioStream()
        }
        sendAudioStop()
        pipelineStatus = PipelineStatus.INACTIVE
    }

    private fun processSettingsPacket(packet: WyomingPacket) {
        val settings = packet.getProp("settings").ifEmpty { packet.data.toString() }
        config.processSettings(settings)
    }

    private fun handleCustomEvent(event: WyomingPacket) {
        // TODO: Normalize incoming custom events into a standard structure in a dedicated parser.
        // HA integration sometimes nests data in a "data" key, sometimes puts it at top level.
        val eventData = event.data["data"]?.jsonObject ?: event.data
        val eventType = event.getProp("event_type")
        val innerPacket = WyomingPacket(eventType, eventData)

        when (eventType) {
            "action" -> handleCustomAction(innerPacket)
            "settings" -> processSettingsPacket(innerPacket)
            "capabilities" -> sendCapabilities()
            else -> {
                // Handle as action if eventType is the action name
                actionHandler.handleAction(eventType, eventData.toString()) { enable, url ->
                    handleAlarmAction(enable, url)
                }
            }
        }
    }

    private fun handleCustomAction(event: WyomingPacket) {
        val action = event.getProp("action")
        val payloadStr = event.getProp("payload")
        
        actionHandler.handleAction(action, payloadStr) { enable, url ->
            handleAlarmAction(enable, url)
        }
    }

    private fun handleAlarmAction(enable: Boolean, url: String = "") {
        if (enable) {
            mediaManager.updateVolumeDucking("music", true)
            mediaManager.alarmPlayer.startAlarm(url)
            config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
        } else {
            mediaManager.alarmPlayer.stopAlarm()
            mediaManager.updateVolumeDucking("music", false)
        }
        sendSettingChange("alarm", enable)
    }

    private fun startPingTimer() {
        stopPingTimer()
        pingTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    sendEvent("ping", buildJsonObject { put("text", "") })
                }
            }, 0, 2000)
        }
    }

    private fun stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = null
    }

    fun sendPong() {
        sendEvent("pong", buildJsonObject { put("text", "") })
    }

    fun sendInfo() {
        sendEvent("info", infoBuilder.buildInfo())
    }

    fun sendWakeWordDetection() {
        sendEvent("detection", buildJsonObject {
            put("name", config.wakeWord)
            put("timestamp", isoNow())
            put("speaker", "")
        })
    }

    fun sendStartPipeline() {
        val nextId = activePipelines.incrementAndGet()
        currentSession = PipelineSession(nextId)
        
        sendEvent("run-pipeline", buildJsonObject {
            put("name", "VACA ${config.uuid}")
            put("start_stage", "asr")
            put("end_stage", "tts")
            put("restart_on_end", false)
            putJsonObject("snd_format") {
                put("rate", config.sampleRate)
                put("width", config.audioWidth)
                put("channels", config.audioChannels)
            }
        })
        receivedSynthesize = false
        setPipelineTimeout(10)
    }

    fun sendAudioStop() {
        sendEvent("audio-stop", buildJsonObject {
            put("timestamp", isoNow())
        })
    }

    fun sendAudio(audio: ByteArray) {
        if (pipelineStatus != PipelineStatus.LISTENING) return

        val data = buildJsonObject {
            put("rate", config.sampleRate)
            put("width", config.audioWidth)
            put("channels", config.audioChannels)
        }
        sendEvent("audio-chunk", data, audio)
    }

    fun sendSettingChange(name: String, value: Any) {
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

    // TODO: Standardize these to use top-level Wyoming events once the HA vaca component supports them.
    // Currently, they must be wrapped in a "custom-event" for compatibility.
    fun sendStatus(data: JsonObject) {
        sendCustomEvent("status", data)
    }

    // TODO: Move this wrapping into the info builder or a dedicated protocol layer.
    fun sendCapabilities() {
        val data = DeviceCapabilitiesManager.toJson(server.deviceInfo)
        sendCustomEvent("capabilities", data)
    }

    fun sendCustomEvent(type: String, data: JsonObject) {
        sendEvent("custom-event", buildJsonObject {
            put("event_type", type)
            put("data", data)
        })
    }

    fun sendEvent(type: String, data: JsonObject = buildJsonObject {}, payload: ByteArray = ByteArray(0)) {
        runCatching {
            val event = WyomingPacket(type, data, payload)
            messenger.sendEvent(event, pipelineStatus)
        }.onFailure { log.e("Failed to send event $type: $it") }
    }

    private fun readEvent(): WyomingPacket? = messenger.readEvent()

    companion object {
        private val IGNORED_LOG_EVENTS = setOf("ping", "pong", "audio-chunk")
        private fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    }
}
