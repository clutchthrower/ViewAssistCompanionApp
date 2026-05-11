package com.msp1974.vacompanion.service

import android.content.Context
import android.net.wifi.WifiManager
import com.msp1974.vacompanion.data.ConnectionStatusManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wyoming.ServerState
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

internal class BackgroundTaskController (private val context: Context, val config: APPConfig, val connectionStatusManager: ConnectionStatusManager) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var wifiLock: WifiManager.WifiLock? = null
    private var server: WyomingTCPServer? = null


    fun start() {
        // Wi-Fi lock
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "wallPanel:wifiLock")

        server = object: WyomingTCPServer(context, config) {
            override fun onEvent(event: String, data: JsonObject) {
                Timber.d("BackgroundTask - Event: $event - ${data.toString()}")
            }
            override fun onState(state: ServerState, restartIfStopped: Boolean) {
                Timber.d("BackgroundTask - State: $state")
                when (state) {
                    ServerState.RUNNING -> {
                        wifiLock?.acquire()
                    }
                    ServerState.STOPPED -> {
                        Timber.d("Wyoming server stopped. Restart: $restartIfStopped")
                        if (wifiLock != null && wifiLock!!.isHeld) {
                            wifiLock!!.release()
                        }
                        // Restart server
                        if (restartIfStopped) runServer()
                    }
                    ServerState.ERRORED -> {
                        restartOnError()
                    }
                    else -> {}
                }
            }
        }

        runServer()

        Timber.d("Background task initialisation completed")
    }

    fun restartOnError() {
        server?.let {
            it.stopServer()
            start()
        }
    }

    fun runServer() {
        // TODO: Implement a recovery process
        try {
            if (server != null && server?.state == ServerState.STOPPED) {
                scope.launch { server?.startServer() }
            } else {
                Timber.d("Server not setup or already running")
            }
        } catch (e: Exception) {
            Timber.e("Error starting server: ${e.message.toString()}")
        }
    }

    fun serverWatchDog() {
        // TODO: Add restart watchdog
    }

    fun shutdown() {
        Timber.i("Shutting down")
        server?.stopServer()
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
    }
}
