package com.msp1974.vacompanion.data

import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject

enum class NetworkStatus {
    Available,
    Unavailable
}

class NetworkInfo() {
    var status: NetworkStatus = NetworkStatus.Unavailable
        set(value) {
            field = value
            lastChanged = System.currentTimeMillis()
            if (value == NetworkStatus.Unavailable) disconnectCount++
        }
    var lastChanged: Long = 0
    var disconnectCount: Long = 0
}

class NetworkStatusManager @Inject constructor(val context: Context) {

    val networkInfo = NetworkInfo()

    @get:RequiresPermission("android.permission.ACCESS_NETWORK_STATE")
    val networkStatus: Flow<NetworkInfo> get() = getNetworkStatus(context)

    @RequiresPermission("android.permission.ACCESS_NETWORK_STATE")
    private fun getNetworkStatus(context: Context): Flow<NetworkInfo> = callbackFlow {
        val connectivityManager = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onUnavailable() {
                networkInfo.status = NetworkStatus.Unavailable
                trySend(networkInfo)
            }

            override fun onAvailable(network: Network) {
                networkInfo.status = NetworkStatus.Available
                trySend(networkInfo)
            }

            override fun onLost(network: Network) {
                networkInfo.status = NetworkStatus.Unavailable
                trySend(networkInfo)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

}