package com.msp1974.vacompanion.satellite

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

/**
 * Handles mDNS/Zeroconf registration for the satellite service.
 */
class SatelliteZeroconf(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private val config = APPConfig.getInstance(context)
    private var isRegistered: Boolean = false
    var serviceName: String? = null

    fun registerService(port: Int) {
        if (!isRegistered) {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "vaca-${config.uuid}"
                serviceType = "_vaca._tcp."
                setPort(port)
            }

            try {
                nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
                    registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
                }
            } catch (e: Exception) {
                Timber.e("Error registering NSD service: $e")
            }
        }
    }

    fun unregisterService() {
        if (isRegistered && nsdManager != null) {
            runCatching { nsdManager!!.unregisterService(registrationListener) }
                .onFailure { Timber.e("Unregister failed: $it") }
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
            serviceName = nsdServiceInfo.serviceName
            isRegistered = true
            Timber.d("Registered NSD service: $serviceName")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            Timber.d("Unregistered NSD service")
            isRegistered = false
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.e("Failed to register NSD service: $errorCode")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Timber.e("Failed to unregister NSD service: $errorCode")
        }
    }
}
