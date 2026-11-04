package com.rnfloatingbubble

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil

/**
 * React Native module that exposes the floating bubble API to JavaScript.
 *
 * The library's manifest declares no permissions and no service: the host app
 * declares what each feature it uses needs, and every method that depends on
 * a declaration checks for it and rejects with PERMISSION_NOT_DECLARED /
 * SERVICE_NOT_DECLARED instead of crashing or silently doing nothing.
 *
 * JS API:
 *   Bubble       show(options), hide(), isShowing(), updateNotification(options)
 *   Overlay      hasPermission(), requestPermission()
 *   App / screen bringAppToForeground(), wakeScreen(durationMs),
 *                enableLockScreenUI(dismissKeyguard), disableLockScreenUI()
 *   Battery      isIgnoringBatteryOptimizations(), requestIgnoreBatteryOptimizations(),
 *                openBatteryOptimizationSettings(), openManufacturerBatterySettings()
 */
class FloatingBubbleModule(
    private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext), LifecycleEventListener {

    companion object {
        const val NAME = "FloatingBubble"

        /**
         * OEM battery / autostart screens, tried in order.  None are public
         * APIs, so any of them may be missing or renamed on a given OS version.
         */
        private val MANUFACTURER_BATTERY_SCREENS = listOf(
            // Xiaomi / Redmi / POCO
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // Huawei / Honor
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            // Oppo / Realme (ColorOS)
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            // Vivo / iQOO
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            // Samsung
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            // OnePlus
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            // Asus
            "com.asus.mobilemanager" to "com.asus.mobilemanager.MainActivity",
        )
    }

    /** A system screen we opened; [check] resolves [promise] when the user returns. */
    private class PendingRequest(val promise: Promise, val check: () -> Boolean)

    private var pendingRequest: PendingRequest? = null

    /** Bubble shown without the foreground service (`foregroundService: false`). */
    private val inAppBubble by lazy { BubbleController(reactContext.applicationContext) }

    /** Permissions in the host app's merged manifest; fixed for the app's lifetime. */
    private val declaredPermissions: Set<String> by lazy {
        @Suppress("DEPRECATION")
        reactContext.packageManager
            .getPackageInfo(reactContext.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            ?: emptySet()
    }

    init {
        reactContext.addLifecycleEventListener(this)
    }

    override fun getName(): String = NAME

    // -------------------------------------------------------------------------
    // Bubble
    // -------------------------------------------------------------------------

    @ReactMethod
    fun show(options: ReadableMap, promise: Promise) {
        if (rejectIfNotDeclared(Manifest.permission.SYSTEM_ALERT_WINDOW, "show()", promise)) return

        if (!hasOverlayPermission()) {
            promise.reject(
                "PERMISSION_DENIED",
                "SYSTEM_ALERT_WINDOW permission is not granted. " +
                "Call requestPermission() first."
            )
            return
        }

        val config = BubbleConfig(
            sizeDp          = options.optInt("size")              ?: 60,
            // ARGB values like 0xFF4285F4 exceed Int.MAX_VALUE in JS; getInt() would clamp them
            color           = options.optDouble("color")?.toLong()?.toInt() ?: 0xFF4285F4.toInt(),
            iconBase64      = options.optString("icon"),
            // useLauncherIcon defaults to true when no explicit icon is provided
            useLauncherIcon = options.optBoolean("useLauncherIcon") ?: (options.optString("icon") == null),
            initialX        = options.optInt("initialX")          ?: 0,
            initialY        = options.optInt("initialY")          ?: 100,
            enableDismiss   = options.optBoolean("enableDismiss") ?: false,
        )
        val useService = options.optBoolean("foregroundService") ?: true

        if (useService) {
            missingServiceRequirement()?.let { (code, message) ->
                promise.reject(code, message)
                return
            }

            val intent = Intent(reactContext, FloatingBubbleService::class.java).apply {
                putExtras(config.toBundle())
                putExtra(FloatingBubbleService.EXTRA_NOTIFICATION_TITLE, options.optString("notificationTitle"))
                putExtra(FloatingBubbleService.EXTRA_NOTIFICATION_TEXT,  options.optString("notificationText"))
                putExtra(FloatingBubbleService.EXTRA_NOTIFICATION_ICON,  options.optString("notificationIcon"))
            }

            UiThreadUtil.runOnUiThread {
                // Switching from foregroundService: false — drop the in-app bubble
                inAppBubble.hide()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        reactContext.startForegroundService(intent)
                    } else {
                        reactContext.startService(intent)
                    }
                    promise.resolve(null)
                } catch (e: Exception) {
                    promise.reject("START_FAILED", "Could not start FloatingBubbleService: ${e.message}", e)
                }
            }
        } else {
            UiThreadUtil.runOnUiThread {
                // Switching from the service — stop it; it removes its own bubble
                reactContext.stopService(Intent(reactContext, FloatingBubbleService::class.java))
                try {
                    inAppBubble.show(config)
                    promise.resolve(null)
                } catch (e: Exception) {
                    inAppBubble.hide()
                    promise.reject("SHOW_FAILED", "Could not add the bubble overlay: ${e.message}", e)
                }
            }
        }
    }

    @ReactMethod
    fun hide(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            reactContext.stopService(Intent(reactContext, FloatingBubbleService::class.java))
            inAppBubble.hide()
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun isShowing(promise: Promise) {
        promise.resolve(inAppBubble.isShowing || isBubbleServiceRunning())
    }

    /** Changes the foreground service's notification text while the bubble shows. */
    @ReactMethod
    fun updateNotification(options: ReadableMap, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            val service = FloatingBubbleService.instance
            if (service == null) {
                promise.reject(
                    "NOT_RUNNING",
                    "The bubble's foreground service is not running. updateNotification() " +
                    "only applies to bubbles shown with foregroundService: true."
                )
                return@runOnUiThread
            }
            service.updateNotification(options.optString("title"), options.optString("text"))
            promise.resolve(null)
        }
    }

    // -------------------------------------------------------------------------
    // Overlay permission
    // -------------------------------------------------------------------------

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
        if (rejectIfNotDeclared(Manifest.permission.SYSTEM_ALERT_WINDOW, "requestPermission()", promise)) return

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${reactContext.packageName}")
        )
        openSystemScreenForResult(intent, promise) { hasOverlayPermission() }
    }

    // -------------------------------------------------------------------------
    // App / screen
    // -------------------------------------------------------------------------

    @ReactMethod
    fun bringAppToForeground(promise: Promise) {
        if (AppLauncher.bringToForeground(reactContext.applicationContext)) {
            promise.resolve(null)
        } else {
            promise.reject("NO_LAUNCH_INTENT", "Could not find or start the app's launcher activity.")
        }
    }

    /** Turns the screen on for [durationMs] using a timed wake lock. */
    @ReactMethod
    fun wakeScreen(durationMs: Double, promise: Promise) {
        if (rejectIfNotDeclared(Manifest.permission.WAKE_LOCK, "wakeScreen()", promise)) return

        try {
            val pm = reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "FloatingBubble:WakeScreen"
            ).acquire(durationMs.toLong())
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("WAKE_FAILED", "Could not acquire the wake lock: ${e.message}", e)
        }
    }

    /**
     * Lets the current activity show over the lock screen and turn the screen
     * on when it comes to the front.  Stays in effect until [disableLockScreenUI]
     * or the activity is destroyed.
     */
    @ReactMethod
    fun enableLockScreenUI(dismissKeyguard: Boolean, promise: Promise) {
        val activity = reactContext.currentActivity ?: run {
            promise.reject("NO_ACTIVITY", "No current activity found.")
            return
        }
        UiThreadUtil.runOnUiThread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                    if (dismissKeyguard) {
                        (activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                            .requestDismissKeyguard(activity, null)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                                (if (dismissKeyguard) WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD else 0)
                    activity.window.addFlags(flags)
                }
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("LOCK_SCREEN_UI_FAILED", e.message, e)
            }
        }
    }

    @ReactMethod
    fun disableLockScreenUI(promise: Promise) {
        val activity = reactContext.currentActivity ?: run {
            promise.reject("NO_ACTIVITY", "No current activity found.")
            return
        }
        UiThreadUtil.runOnUiThread {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activity.setShowWhenLocked(false)
                    activity.setTurnScreenOn(false)
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.clearFlags(
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    )
                }
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("LOCK_SCREEN_UI_FAILED", e.message, e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Battery optimization
    // -------------------------------------------------------------------------

    @ReactMethod
    fun isIgnoringBatteryOptimizations(promise: Promise) {
        promise.resolve(isIgnoringBatteryOptimizations())
    }

    /**
     * Shows the system "Allow app to run in background?" dialog.  Resolves with
     * the exemption state once the user returns to the app.
     */
    @SuppressLint("BatteryLife")
    @ReactMethod
    fun requestIgnoreBatteryOptimizations(promise: Promise) {
        if (isIgnoringBatteryOptimizations()) {
            promise.resolve(true)
            return
        }
        if (rejectIfNotDeclared(
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "requestIgnoreBatteryOptimizations()",
                promise
            )) return

        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${reactContext.packageName}")
        )
        openSystemScreenForResult(intent, promise) { isIgnoringBatteryOptimizations() }
    }

    /** Opens the system list of battery-optimized apps.  Needs no permission. */
    @ReactMethod
    fun openBatteryOptimizationSettings(promise: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            promise.resolve(null)
            return
        }
        if (startActivityFromApp(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            promise.resolve(null)
        } else {
            promise.reject("SETTINGS_UNAVAILABLE", "Could not open the battery optimization settings.")
        }
    }

    /**
     * Best effort: opens the device maker's own autostart / battery screen
     * (Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus, Asus).  Resolves true if
     * one opened; otherwise opens the standard battery optimization list and
     * resolves false.
     */
    @ReactMethod
    fun openManufacturerBatterySettings(promise: Promise) {
        val opened = MANUFACTURER_BATTERY_SCREENS.any { (pkg, cls) ->
            startActivityFromApp(Intent().setComponent(ComponentName(pkg, cls)))
        }
        if (!opened && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivityFromApp(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        promise.resolve(opened)
    }

    // -------------------------------------------------------------------------
    // LifecycleEventListener — resolve pending system-screen requests on resume
    // -------------------------------------------------------------------------

    override fun onHostResume() {
        pendingRequest?.let { request ->
            pendingRequest = null
            request.promise.resolve(request.check())
        }
    }

    override fun onHostPause() { /* no-op */ }

    override fun onHostDestroy() {
        pendingRequest?.promise?.reject("DESTROYED", "Activity destroyed.")
        pendingRequest = null
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

    private fun isIgnoringBatteryOptimizations(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            (reactContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(reactContext.packageName)
        else
            true

    private fun isPermissionDeclared(permission: String): Boolean =
        permission in declaredPermissions

    /** Rejects [promise] and returns true if the host app didn't declare [permission]. */
    private fun rejectIfNotDeclared(permission: String, feature: String, promise: Promise): Boolean {
        if (isPermissionDeclared(permission)) return false
        promise.reject(
            "PERMISSION_NOT_DECLARED",
            "$feature requires <uses-permission android:name=\"$permission\" /> " +
            "in your app's AndroidManifest.xml."
        )
        return true
    }

    /**
     * Returns (code, message) for the first thing the host app is missing to
     * run [FloatingBubbleService], or null if it can be started.
     */
    private fun missingServiceRequirement(): Pair<String, String>? {
        val serviceInfo = try {
            @Suppress("DEPRECATION")
            reactContext.packageManager.getServiceInfo(
                ComponentName(reactContext, FloatingBubbleService::class.java), 0
            )
        } catch (_: PackageManager.NameNotFoundException) {
            return "SERVICE_NOT_DECLARED" to
                "show() requires <service android:name=\"com.rnfloatingbubble.FloatingBubbleService\" /> " +
                "in your app's AndroidManifest.xml, or pass foregroundService: false."
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !isPermissionDeclared(Manifest.permission.FOREGROUND_SERVICE)
        ) {
            return notDeclared(Manifest.permission.FOREGROUND_SERVICE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val types = serviceInfo.foregroundServiceType
            // 0 = no android:foregroundServiceType attribute
            if (types == 0 &&
                reactContext.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                return "SERVICE_TYPE_MISSING" to
                    "FloatingBubbleService must declare android:foregroundServiceType " +
                    "(e.g. \"specialUse\") when targeting Android 14+."
            }
            foregroundServiceTypePermissions()
                .filter { (type, _) -> types and type != 0 }
                .firstOrNull { (_, permission) -> !isPermissionDeclared(permission) }
                ?.let { (_, permission) -> return notDeclared(permission) }
        }

        return null
    }

    private fun notDeclared(permission: String): Pair<String, String> =
        "PERMISSION_NOT_DECLARED" to
            "The bubble's foreground service requires <uses-permission android:name=\"$permission\" /> " +
            "in your app's AndroidManifest.xml."

    /** Permission each foreground service type requires on Android 14+. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun foregroundServiceTypePermissions(): List<Pair<Int, String>> = listOf(
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA           to Manifest.permission.FOREGROUND_SERVICE_CAMERA,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE to Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC        to Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH           to Manifest.permission.FOREGROUND_SERVICE_HEALTH,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION         to Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK   to Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION to Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE       to Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL       to Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING to Manifest.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE      to Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED  to Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED,
    )

    /**
     * Starts [intent] from the current activity and resolves [promise] with
     * [check] when the user comes back (see [onHostResume]).
     */
    private fun openSystemScreenForResult(intent: Intent, promise: Promise, check: () -> Boolean) {
        if (pendingRequest != null) {
            promise.reject("REQUEST_IN_PROGRESS", "Another permission request is still waiting for the user.")
            return
        }
        val activity = reactContext.currentActivity ?: run {
            promise.reject("NO_ACTIVITY", "No current activity found.")
            return
        }
        pendingRequest = PendingRequest(promise, check)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            pendingRequest = null
            promise.reject("SETTINGS_UNAVAILABLE", "Could not open the system settings screen: ${e.message}", e)
        }
    }

    /** Starts [intent] from the current activity, or the app context if none; false on failure. */
    private fun startActivityFromApp(intent: Intent): Boolean {
        return try {
            val activity = reactContext.currentActivity
            if (activity != null) {
                activity.startActivity(intent)
            } else {
                reactContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun isBubbleServiceRunning(): Boolean {
        val manager = reactContext.getSystemService(Context.ACTIVITY_SERVICE)
                as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == FloatingBubbleService::class.java.name
        }
    }

    // ReadableMap accessors that treat a missing or null key as "not set"
    private fun ReadableMap.isSet(key: String) = hasKey(key) && !isNull(key)
    private fun ReadableMap.optInt(key: String): Int?         = if (isSet(key)) getInt(key) else null
    private fun ReadableMap.optDouble(key: String): Double?   = if (isSet(key)) getDouble(key) else null
    private fun ReadableMap.optBoolean(key: String): Boolean? = if (isSet(key)) getBoolean(key) else null
    private fun ReadableMap.optString(key: String): String?   = if (isSet(key)) getString(key) else null
}
