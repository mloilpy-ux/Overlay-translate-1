package com.example

import android.content.Context
import android.os.Build
import android.provider.Settings

object PermissionUtils {
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
}
