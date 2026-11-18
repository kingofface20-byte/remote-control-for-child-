package com.example.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object AppLauncher {
    // Returns: launched | not_installed | cannot_launch | error
    fun launch(context: Context, pkg: String): String {
        val pm = context.packageManager
        try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return "not_installed"
        }

        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "cannot_launch"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "launched"
        } catch (e: Exception) {
            "error"
        }
    }
}
