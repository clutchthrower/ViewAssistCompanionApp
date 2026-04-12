package com.msp1974.vacompanion.wyoming

import android.content.Context
import com.google.android.gms.tasks.Tasks.await
import com.msp1974.vacompanion.satellite.Satellite
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.device.DeviceCapabilitiesData
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Helpers.Companion.getIpv4HostAddress
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.port
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject


interface IEvents {
    fun onEvent(event: String, data: JsonObject)
    fun onState(state: ServerState, restartIfStopped: Boolean = true)
}

data class Connection(
    val id: String,
    val handler: WyomingClientHandler,
    val job: Job
)

data class MessageQueueItem(
    val clientId: String,
    val message: WyomingPacket
)

abstract class WyomingTCPServer(private val context: Context, val config: APPConfig): IEvents {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private var runServer: Boolean = true
    var satellite: Satellite? = null
    private val clients = mutableMapOf<String, Connection>()
    private lateinit var zeroconf: Zeroconf
    private var serverSocket: ServerSocket? = null
    private var restartIfStopped: Boolean = false

    private var deviceInfo: DeviceCapabilitiesData = DeviceCapabilitiesManager(context, config).getDeviceInfo()
    private val infoBuilder: WyomingInfoBuilder = WyomingInfoBuilder(context, config, deviceInfo)

    var state: ServerState = ServerState.STOPPED
        set(value) {
            field = value
            onState(value, restartIfStopped)
        }

    suspend fun startServer() {
        val exec = Executors.newCachedThreadPool()
        val selector = ActorSelectorManager(exec.asCoroutineDispatcher())

        state = ServerState.STARTING
        Timber.d("Wyoming TCP Server starting")


        try {
            serverSocket =
                aSocket(selector).tcp().bind("0.0.0.0", config.serverPort)
            Timber.d("Wyoming TCP Server started and listening at ${serverSocket?.localAddress}")
        } catch (e: Throwable) {
            Timber.e("Server Error: ${e.toString()}")
            return
        }


        Timber.d("Starting zeroconf")
        zeroconf = Zeroconf(context, config.uuid)
        zeroconf.registerService(config.serverPort)

        withContext(Dispatchers.IO) {
            try {
                state = ServerState.RUNNING
                restartIfStopped = true
                while (runServer) {
                    val socket = serverSocket?.accept()

                    val data = buildJsonObject {
                        put("remoteId", socket?.remoteAddress.toString())
                    }

                    val client: WyomingClientHandler = object : WyomingClientHandler(scope, socket!!) {
                        override suspend fun onClientDisconnected(clientId: String) {

                            if (clientId in clients) {
                                val client = clients[clientId]
                                client?.job?.cancel()
                                clients.remove(clientId)
                            }
                            Timber.d("Client disconnected: $clientId.  Total: ${clients.size}")


                            if (clients.isEmpty()) {
                                Timber.d("No clients connected")
                                scope.launch {
                                    // Stop satellite if connection lost for more than 15s
                                    delay(15000)
                                    if (clients.isEmpty()) {
                                        stopSatellite()
                                    }
                                }
                            }
                        }

                        override suspend fun onWyomingMessage(
                            clientId: String,
                            message: WyomingPacket
                        ) {
                            messageHandler(clientId, message)
                        }
                    }

                    val job = launch {
                        client.run()
                    }
                    val id = socket.remoteAddress.port().toString()
                    clients[id] = Connection(id, client, job)
                    Timber.d("Client connected: ${socket.remoteAddress}.  Total: ${clients.size}")
                    onEvent("client_connected", data)

                }
            } catch (e: Throwable) {
                ensureActive()
                Timber.e("Server Error: ${e.toString()}")
            } finally {
                state = ServerState.STOPPING
                serverSocket?.close()
                zeroconf.unregisterService()
                state = ServerState.STOPPED
                Timber.i("Wyoming TCP Server stopped")
            }
        }
    }

