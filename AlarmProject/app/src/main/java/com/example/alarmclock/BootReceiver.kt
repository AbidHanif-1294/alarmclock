package com.example.alarmlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val armed = context.getSharedPreferences("alarm_prefs", 0)
                .getBoolean("armed", false)
            if (armed) {
                ContextCompat.startForegroundService(
                    context, Intent(context, AlarmService::class.java)
                )
            }
        }
    }
}