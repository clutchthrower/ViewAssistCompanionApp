package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.utils.DeviceCapabilitiesData
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingClient
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Interface for notification callbacks from the satellite server.
 */
interface SatelliteCallback {
    fun onSatelliteStarted()
    fun onSatelliteStopped()
    fun onRequestInputAudioStream()
    fun onReleaseInputAudioStream()
}

/**
 * TCP Server that listens for Wyoming protocol connections and spawns SatelliteClientHandlers.
 */
class SatelliteServer(
    val context: Context, 
    val port: Int, 
    private val callback: SatelliteCallback
) {
    private val log = Logger()
    private val isRunning = AtomicBoolean(true)
    private var serverSocket: ServerSocket? = null
    
    @Volatile var pipelineClient: WyomingClient? = null
    private val deviceInfo: DeviceCapabilitiesData = DeviceCapabilitiesManager(context).getDeviceInfo()

    fun getDeviceInfo() = deviceInfo

    fun start() {
        try {
            serverSocket = ServerSocket(port).also { socket ->
                log.d("Satellite Server listening on port ${socket.localPort}")
                
                while (isRunning.get()) {
                    val clientSocket = socket.accept()
                    if (isRunning.get()) {
                        thread(name = "SatelliteClient-${clientSocket.port}") {
                            SatelliteClientHandler(
                                context,
                                this,
                                clientSocket
                            ).start()
                        }
                    } else {
                        clientSocket.close()
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning.get()) log.e("Satellite Server exception: $e")
        } finally {
            runCatching { serverSocket?.close() }
        }
    }

    fun stop() {
        log.d("Stopping Satellite Server")
        isRunning.set(false)
        pipelineClient?.stop()
        runCatching { serverSocket?.close() }
    }

    // region Delegates to active client

    fun sendAudio(audio: ByteArray) = pipelineClient?.sendAudio(audio)
    fun sendStatus(data: kotlinx.serialization.json.JsonObject) = pipelineClient?.sendStatus(data)
    fun sendSetting(name: String, value: Any) = pipelineClient?.sendSetting(name, value)

    // endregion

    // region Callbacks from active client

    fun onSatelliteStarted() = callback.onSatelliteStarted()
    fun onSatelliteStopped() = callback.onSatelliteStopped()
    fun requestInputAudioStream() = callback.onRequestInputAudioStream()
    fun releaseInputAudioStream() = callback.onReleaseInputAudioStream()

    // endregion
}
