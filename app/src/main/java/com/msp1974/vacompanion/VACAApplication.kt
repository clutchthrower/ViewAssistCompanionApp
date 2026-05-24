package com.msp1974.vacompanion

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.msp1974.vacompanion.utils.ActivityManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


@HiltAndroidApp
class VACAApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(AppExceptionHandler(this.applicationContext))

        disableSSLCertificateChecking()

        activityManager = ActivityManager(this)
        Timber.plant(DebugTree())

        // NotificationChannel is required for Android 8.0+ (API 26+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "VACAForegroundServiceChannelId",
                "VACA Foreground Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

    }

    private fun disableSSLCertificateChecking() {
        val trustAllCerts: Array<TrustManager?> = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate?>? {
                return null
            }

            @SuppressLint("TrustAllX509TrustManager")
            @Throws(CertificateException::class)
            public override fun checkClientTrusted(arg0: Array<X509Certificate?>?, arg1: String?) {
                // Not implemented
            }

            @SuppressLint("TrustAllX509TrustManager")
            @Throws(CertificateException::class)
            public override fun checkServerTrusted(arg0: Array<X509Certificate?>?, arg1: String?) {
                // Not implemented
            }
        }) as Array<TrustManager?>

        try {
            val sc = SSLContext.getInstance("TLS")

            sc.init(null, trustAllCerts, SecureRandom())

            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        } catch (e: KeyManagementException) {
            e.printStackTrace()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var activityManager: ActivityManager
    }
}