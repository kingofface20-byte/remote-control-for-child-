package com.example.launcher

import android.content.Context
import android.os.Build
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit().putString("url", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    // true while the user wants the service running (used by BootReceiver)
    var running: Boolean
        get() = sp.getBoolean("running", false)
        set(v) = sp.edit().putBoolean("running", v).apply()

    var lastStatus: String
        get() = sp.getString("last_status", "-") ?: "-"
        set(v) = sp.edit().putString("last_status", v).apply()

    // Generated once, then kept
    val deviceId: String
        get() {
            val existing = sp.getString("device_id", null)
            if (existing != null) return existing
            val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "").take(20).ifEmpty { "device" }
            val id = model + "-" + UUID.randomUUID().toString().take(4)
            sp.edit().putString("device_id", id).apply()
            return id
        }
}
