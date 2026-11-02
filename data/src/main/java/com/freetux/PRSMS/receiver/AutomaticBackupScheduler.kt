/*
 * Copyright (C) 2026 Afshin Irani
 *
 * This file is part of PRSMS and is licensed under the GNU General Public License version 3.
 */
package com.freetux.PRSMS.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AutomaticBackupScheduler {

    private const val REQUEST_CODE = 61026

    fun schedule(context: Context, enabled: Boolean) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context)

        if (!enabled) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            return
        }

        val now = System.currentTimeMillis()
        val firstRun = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        alarmManager.setInexactRepeating(
                AlarmManager.RTC,
                firstRun,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
        )
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AutomaticBackupReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

}
