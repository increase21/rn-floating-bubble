package com.rnfloatingbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the process alive while the bubble is shown
 * and owns its persistent notification.  The overlay itself is drawn by
 * [BubbleController].
 *
 * The library does not declare this service; the host app declares it in its
 * own AndroidManifest.xml, choosing the foreground service type (see README).
 */
class FloatingBubbleService : Service() {

    companion object {
        const val EXTRA_NOTIFICATION_TITLE = "notificationTitle"
        const val EXTRA_NOTIFICATION_TEXT  = "notificationText"
        const val EXTRA_NOTIFICATION_ICON  = "notificationIcon"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "rn_floating_bubble_channel"

        /** The running service, or null.  Read and written on the main thread. */
        var instance: FloatingBubbleService? = null
            private set
    }

    private lateinit var bubble: BubbleController

    // Null means "use the string resource", which the host app can override
    private var notificationTitle: String? = null
    private var notificationText: String?  = null
    private var notificationIcon: String?  = null

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bubble = BubbleController(this) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            notificationTitle = it.getStringExtra(EXTRA_NOTIFICATION_TITLE)
            notificationText  = it.getStringExtra(EXTRA_NOTIFICATION_TEXT)
            notificationIcon  = it.getStringExtra(EXTRA_NOTIFICATION_ICON)
        }

        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            bubble.show(BubbleConfig.fromBundle(intent?.extras))
        } catch (_: Exception) {
            // Missing foreground-service prerequisite, or overlay permission
            // revoked (e.g. redelivered intent after the user disabled it)
            bubble.hide()
            stopSelf()
            return START_NOT_STICKY
        }

        instance = this
        // Redeliver so the bubble and notification options survive a restart
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        bubble.hide()
        super.onDestroy()
    }

    /** Replaces the notification text; a null argument keeps the current value. */
    fun updateNotification(title: String?, text: String?) {
        title?.let { notificationTitle = it }
        text?.let  { notificationText  = it }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    // -------------------------------------------------------------------------
    // Notification (required for foreground service)
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val openIntent    = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle ?: getString(R.string.floating_bubble_notification_title))
            .setContentText(notificationText ?: getString(R.string.floating_bubble_notification_text))
            .setSmallIcon(resolveSmallIcon())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /** Looks up the `notificationIcon` name in the app's drawables, then mipmaps. */
    private fun resolveSmallIcon(): Int {
        val name = notificationIcon ?: return R.drawable.ic_notification
        for (type in arrayOf("drawable", "mipmap")) {
            val id = resources.getIdentifier(name, type, packageName)
            if (id != 0) return id
        }
        return R.drawable.ic_notification
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_bubble_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.floating_bubble_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
