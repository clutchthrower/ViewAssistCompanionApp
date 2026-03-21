package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.utils.DeviceCapabilitiesData
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingClient
import java.net.ServerSocket
import java.util.concurrent.Executors
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
    private val clientThreadPool = Executors.newFixedThreadPool(10)
    private val deviceInfo: DeviceCapabilitiesData = DeviceCapabilitiesManager(context).getDeviceInfo()
    
    // Audio Stream state (Atomic for thread safety between multiple client handlers)
    private val isAudioRequested = AtomicBoolean(false)

    fun getDeviceInfo() = deviceInfo

    fun start() {
        try {
            serverSocket = ServerSocket(port).also { socket ->
                log.d("Satellite Server listening on port ${socket.localPort}")
                
                while (isRunning.get()) {
                    val clientSocket = socket.accept()
                    if (isRunning.get()) {
                        clientThreadPool.execute {
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
        
        // Graceful shutdown: don't interrupt active handlers mid-flight immediately
        clientThreadPool.shutdown()
        runCatching {
            if (!clientThreadPool.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                log.w("Satellite client threads did not shut down in time, forcing...")
                clientThreadPool.shutdownNow()
            }
        }
        
        // Safety: release audio if anything was pending
        if (isAudioRequested.getAndSet(false)) {
            callback.onReleaseInputAudioStream()
        }
    }

    // region Delegates to active client

    fun sendAudio(audio: ByteArray) = pipelineClient?.sendAudio(audio)
    fun sendStatus(data: kotlinx.serialization.json.JsonObject) = pipelineClient?.sendStatus(data)
    fun sendSetting(name: String, value: Any) = pipelineClient?.sendSetting(name, value)

    // endregion

    // region Callbacks from active client

    fun onSatelliteStarted() = callback.onSatelliteStarted()
    fun onSatelliteStopped() = callback.onSatelliteStopped()
    fun requestInputAudioStream() {
        // Use AtomicBoolean to ensure we only call callback once per state transition
        // and check pipelineClient within the same synchronized or volatile context.
        // We only allow audio capture if a client is actually connected.
        if (pipelineClient == null) return
        if (!isAudioRequested.getAndSet(true)) {
            callback.onRequestInputAudioStream()
        }
    }

    fun releaseInputAudioStream() {
        if (isAudioRequested.getAndSet(false)) {
            callback.onReleaseInputAudioStream()
        }
    }

    // endregion
}
