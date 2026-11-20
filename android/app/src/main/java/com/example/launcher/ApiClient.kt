package com.example.launcher

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiClient(
    private val baseUrl: String,
    private val token: String,
    private val deviceId: String
) {
    class Command(val id: Int, val pkg: String)

    class PollResponse(
        val command: Command?,
        val autoClickEnabled: Boolean?,
        val autoShotEnabled: Boolean?,
        val targetPackage: String?,
        val steps: List<String>?,
        val triggerId: String?,
        val botToken: String?,
        val chatId: String?
    )

    fun poll(): PollResponse {
        val id = URLEncoder.encode(deviceId, "UTF-8")
        val name = URLEncoder.encode(Build.MODEL, "UTF-8")
        val body = request("GET", "$baseUrl/api/poll?deviceId=$id&name=$name", null)
        val root = JSONObject(body)

        val cmdObj = root.optJSONObject("command")
        val command = if (cmdObj != null && cmdObj.has("id") && cmdObj.has("package")) {
            Command(cmdObj.getInt("id"), cmdObj.getString("package"))
        } else {
            null
        }

        val autoClickEnabled = if (root.has("autoClickEnabled") && !root.isNull("autoClickEnabled")) {
            root.optBoolean("autoClickEnabled", false)
        } else {
            null
        }

        val autoShotEnabled = if (root.has("autoShotEnabled") && !root.isNull("autoShotEnabled")) {
            root.optBoolean("autoShotEnabled", false)
        } else {
            null
        }

        val targetPackage = root.optString("targetPackage", "").ifBlank { null }
        val triggerId = root.optString("triggerId", "").ifBlank { null }
        val botToken = root.optString("botToken", root.optString("bot_token", "")).ifBlank { null }
        val chatId = root.optString("chatId", root.optString("chat_id", "")).ifBlank { null }

        val stepsArray: JSONArray? = root.optJSONArray("steps")
        val steps: List<String>? = if (stepsArray != null) {
            val list = ArrayList<String>(Prefs.MAX_CLICK_STEPS)
            for (i in 0 until minOf(stepsArray.length(), Prefs.MAX_CLICK_STEPS)) {
                list.add(stepsArray.optString(i, "").trim())
            }
            while (list.size < Prefs.MAX_CLICK_STEPS) list.add("")
            list
        } else {
            null
        }

        return PollResponse(
            command = command,
            autoClickEnabled = autoClickEnabled,
            autoShotEnabled = autoShotEnabled,
            targetPackage = targetPackage,
            steps = steps,
            triggerId = triggerId,
            botToken = botToken,
            chatId = chatId
        )
    }

    fun sendResult(commandId: Int, result: String) {
        val json = JSONObject()
            .put("deviceId", deviceId)
            .put("commandId", commandId)
            .put("result", result)
        request("POST", "$baseUrl/api/result", json.toString())
    }

    private fun request(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