    fun stopServer() {
        restartIfStopped = false
        runServer = false
    }

    private suspend fun messageHandler(clientId: String, packet: WyomingPacket) {
        if (packet.type !in IGNORED_LOG_EVENTS) {
            Timber.d("Received <- ${clientId}: ${packet.toMap()}")
        }

        try {
            when (packet.type) {
                "ping" -> sendPong(clientId)
                "pong" -> {}
                "describe" -> sendInfo(clientId)
                "capabilities" -> sendCapabilities(clientId)
                "run-satellite" -> {
                    startSatellite(clientId)
                }
                "pause-satellite" -> stopSatellite()
                else -> {
                    var retryCount = 2
                    var processed = false
                    while (retryCount > 0) {
                        if (satellite != null && clientId == satellite?.clientId && satellite?.state != SatelliteState.STOPPED) {
                            satellite?.processMessage(packet)
                            processed = true
                            break
                        } else {
                            retryCount--
                            delay(500)
                        }
                    }
                    if (!processed) {
                        Timber.w("Cannot process message ${packet.toMap()}")
                        when {
                            satellite == null -> Timber.w("Satellite is null")
                            clientId != satellite?.clientId -> Timber.w("Client id does not match satellite id")
                            satellite?.state == SatelliteState.STOPPED -> Timber.w("Satellite is stopped")
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Timber.e("Error processing event ${packet.type}: $ex")
        }
    }

    suspend fun sendPong(clientId: String) {
        respondToGenericMessage(clientId, "pong", buildJsonObject { put("text", "") })
    }

    suspend fun sendInfo(clientId: String) {
        respondToGenericMessage(clientId, "info", infoBuilder.buildInfo())
    }

    suspend fun sendCapabilities(clientId: String) {
        respondToGenericMessage(clientId, "capabilities", DeviceCapabilitiesManager.toJson(deviceInfo))
    }

    private suspend fun startSatellite(clientId: String) {
        Timber.d("Processing run satellite")
        if (satellite != null) {
            Timber.d("Satellite not null")
            if (satellite?.state == SatelliteState.RUNNING) {
                Timber.d("Satellite already running - updating clientId")
                satellite?.clientId = clientId
                return
            } else {
                stopSatellite()
            }
        }
        try {
            Timber.d("Starting new satellite")
            satellite = object: Satellite(context, config, scope, clientId, deviceInfo) {
                override fun onEvent(event: String, data: JsonObject) {
                    Timber.d("Satellite event: $event")
                }

                override fun sendSatelliteMessage(
                    clientId: String,
                    type: String,
                    data: JsonObject,
                    payload: ByteArray
                ) {
                    sendMessage(clientId, type, data, payload)
                }
            }.also {
                scope.launch {
                    satellite?.start()
                }
            }
        } catch (e: Exception) {
            Timber.e("Error starting satellite: $e")
        }
    }

    private suspend fun stopSatellite() {
        if (satellite != null) {
            val satId = satellite?.clientId
            satellite?.stop()
            satellite = null
            withContext(Dispatchers.IO) {
                clients[satId]?.handler?.socket?.close()
                clients.remove(satId)
            }
        }
    }

    private suspend fun respondToGenericMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        val packet = WyomingPacket(type, data, payload)
        if (type !in IGNORED_LOG_EVENTS) {
            Timber.d("Sending -> $clientId: ${packet.toMap()}")
        }
        clients[clientId]?.handler?.writeMessage(packet)
    }

    fun sendMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        if (satellite != null && clientId == satellite?.clientId) {
            scope.launch {
                val packet = WyomingPacket(type, data, payload)
                if (type !in IGNORED_LOG_EVENTS) {
                    Timber.d("Sending -> $clientId: ${packet.toMap()}")
                }
                clients[clientId]?.handler?.writeMessage(packet)
            }
        }
    }

    companion object {
        private val IGNORED_LOG_EVENTS = setOf("ping", "pong", "audio-chunk")
    }
}
