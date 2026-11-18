package com.example.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (Prefs(context).running) {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PollService::class.java)
                )
            }
        }
    }
}
