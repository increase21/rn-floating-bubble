package com.rnfloatingbubble

import android.content.Context
import android.content.Intent

/** Brings the host app's existing task to the front, or launches it if none exists. */
object AppLauncher {

    /**
     * Returns false if the app has no launcher activity or the launch failed.
     *
     * Android 10+ silently blocks activity starts from the background unless an
     * exemption applies; holding the overlay permission is one (Android 15+ also
     * requires a visible overlay window, e.g. the bubble).
     */
    fun bringToForeground(context: Context): Boolean {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            ?: return false

        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
