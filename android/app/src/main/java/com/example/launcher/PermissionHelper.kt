package com.example.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object PermissionHelper {

    fun hasOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun hasNotifications(c: Context): Boolean =
        NotificationManagerCompat.from(c).areNotificationsEnabled()

    fun ignoresBattery(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    fun overlayIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))

    @SuppressLint("BatteryLife")
    fun batteryIntent(c: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${c.packageName}")
        )

    fun appDetailsIntent(c: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))

    fun notificationSettingsIntent(c: Context): Intent =
        if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
        } else {
            appDetailsIntent(c)
        }

    // Xiaomi autostart screen; other brands fall back to app details
    fun autostartIntent(c: Context): Intent {
        val maker = Build.MANUFACTURER.lowercase()
        if (maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")) {
            val i = Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
            if (i.resolveActivity(c.packageManager) != null) return i
        }
        return appDetailsIntent(c)
    }
}
