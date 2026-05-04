package com.msp1974.vacompanion

/*
 * Copyright (c) 2022 Wallpanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.msp1974.vacompanion.service.VAForegroundService
import timber.log.Timber

class AppExceptionHandler(val context: Context) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, ex: Throwable) {
        Timber.e("AppExceptionHandler: $ex")
        ex.printStackTrace()

        restartApp()
    }

    private fun restartApp() {
        // Stop foreground service
        Intent(context, VAForegroundService::class.java).also {
            it.action = VAForegroundService.Actions.STOP.toString()
            context.startService(it)
        }

        val intent = Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0) // Close the current process
    }

    private fun oldRestartApp() {
        val intent = Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                or Intent.FLAG_ACTIVITY_CLEAR_TASK
                or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(context.applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        val mgr = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        mgr[AlarmManager.RTC, System.currentTimeMillis() + 5000] = pendingIntent
        Runtime.getRuntime().exit(0)  // Close current process
    }
}