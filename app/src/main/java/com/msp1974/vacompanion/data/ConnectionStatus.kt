package com.msp1974.vacompanion.data

import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

enum class ConnectionStatus {
    DISCONNECTED,
      CONNECTED,
}

class ConnectionInfo() {
    var status: ConnectionStatus = ConnectionStatus.DISCONNECTED
        set(value) {
            field = value
            lastChanged = System.currentTimeMillis()
            if (value == ConnectionStatus.DISCONNECTED) disconnectCount++
        }
    var lastChanged: Long = 0
    var disconnectCount: Long = 0
}

class ConnectionStatusManager @Inject constructor(val context: Context) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private val networkInfo = ConnectionInfo()

    private val _networkStatus = MutableSharedFlow<ConnectionInfo>()
    val networkStatus = _networkStatus.asSharedFlow()

    fun start() {
        registerNetworkMonitor()
    }

    fun stop() {
        unregisterNetworkMonitor()
    }

    fun emitNetworkStatus(status: ConnectionStatus) {
        networkInfo.status = status
        scope.launch {
            _networkStatus.emit(networkInfo)
        }
    }

    fun registerNetworkMonitor() {
        val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        Timber.d("Registering Wifi monitor")
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    val networkCallback = object: ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            emitNetworkStatus(ConnectionStatus.CONNECTED)
        }

        override fun onLost(network: Network) {
            emitNetworkStatus(ConnectionStatus.DISCONNECTED)
        }
    }

    fun unregisterNetworkMonitor() {
        val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
}