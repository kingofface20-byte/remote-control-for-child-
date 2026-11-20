package com.example.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var serviceState: TextView
    private lateinit var lastStatus: TextView
    private lateinit var checklist: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshChecklist()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        serviceState = findViewById(R.id.serviceState)
        lastStatus = findViewById(R.id.lastStatus)
        checklist = findViewById(R.id.checklist)

        findViewById<TextView>(R.id.deviceIdText).text = prefs.deviceId

        findViewById<Button>(R.id.startButton).setOnClickListener { startService() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopService() }
        findViewById<Button>(R.id.refreshWatchButton).setOnClickListener { refreshWatchNow() }
        findViewById<Button>(R.id.oemButton).setOnClickListener {
            openSettings(PermissionHelper.autostartIntent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChecklist()
        refreshStatus()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refreshWatchNow() {
        MonitorAccessibilityService.instance?.triggerRefreshCheck()
        if (prefs.running) {
            val refreshIntent = Intent(this, PollService::class.java).apply {
                action = PollService.ACTION_REFRESH_NOW
            }
            ContextCompat.startForegroundService(this, refreshIntent)
        }
        refreshChecklist()
        refreshStatus()
        Toast.makeText(this, R.string.watch_refreshed, Toast.LENGTH_SHORT).show()
    }

    private fun startService() {
        if (!allRequiredPermissionsGranted()) {
            Toast.makeText(this, R.string.need_permissions, Toast.LENGTH_LONG).show()
            refreshChecklist()
            return
        }
        prefs.running = true
        ContextCompat.startForegroundService(this, Intent(this, PollService::class.java))
        MonitorAccessibilityService.instance?.triggerRefreshCheck()
        refreshStatus()
        startActivity(Intent(this, WebViewActivity::class.java))
    }

    private fun allRequiredPermissionsGranted(): Boolean {
        return PermissionHelper.hasOverlay(this) &&
            PermissionHelper.hasNotifications(this) &&
            PermissionHelper.ignoresBattery(this) &&
            PermissionHelper.isAccessibilityEnabled(this) &&
            PermissionHelper.isDeviceAdminActive(this)
    }

    private fun stopService() {
        prefs.running = false
        stopService(Intent(this, PollService::class.java))
        MonitorAccessibilityService.instance?.triggerRefreshCheck()
        refreshStatus()
    }

    private fun refreshStatus() {
        serviceState.setText(if (prefs.running) R.string.service_on else R.string.service_off)
        lastStatus.text = prefs.lastStatus
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(PermissionHelper.appDetailsIntent(this))
        }
    }

    private fun refreshChecklist() {
        checklist.removeAllViews()

        addRow(R.string.perm_overlay, PermissionHelper.hasOverlay(this)) {
            openSettings(PermissionHelper.overlayIntent(this))
        }

        addRow(R.string.perm_notif, PermissionHelper.hasNotifications(this)) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openSettings(PermissionHelper.notificationSettingsIntent(this))
            }
        }

        addRow(R.string.perm_battery, PermissionHelper.ignoresBattery(this)) {
            openSettings(PermissionHelper.batteryIntent(this))
        }

        addRow(R.string.perm_accessibility, PermissionHelper.isAccessibilityEnabled(this)) {
            openSettings(PermissionHelper.accessibilityIntent())
        }

        addRow(R.string.perm_device_admin, PermissionHelper.isDeviceAdminActive(this)) {
            openSettings(PermissionHelper.deviceAdminIntent(this))
        }

        addRow(R.string.perm_packages, true, null)
    }

    private fun addRow(labelRes: Int, granted: Boolean, onEnable: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val mark = if (granted) "\u2705 " else "\u274C "
        val label = TextView(this).apply {
            text = mark + getString(labelRes) + " - " +
                getString(if (granted) R.string.perm_ok else R.string.perm_missing)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        if (!granted && onEnable != null) {
            val button = Button(this).apply {
                setText(R.string.btn_enable)
                setOnClickListener { onEnable() }
            }
            row.addView(button)
        }
        row.visibility = View.VISIBLE
        checklist.addView(row)
    }
}
