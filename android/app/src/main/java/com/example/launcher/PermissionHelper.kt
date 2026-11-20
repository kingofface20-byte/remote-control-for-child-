package com.example.launcher

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
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

    fun isAccessibilityEnabled(c: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            c.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val fullName = "${c.packageName}/${MonitorAccessibilityService::class.java.name}"
        val shortName = "${c.packageName}/.MonitorAccessibilityService"
        return enabledServices.contains(fullName) || enabledServices.contains(shortName)
    }

    fun isDeviceAdminActive(c: Context): Boolean {
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminName = ComponentName(c, ScreenAdminReceiver::class.java)
        return dpm.isAdminActive(adminName)
    }

    fun accessibilityIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun deviceAdminIntent(c: Context): Intent {
        val adminName = ComponentName(c, ScreenAdminReceiver::class.java)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminName)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, c.getString(R.string.admin_explain))
        }
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
