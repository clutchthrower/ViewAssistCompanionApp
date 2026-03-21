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
    private val client: Socket,
    private val log: Logger = Logger(),
    private val config: APPConfig = APPConfig.getInstance(context),
    private val messenger: WyomingMessenger = WyomingMessenger(
        client.port,
        DataInputStream(client.getInputStream()),
        DataOutputStream(client.getOutputStream()),
        config.version,
        log
    ),
    private val mediaManager: WyomingMediaManager = WyomingMediaManager(context),
    private val actionHandler: WyomingActionHandler = WyomingActionHandler(context, config, mediaManager, log),
    private val infoBuilder: WyomingInfoBuilder = WyomingInfoBuilder(context, config, server.deviceInfo),
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) {
    // Infrastructure & Resources
    val clientId: Int = client.port
    private val connectionId = client.inetAddress.hostAddress ?: "unknown"
    private val sendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WyomingSender-$clientId")
    }
    private val broadcastExecutor = Executors.newSingleThreadExecutor()

    // Connection & Pipeline State
    private val isRunning = AtomicBoolean(true)
    @Volatile private var satelliteState = SatelliteState.STOPPED
    @Volatile private var pipelineStage = PipelineStage.IDLE
    
    // Session State (Tracked per voice interaction)
    private val sessionIdGenerator = AtomicInteger(0)
    private var currentSession: PipelineSession? = null
    @Volatile private var isExpectingTtsAudio = false
    
    private var pingTimer: Timer? = null

    // Runnables
    private val pipelineTimeoutRunnable = Runnable { handlePipelineTimeout() }

    // Broadcasts
    private val intentFilter = IntentFilter().apply {
        addAction(BroadcastSender.WAKE_WORD_DETECTED)
        addAction(BroadcastSender.STOP_WORD_DETECTED)
    }

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (satelliteState != SatelliteState.RUNNING) return
            
            broadcastExecutor.execute {
                handleBroadcastIntent(intent)
            }
        }
    }

    private fun isPipelineActive() = currentSession != null

    private fun handleBroadcastIntent(intent: Intent) {
        // Stop active media/alarms if needed when a wake word is detected
        if (mediaManager.pcmMediaPlayer.isPlaying) {
            mediaManager.pcmMediaPlayer.stop()
            mediaManager.updateVolumeDucking("music", false)
        }
        if (mediaManager.alarmPlayer.isSounding) {
            handleAlarmAction(false)
        }

        when (intent.action) {
            BroadcastSender.WAKE_WORD_DETECTED -> {
                onWakeWordDetected()
            }
            BroadcastSender.STOP_WORD_DETECTED -> {
                log.d("Stop word detected - resetting pipeline")
                resetCurrentPipeline()
            }
        }
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

        if (satelliteState == SatelliteState.RUNNING) {
            stopSatelliteInternal()
        }



        
        mediaManager.release()
        runCatching { client.close() }
        sendExecutor.shutdown()
        broadcastExecutor.shutdown()
        runCatching {
            sendExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
            broadcastExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
        }
        sendExecutor.shutdownNow()
        broadcastExecutor.shutdownNow()

        val remaining = config.atomicConnectionCount.decrementAndGet()
        log.w("$connectionId:$clientId disconnected. Remaining connections: $remaining")
    }

    // region Lifecycle & Connectivity

    internal fun onWakeWordDetected() {
        if (pipelineStage != PipelineStage.IDLE || isPipelineActive()) {
            if (pipelineStage == PipelineStage.STREAMING || pipelineStage == PipelineStage.AWAITING_TTS) {
                log.d("Interrupting response ($pipelineStage) for new wake word")
                resetCurrentPipeline()
                initiatePipeline()
                return
            }
        }

        log.d("Wake word detected. Starting pipeline.")
        mediaManager.updateVolumeDucking("all", true)
        initiatePipeline()
    }

    internal fun startSatellite() {
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
                satelliteState = SatelliteState.RUNNING
                server.satelliteStarted()
                log.d("Satellite session started for $clientId")
            } else {
                log.i("Unauthorized connection attempt from $connectionId:$clientId. Aborting.")
                stop()
            }
        }
        config.isRunning = satelliteState == SatelliteState.RUNNING
    }

    private fun stopSatellite() {
        stopSatelliteInternal()
        isRunning.set(false)
    }

    internal fun stopSatelliteInternal() {
        log.d("Stopping satellite service for $clientId")
        LocalBroadcastManager.getInstance(context).unregisterReceiver(wakeWordReceiver)
        
        synchronized(server) {
            if (server.pipelineClient == this) {
                if (pipelineStage == PipelineStage.LISTENING) {
                    releaseInputAudioStream()
                }

                handleAlarmAction(false)
                mediaManager.musicPlayer.stop()

                server.pipelineClient = null
                server.satelliteStopped()
                config.homeAssistantConnectedIP = ""
            }
            
            pipelineStage = PipelineStage.IDLE
            satelliteState = SatelliteState.STOPPED
        }
        config.isRunning = false
    }

    private fun requestInputAudioStream() {
        if (pipelineStage != PipelineStage.LISTENING) {
            log.d("Requesting audio input stream for $clientId")
            pipelineStage = PipelineStage.LISTENING
            server.requestInputAudioStream()
        }
    }

    private fun releaseInputAudioStream() {
        if (pipelineStage != PipelineStage.IDLE) {
            log.d("Releasing audio input stream for $clientId")
            pipelineStage = PipelineStage.IDLE
            server.releaseInputAudioStream()
        }
    }

    // endregion

    // region Event Processing

    internal fun processEvent(packet: WyomingPacket) {
        if (packet.type !in IGNORED_LOG_EVENTS) {
            log.d("Event received - $clientId: ${packet.toMap()}")
        }

        try {
            when (packet.type) {
                "ping" -> sendPong()
                "describe" -> sendInfo()
                "settings",
                "custom-settings" -> processSettingsPacket(packet)
                "capabilities" -> sendCapabilities()
                "run-satellite" -> startSatellite()
                "action",
                "custom-action" -> handleCustomAction(packet)
                "custom-event" -> handleCustomEvent(packet)
            }

            if (satelliteState == SatelliteState.RUNNING) {
                processSatelliteEvent(packet)
            }
        } catch (ex: Exception) {
            log.e("Error processing event ${packet.type}: $ex")
        }
    }

    private fun processSatelliteEvent(event: WyomingPacket) {
        when (event.type) {
            "pause-satellite" -> stopSatellite()
            "transcribe" -> {
                if (currentSession == null) {
                    log.d("Ignoring transcribe — no pipeline session (stale or very early)")
                    return
                }
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
                resetCurrentPipeline()
            } else {
                setPipelineTimeout(15) // Waiting for synthesis
            }
        }
    }

    private fun handleSynthesize(event: WyomingPacket) {
        val session = currentSession
        if (session != null) {
            pipelineStage = PipelineStage.AWAITING_TTS
            isExpectingTtsAudio = true
            
            // Check for continue_conversation flag in the synthesize data (HA might pass it here)
            findContinueConversation(event.data)?.let {
                log.d("Found continue_conversation=$it in synthesize event")
                session.forceContinue = it
            }

            setPipelineTimeout(20)
        }
    }

    private fun handlePipelineEnded() {
        val session = currentSession ?: return
        session.logicFinished = true

        if (pipelineStage == PipelineStage.AWAITING_TTS || pipelineStage == PipelineStage.STREAMING) {
            log.d("Pipeline ended but TTS is in stage $pipelineStage. Waiting for audio to complete session ${session.id}.")
            return
        }

        checkFinalizeSession(session)
    }

    private fun checkFinalizeSession(session: PipelineSession) {
        // A session is complete if logic ended AND (audio ended OR we never expected audio)
        val audioDone = session.audioFinished || !isExpectingTtsAudio
        
        if (session.logicFinished && audioDone && !session.finalized) {
            session.finalized = true
            if (currentSession == session) {
                cleanupPipeline()
                currentSession = null
            }
        }
    }

    private fun cleanupPipeline() {
        cancelPipelineTimeout()
        mediaManager.updateVolumeDucking("all", false)
        if (pipelineStage != PipelineStage.STREAMING) {
            releaseInputAudioStream()
        }
    }

    internal fun handleAudioStart() {
        if (isPipelineActive()) {
            cancelPipelineTimeout()
            pipelineStage = PipelineStage.STREAMING
            mediaManager.updateVolumeDucking("all", true)
            mediaManager.pcmMediaPlayer.play()
        }
    }

    internal fun handleAudioChunk(event: WyomingPacket) {
        if (isPipelineActive() && mediaManager.pcmMediaPlayer.isPlaying) {
            mediaManager.pcmMediaPlayer.writeAudio(event.payload)
        }
    }

    internal fun handleAudioStop() {
        if (isPipelineActive()) {
            if (mediaManager.pcmMediaPlayer.isPlaying) {
                // We send 'played' but we DON'T stop immediately, to allow draining.
                // The next synthesizer or a reset will stop it properly.
                sendEvent("played")
            }
            
            pipelineStage = PipelineStage.IDLE
            val hadSynthesize = isExpectingTtsAudio
            val session = currentSession
            
            if (session != null) {
                session.audioFinished = true
                val shouldContinue = session.forceContinue
                
                checkFinalizeSession(session)
                
                if (hadSynthesize && shouldContinue) {
                    log.d("Continuing conversation as requested by Home Assistant Turn result.")
                    initiatePipeline(precedeWithWakeDetection = false)
                }
            }
        }
    }

    private fun handlePipelineError(event: WyomingPacket) {
        val code = event.getProp("code")
        val text = event.getProp("text")
        
        val isDuplicateWakeUp = code == "duplicate_wake_up_detected"
                                
        if (isDuplicateWakeUp) {
            log.d("Speech-to-text cancelled to avoid duplicate wake-up. Handled gracefully.")
        }
        
        val toastMessage = if (text.isNotEmpty()) text else "Error: $code"
        config.eventBroadcaster.notifyEvent(Event("recognitionError", toastMessage, code))
        
        resetCurrentPipeline()
    }

    private fun setPipelineTimeout(durationSeconds: Int) {
        cancelPipelineTimeout()
        mainHandler.postDelayed(pipelineTimeoutRunnable, durationSeconds * 1000L)
    }

    private fun cancelPipelineTimeout() {
        mainHandler.removeCallbacks(pipelineTimeoutRunnable)
    }

    private fun handlePipelineTimeout() {
        log.d("Pipeline timed out")
        resetCurrentPipeline()
    }

    private fun resetCurrentPipeline() {
        val wasListening = pipelineStage == PipelineStage.LISTENING
        val sessionIdForAudioStop = currentSession?.id
        currentSession = null
        isExpectingTtsAudio = false
        mediaManager.updateVolumeDucking("all", false)
        mediaManager.pcmMediaPlayer.stop()
        
        if (pipelineStage != PipelineStage.IDLE) {
            releaseInputAudioStream()
        }
        
        if (wasListening) {
            sendAudioStop(sessionIdForAudioStop)
        }
        
        pipelineStage = PipelineStage.IDLE
        cancelPipelineTimeout()
    }

    // endregion

    // region Settings & Custom Commands

    private fun processSettingsPacket(packet: WyomingPacket) {
        val settingsStr = packet.getProp("settings").ifEmpty { packet.data.toString() }
        config.processSettings(settingsStr)
    }

    private fun handleCustomEvent(event: WyomingPacket) {
        // HA integration sometimes nests data in a "data" key, sometimes puts it at top level.
        val eventData = event.data["data"]?.jsonObject ?: event.data
        val eventType = event.getProp("event_type")

        // Look for continue_conversation in any custom event to set session state
        findContinueConversation(eventData)?.let {
            log.d("Continuing conversation state updated from custom event ($eventType): $it")
            currentSession?.forceContinue = it
        }

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

    // endregion

    // region Outbound Events

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

    /**
     * Initiates a pipeline run. 
     * @param precedeWithWakeDetection when true, sends `detection` and `run-pipeline` back-to-back
     * in one [sendExecutor] task to ensure no other events interleave on the wire.
     */
    fun initiatePipeline(precedeWithWakeDetection: Boolean = true) {
        if (pipelineStage == PipelineStage.LISTENING) {
            releaseInputAudioStream()
        }
        val nextId = sessionIdGenerator.incrementAndGet()

        val runPipelinePacket = WyomingPacket(
            "run-pipeline",
            buildJsonObject {
                put("name", "VACA ${config.uuid}")
                put("start_stage", "asr")
                put("end_stage", "tts")
                put("restart_on_end", false)
                putJsonObject("snd_format") {
                    put("rate", config.sampleRate)
                    put("width", config.audioWidth)
                    put("channels", config.audioChannels)
                }
            }
        )

        sendExecutor.execute {
            if (precedeWithWakeDetection) {
                val detectionPacket = WyomingPacket(
                    "detection",
                    buildJsonObject {
                        put("name", config.wakeWord)
                        put("timestamp", isoNow())
                        put("speaker", "")
                    },
                    sessionId = nextId
                )
                messenger.sendEvent(detectionPacket, pipelineStage, nextId)
            }
            messenger.sendEvent(runPipelinePacket.copy(sessionId = nextId), pipelineStage, nextId)
        }

        currentSession = PipelineSession(nextId)
        isExpectingTtsAudio = false
        setPipelineTimeout(15)
    }

    /** @param sessionId session to tag the stop with; pass from before clearing [currentSession] on reset. */
    fun sendAudioStop(sessionId: Int? = null) {
        sendEvent(
            "audio-stop",
            buildJsonObject { put("timestamp", isoNow()) },
            ByteArray(0),
            enqueueSessionId = sessionId ?: currentSession?.id
        )
    }

    fun sendAudio(audio: ByteArray) {
        if (pipelineStage != PipelineStage.LISTENING) return

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

    fun sendEvent(
        type: String,
        data: JsonObject = buildJsonObject {},
        payload: ByteArray = ByteArray(0),
        enqueueSessionId: Int? = currentSession?.id
    ) {
        if (!isRunning.get() || client.isClosed) return

        sendExecutor.execute {
            runCatching {
                val event = WyomingPacket(type, data, payload, sessionId = enqueueSessionId)
                messenger.sendEvent(event, this.pipelineStage, currentSession?.id)
            }.onFailure {
                if (isRunning.get()) {
                    log.e("Failed to send event $type: ${it.message}")
                }
            }
        }
    }

    private fun readEvent(): WyomingPacket? = messenger.readEvent()

    // endregion

    companion object {
        private val IGNORED_LOG_EVENTS = setOf("ping", "pong", "audio-chunk")
        private fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        /**
         * Robustly searches for a 'continue_conversation' boolean in a JSON object,
         * including nested intent_output structures.
         */
        private fun findContinueConversation(data: JsonObject): Boolean? {
            // Extract from HA's intent-output event: intent_output.continue_conversation
            data["intent_output"]?.jsonObject?.let { output ->
                output["continue_conversation"]?.jsonPrimitive?.booleanOrNull?.let { return it }
            }
            
            return null
        }
    }
}
