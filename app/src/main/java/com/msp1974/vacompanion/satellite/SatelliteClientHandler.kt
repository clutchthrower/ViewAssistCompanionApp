package com.msp1974.vacompanion.satellite

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
import com.msp1974.vacompanion.wyoming.PipelineStage
import com.msp1974.vacompanion.wyoming.WyomingClient
import com.msp1974.vacompanion.wyoming.WyomingMessenger
import com.msp1974.vacompanion.wyoming.WyomingPacket
import io.github.z4kn4fein.semver.toVersion
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concrete implementation of a Wyoming client using TCP transport.
 * Orchestrates the connection, handles Wyoming protocol messaging, 
 * and delegates voice interaction logic to the SessionCoordinator.
 */
class SatelliteClientHandler(
    private val context: Context,
    private val server: SatelliteServer,
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
    private val mediaHandler: SatelliteMediaHandler = SatelliteMediaHandler(context),
    private val actionHandler: SatelliteActionHandler = SatelliteActionHandler(context, config, mediaHandler, log),
    private val infoBuilder: SatelliteInfoBuilder = SatelliteInfoBuilder(context, config, server.getDeviceInfo()),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SatelliteClient-${client.port}")
    },
    private val broadcastExecutor: ExecutorService = Executors.newSingleThreadExecutor()
) : WyomingClient {

    private val connectionId = client.inetAddress.hostAddress ?: "unknown"
    override val clientId: Int = client.port
    
    // State Management (Executors provided via constructor)

    // State Management
    private val isRunning = AtomicBoolean(true)
    @Volatile private var satelliteState = SatelliteState.STOPPED
    private val missedPongs = AtomicInteger(0)
    
    private val sessionCoordinator: SessionCoordinator
    
    // Health Tracking
    private companion object {
        const val PING_INTERVAL_MS = 2000L
        const val MAX_MISSED_PONGS = 3
    }

    init {
        sessionCoordinator = SessionCoordinator(log, object : VoiceSession.Callback {
            override fun sendEvent(packet: WyomingPacket) = this@SatelliteClientHandler.sendRawEvent(packet)

            override fun onRequestAudioStream() {
                log.d("Requesting audio input stream for $clientId")
                server.requestInputAudioStream()
            }

            override fun onReleaseAudioStream() {
                log.d("Releasing audio input stream for $clientId")
                server.releaseInputAudioStream()
            }

            override fun onStartMediaPlayback() = mediaHandler.pcmMediaPlayer.play()

            override fun onWriteMediaChunk(payload: ByteArray) {
                if (mediaHandler.pcmMediaPlayer.isPlaying) {
                    mediaHandler.pcmMediaPlayer.writeAudio(payload)
                }
            }

            override fun onStopMediaPlayback() = mediaHandler.pcmMediaPlayer.stop()

            override fun onUpdateVolumeDucking(key: String, duck: Boolean) = mediaHandler.updateVolumeDucking(key, duck)

            override fun notifyContinueConversation(phrase: String) {
                config.eventBroadcaster.notifyEvent(Event("continueConversationStart", "", ""))
                sessionCoordinator.startContinueConversation()
            }

            override fun notifyRecognitionError(code: String, text: String) {
                config.eventBroadcaster.notifyEvent(Event("recognitionError", text, code))
            }

            override fun setPipelineTimeout(seconds: Int) {
                cancelPipelineTimeout()
                mainHandler.postDelayed(pipelineTimeoutRunnable, seconds * 1000L)
            }

            override fun cancelPipelineTimeout() {
                mainHandler.removeCallbacks(pipelineTimeoutRunnable)
            }

            override fun initiatePipeline(session: VoiceSession, isContinue: Boolean) {
                initiateInteraction(session, precedeWithWakeDetection = !isContinue)
            }
            
            override fun onSessionFinalized(session: VoiceSession) {
                this@SatelliteClientHandler.sessionCoordinator.onSessionFinalized(session)
                if (session.needsContinue) {
                    notifyContinueConversation()
                }
            }
        })
    }

    val pipelineStage: PipelineStage
        get() = sessionCoordinator.activeSession?.stage ?: PipelineStage.IDLE

    private var pingTimer: Timer? = null
    private val pipelineTimeoutRunnable = Runnable { 
        log.d("Pipeline timed out (Session ${sessionCoordinator.activeSession?.id})")
        sessionCoordinator.reset() 
    }

    // Broadcasts
    private val intentFilter = IntentFilter().apply {
        addAction(BroadcastSender.WAKE_WORD_DETECTED)
        addAction(BroadcastSender.STOP_WORD_DETECTED)
    }

    private val wakeWordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (satelliteState != SatelliteState.RUNNING) return
            broadcastExecutor.execute { handleBroadcastIntent(intent) }
        }
    }

    // region Lifecycle

    override fun start() {
        val totalConnections = config.atomicConnectionCount.incrementAndGet()
        log.d("Client $clientId connected from $connectionId. Total connections: $totalConnections")
        startPingTimer()
        
        try {
            while (isRunning.get() && !client.isClosed) {
                val packet = messenger.readEvent() ?: continue
                missedPongs.set(0) // Connection is alive
                processPacket(packet)
            }
        } catch (_: EOFException) {
            log.d("Connection $clientId closed by peer.")
        } catch (ex: Exception) {
            if (isRunning.get()) log.e("Connection $clientId terminated due to exception: $ex")
        } finally {
            stop()
        }
    }

    override fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        
        log.d("Stopping client $clientId connection handler")
        stopPingTimer()

        if (satelliteState == SatelliteState.RUNNING) {
            stopSatelliteInternal()
        }
        
        mediaHandler.release()
        sendExecutor.shutdown()
        runCatching { sendExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
        
        broadcastExecutor.shutdown()
        runCatching { broadcastExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }

        runCatching { client.close() }
        
        val remaining = config.atomicConnectionCount.decrementAndGet()
        log.w("$connectionId:$clientId disconnected. Remaining connections: $remaining")
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
            if (config.pairedDeviceID.isEmpty()) config.pairedDeviceID = connectionId

            if (config.pairedDeviceID == connectionId) {
                log.d("Starting satellite service for $clientId")
                LocalBroadcastManager.getInstance(context).registerReceiver(wakeWordReceiver, intentFilter)
                config.homeAssistantConnectedIP = connectionId

                handleAlarmAction(false)
                mediaHandler.musicPlayer.stop()
                sessionCoordinator.reset()

                val oldClient = server.pipelineClient as? SatelliteClientHandler
                if (oldClient != null && oldClient != this) {
                    log.d("Satellite session takeover by $clientId from ${oldClient.clientId}")
                    oldClient.stop()
                }
                
                server.pipelineClient = this
                satelliteState = SatelliteState.RUNNING
                server.onSatelliteStarted()
                log.d("Satellite session started for $clientId")
            } else {
                log.i("Unauthorized connection attempt from $connectionId:$clientId. Aborting.")
                stop()
            }
        }
        config.isRunning = satelliteState == SatelliteState.RUNNING
    }

    private fun stopSatelliteInternal() {
        log.d("Stopping satellite service for $clientId")
        LocalBroadcastManager.getInstance(context).unregisterReceiver(wakeWordReceiver)
        
        synchronized(server) {
            if (server.pipelineClient == this) {
                if (pipelineStage == PipelineStage.LISTENING) server.releaseInputAudioStream()
                handleAlarmAction(false)
                mediaHandler.musicPlayer.stop()
                server.pipelineClient = null
                server.onSatelliteStopped()
                config.homeAssistantConnectedIP = ""
            }
            sessionCoordinator.reset()
            satelliteState = SatelliteState.STOPPED
        }
        config.isRunning = false
    }

    // endregion

    // region Event Processing

    override fun processPacket(packet: WyomingPacket) {
        if (packet.type !in listOf("ping", "pong", "audio-chunk")) {
            log.d("Event received - $clientId: ${packet.toMap()}")
        }

        try {
            when (packet.type) {
                "ping" -> sendPong()
                "pong" -> { /* Already handled by read loop reset */ }
                "describe" -> sendInfo()
                "capabilities" -> sendCapabilities()
                "run-satellite" -> startSatellite()
                "pause-satellite" -> if (satelliteState == SatelliteState.RUNNING) stopSatelliteInternal()
                "settings", "custom-settings" -> processSettingsPacket(packet)
                "action", "custom-action" -> handleCustomAction(packet)
                "custom-event" -> handleCustomEvent(packet)
            }

            if (satelliteState == SatelliteState.RUNNING) {
                if (packet.type == "timer-finished") handleAlarmAction(true)
                sessionCoordinator.processPacket(packet)
            }
            
        } catch (ex: Exception) {
            log.e("Error processing event ${packet.type}: $ex")
        }
    }

    override fun onWakeWordDetected() {
        sessionCoordinator.onWakeWordDetected()
    }

    private fun handleBroadcastIntent(intent: Intent) {
        if (mediaHandler.pcmMediaPlayer.isPlaying) {
            mediaHandler.pcmMediaPlayer.stop()
            mediaHandler.updateVolumeDucking("music", false)
        }
        handleAlarmAction(false)

        when (intent.action) {
            BroadcastSender.WAKE_WORD_DETECTED -> onWakeWordDetected()
            BroadcastSender.STOP_WORD_DETECTED -> {
                log.d("Stop word detected - resetting coordinator")
                sessionCoordinator.reset()
            }
        }
    }

    private fun processSettingsPacket(packet: WyomingPacket) {
        val settingsStr = packet.getProp("settings").ifEmpty { packet.data.toString() }
        config.processSettings(settingsStr)
    }

    private fun handleCustomEvent(event: WyomingPacket) {
        val eventData = event.data["data"]?.jsonObject ?: event.data
        val eventType = event.getProp("event_type")

        // Sync forceContinue if found in custom event
        eventData["intent_output"]?.jsonObject?.let { output ->
            output["continue_conversation"]?.jsonPrimitive?.booleanOrNull?.let { value ->
                sessionCoordinator.activeSession?.let { session ->
                    sessionCoordinator.setForceContinue(session.id, value)
                }
            }
        }

        val innerPacket = WyomingPacket(eventType, eventData)
        when (eventType) {
            "action" -> handleCustomAction(innerPacket)
            "settings" -> processSettingsPacket(innerPacket)
            "capabilities" -> sendCapabilities()
            else -> {
                actionHandler.handleAction(eventType, eventData.toString()) { enable, url ->
                    handleAlarmAction(enable, url)
                }
            }
        }
    }

    private fun handleCustomAction(event: WyomingPacket) {
        actionHandler.handleAction(event.getProp("action"), event.getProp("payload")) { enable, url ->
            handleAlarmAction(enable, url)
        }
    }

    private fun handleAlarmAction(enable: Boolean, url: String = "") {
        if (enable) {
            mediaHandler.updateVolumeDucking("music", true)
            mediaHandler.alarmPlayer.startAlarm(url)
            config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
        } else {
            mediaHandler.alarmPlayer.stopAlarm()
            mediaHandler.updateVolumeDucking("music", false)
        }
        sendSettingChange("alarm", enable)
    }

    // endregion

    // region Outbound

    private fun startPingTimer() {
        stopPingTimer()
        pingTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (missedPongs.get() >= MAX_MISSED_PONGS) {
                        log.w("Client $clientId: No response for ${MAX_MISSED_PONGS * PING_INTERVAL_MS / 1000}s. Terminating connection.")
                        stop()
                        return
                    }
                    missedPongs.incrementAndGet()
                    sendRawEvent(WyomingPacket("ping", buildJsonObject { put("text", "") }))
                }
            }, 0, PING_INTERVAL_MS)
        }
    }

    private fun stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = null
    }

    fun sendPong() = sendRawEvent(WyomingPacket("pong", buildJsonObject { put("text", "") }))

    fun sendInfo() = sendRawEvent(WyomingPacket("info", infoBuilder.buildInfo()))

    fun initiateInteraction(session: VoiceSession? = null, precedeWithWakeDetection: Boolean = true) {
        val targetSession = session ?: sessionCoordinator.activeSession ?: return
        
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
            },
            sessionId = targetSession.id
        )

        sendExecutor.execute {
            if (precedeWithWakeDetection) {
                val detectionPacket = WyomingPacket(
                    "detection",
                    buildJsonObject {
                        put("name", config.wakeWord)
                        put("timestamp", WyomingPacket.isoNow())
                        put("speaker", "")
                    },
                    sessionId = targetSession.id
                )
                sendRawEvent(detectionPacket)
            }
            sendRawEvent(runPipelinePacket)
        }
    }

    override fun sendAudio(audio: ByteArray) {
        val session = sessionCoordinator.activeSession ?: return
        if (session.stage != PipelineStage.LISTENING) return

        val data = buildJsonObject {
            put("rate", config.sampleRate)
            put("width", config.audioWidth)
            put("channels", config.audioChannels)
        }
        sendRawEvent(WyomingPacket("audio-chunk", data, audio, sessionId = session.id))
    }

    override fun sendStatus(data: JsonObject) = sendCustomEvent("status", data)

    override fun sendSetting(name: String, value: Any) = sendSettingChange(name, value)

    fun sendCapabilities() {
        val data = DeviceCapabilitiesManager.toJson(server.getDeviceInfo())
        sendCustomEvent("capabilities", data)
    }

    fun sendSettingChange(name: String, value: Any) {
        sendCustomEvent("settings", buildJsonObject {
            put("timestamp", WyomingPacket.isoNow())
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

    fun sendCustomEvent(type: String, data: JsonObject) {
        sendRawEvent(WyomingPacket("custom-event", buildJsonObject {
            put("event_type", type)
            put("data", data)
        }))
    }

    fun sendRawEvent(packet: WyomingPacket) {
        if (!isRunning.get() || client.isClosed) return

        sendExecutor.execute {
            // --- Transport Filtering ---
            val currentSessionId = sessionCoordinator.activeSession?.id
            if (currentSessionId != null && 
                packet.sessionId != null && 
                packet.sessionId != currentSessionId
            ) {
                log.d("Dropping packet ${packet.type} for session ${packet.sessionId} (active session: $currentSessionId)")
                return@execute
            }
            
            if (packet.type == "audio-chunk" && pipelineStage != PipelineStage.LISTENING) {
                return@execute
            }
            // ---------------------------
            
            runCatching {
                messenger.sendEvent(packet)
            }.onFailure { if (isRunning.get()) log.e("Failed to send event ${packet.type}: ${it.message}") }
        }
    }

    // endregion
}
