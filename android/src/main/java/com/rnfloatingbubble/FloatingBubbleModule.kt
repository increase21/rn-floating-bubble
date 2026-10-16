package com.rnfloatingbubble

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap

/**
 * React Native module that exposes the floating bubble API to JavaScript.
 *
 * JS API:
 *   FloatingBubble.show(options)         → Promise<void>
 *   FloatingBubble.hide()                → Promise<void>
 *   FloatingBubble.isShowing()           → Promise<boolean>
 *   FloatingBubble.hasPermission()       → Promise<boolean>
 *   FloatingBubble.requestPermission()   → Promise<boolean>
 */
class FloatingBubbleModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext), LifecycleEventListener {

    companion object {
        const val NAME = "FloatingBubble"
    }

    // Held across onHostPause / onHostResume for the permission request flow
    private var pendingPermissionPromise: Promise? = null

    init {
        reactContext.addLifecycleEventListener(this)
    }

    override fun getName(): String = NAME

    // -------------------------------------------------------------------------
    // JS-exposed methods
    // -------------------------------------------------------------------------

    @ReactMethod
    fun show(options: ReadableMap, promise: Promise) {
        if (!hasOverlayPermission()) {
            promise.reject(
                "PERMISSION_DENIED",
                "SYSTEM_ALERT_WINDOW permission is not granted. " +
                "Call requestPermission() first."
            )
            return
        }

        val sizeDp          = if (options.hasKey("size"))             options.getInt("size")              else 60
        // ARGB values like 0xFF4285F4 exceed Int.MAX_VALUE in JS; getInt() would clamp them
        val color           = if (options.hasKey("color"))            options.getDouble("color").toLong().toInt() else 0xFF4285F4.toInt()
        val icon            = if (options.hasKey("icon"))             options.getString("icon")           else null
        // useLauncherIcon defaults to true when no explicit icon is provided
        val useLauncherIcon = if (options.hasKey("useLauncherIcon"))  options.getBoolean("useLauncherIcon") else (icon == null)
        val initX           = if (options.hasKey("initialX"))         options.getInt("initialX")          else 0
        val initY           = if (options.hasKey("initialY"))         options.getInt("initialY")          else 100
        val enableDismiss   = if (options.hasKey("enableDismiss"))    options.getBoolean("enableDismiss") else false

        val intent = Intent(reactContext, FloatingBubbleService::class.java).apply {
            putExtra(FloatingBubbleService.EXTRA_SIZE,              sizeDp)
            putExtra(FloatingBubbleService.EXTRA_COLOR,             color)
            putExtra(FloatingBubbleService.EXTRA_USE_LAUNCHER_ICON, useLauncherIcon)
            putExtra(FloatingBubbleService.EXTRA_INITIAL_X,         initX)
            putExtra(FloatingBubbleService.EXTRA_INITIAL_Y,         initY)
            putExtra(FloatingBubbleService.EXTRA_ENABLE_DISMISS,    enableDismiss)
            icon?.let { putExtra(FloatingBubbleService.EXTRA_ICON, it) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            reactContext.startForegroundService(intent)
        } else {
            reactContext.startService(intent)
        }

        promise.resolve(null)
    }

    @ReactMethod
    fun hide(promise: Promise) {
        reactContext.stopService(
            Intent(reactContext, FloatingBubbleService::class.java)
        )
        promise.resolve(null)
    }

    @ReactMethod
    fun isShowing(promise: Promise) {
        promise.resolve(isBubbleServiceRunning())
    }

    @ReactMethod
    fun hasPermission(promise: Promise) {
        promise.resolve(hasOverlayPermission())
    }

    /**
     * Opens the system "Display over other apps" settings page.
     *
     * The promise resolves (true/false) when the user returns to the app.
     * On devices below API 23 the permission is implicitly granted, so this
     * resolves immediately with `true`.
     */
    @ReactMethod
    fun requestPermission(promise: Promise) {
        if (hasOverlayPermission()) {
            promise.resolve(true)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingPermissionPromise = promise
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${reactContext.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactContext.currentActivity?.startActivity(intent)
                ?: run {
                    pendingPermissionPromise = null
                    promise.reject("NO_ACTIVITY", "No current activity found.")
                }
        } else {
            // Implicitly granted on older Android versions
            promise.resolve(true)
        }
    }

    // -------------------------------------------------------------------------
    // LifecycleEventListener — resolve permission promise on app resume
    // -------------------------------------------------------------------------

    override fun onHostResume() {
        pendingPermissionPromise?.let { promise ->
            promise.resolve(hasOverlayPermission())
            pendingPermissionPromise = null
        }
    }

    override fun onHostPause() { /* no-op */ }
    override fun onHostDestroy() {
        pendingPermissionPromise?.reject("DESTROYED", "Activity destroyed.")
        pendingPermissionPromise = null
    }

    override fun invalidate() {
        reactContext.removeLifecycleEventListener(this)
        super.invalidate()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(reactContext)
        else
            true

    @Suppress("DEPRECATION")
    private fun isBubbleServiceRunning(): Boolean {
        val manager = reactContext.getSystemService(Context.ACTIVITY_SERVICE)
                as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == FloatingBubbleService::class.java.name
        }
    }
}
