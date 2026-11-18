package com.example.launcher

import android.os.Build
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

    // Returns the next command for this device, or null
    fun poll(): Command? {
        val id = URLEncoder.encode(deviceId, "UTF-8")
        val name = URLEncoder.encode(Build.MODEL, "UTF-8")
        val body = request("GET", "$baseUrl/api/poll?deviceId=$id&name=$name", null)
        val cmd = JSONObject(body).optJSONObject("command") ?: return null
        return Command(cmd.getInt("id"), cmd.getString("package"))
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
            conn.readTimeout = 60000 // Render free plan can take a while to wake up
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
