package com.rnfloatingbubble

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Foreground service that renders and manages the floating bubble overlay.
 *
 * The bubble is added to the [WindowManager] so it persists over all
 * other apps.  Touch handling supports:
 *   - Drag to reposition
 *   - Spring-snap to the nearest screen edge on release
 *   - Tap to bring the host app to the foreground
 *   - Dragging reveals a dismiss zone (✕) at the bottom of the screen;
 *     release onto it to close the bubble (opt-in via [enableDismiss])
 *
 * Icon resolution order (first match wins):
 *   1. Developer-supplied base64 image  (`icon` option)
 *   2. App launcher icon               (`useLauncherIcon = true`, the default)
 *   3. Filled colour circle            (always available fallback)
 */
class FloatingBubbleService : Service() {

    // -------------------------------------------------------------------------
    // Companion / constants
    // -------------------------------------------------------------------------

    companion object {
        const val EXTRA_SIZE              = "size"
        const val EXTRA_COLOR             = "color"
        const val EXTRA_ICON              = "icon"
        const val EXTRA_INITIAL_X         = "initialX"
        const val EXTRA_INITIAL_Y         = "initialY"
        const val EXTRA_USE_LAUNCHER_ICON = "useLauncherIcon"
        const val EXTRA_ENABLE_DISMISS    = "enableDismiss"

        private const val NOTIFICATION_ID      = 1001
        private const val CHANNEL_ID           = "rn_floating_bubble_channel"
        private const val CLOSE_ZONE_SIZE_DP   = 44
        private const val CLOSE_ZONE_BOTTOM_DP = 40
        // Pixel radius within which the bubble snaps onto the close zone
        private const val SNAP_THRESHOLD_DP    = 55
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var windowManager: WindowManager?       = null
    private var bubbleView: ImageView?              = null
    private var params: WindowManager.LayoutParams? = null

    // Dismiss zone (only created when enableDismiss = true)
    private var closeView: ImageView?               = null
    private var closeParams: WindowManager.LayoutParams? = null

    private var bubbleSizeDp    = 60
    private var bubbleColor     = Color.parseColor("#4285F4")
    private var iconBase64: String? = null
    private var useLauncherIcon = true
    private var initialX        = 0
    private var initialY        = 100
    private var enableDismiss   = false

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            bubbleSizeDp    = it.getIntExtra(EXTRA_SIZE,              60)
            bubbleColor     = it.getIntExtra(EXTRA_COLOR,             Color.parseColor("#4285F4"))
            iconBase64      = it.getStringExtra(EXTRA_ICON)
            useLauncherIcon = it.getBooleanExtra(EXTRA_USE_LAUNCHER_ICON, true)
            initialX        = it.getIntExtra(EXTRA_INITIAL_X,         0)
            initialY        = it.getIntExtra(EXTRA_INITIAL_Y,         100)
            enableDismiss   = it.getBooleanExtra(EXTRA_ENABLE_DISMISS, false)
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        try {
            setupBubble()
        } catch (_: Exception) {
            // Overlay permission revoked (e.g. sticky restart after the user disabled it)
            removeBubble()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
    }

    // -------------------------------------------------------------------------
    // Bubble setup
    // -------------------------------------------------------------------------

    private fun setupBubble() {
        // show() may be called while already showing; drop the old views first
        removeBubble()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = dp2px(bubbleSizeDp)
        bubbleView = ImageView(this).apply {
            elevation = 8f
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        bubbleView!!.setImageBitmap(resolveIcon(sizePx))

        val windowType = overlayWindowType()

        params = WindowManager.LayoutParams(
            sizePx, sizePx, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        windowManager!!.addView(bubbleView, params)

        if (enableDismiss) setupCloseZone(windowType)

        attachTouchListener()
    }

    /**
     * Creates the dismiss zone — a translucent circle with a ✕ drawn on it,
     * anchored to the bottom-centre of the screen. Hidden (alpha 0) at rest;
     * fades in when the user begins dragging.
     */
    private fun setupCloseZone(windowType: Int) {
        val sizePx       = dp2px(CLOSE_ZONE_SIZE_DP)
        val screenWidth  = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        closeView = ImageView(this).apply {
            alpha = 0f
            elevation = 10f
            setImageBitmap(buildCloseZoneBitmap(sizePx))
        }

        closeParams = WindowManager.LayoutParams(
            sizePx, sizePx, windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth  / 2) - (sizePx / 2)
            y = screenHeight - sizePx - dp2px(CLOSE_ZONE_BOTTOM_DP)
        }

        windowManager!!.addView(closeView, closeParams)
    }

    private fun removeBubble() {
        try { bubbleView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { closeView?.let  { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubbleView = null
        closeView  = null
    }

    // -------------------------------------------------------------------------
    // Touch handling
    // -------------------------------------------------------------------------

    private fun attachTouchListener() {
        var downParamsX = 0
        var downParamsY = 0
        var downRawX    = 0f
        var downRawY    = 0f
        var isDragging  = false
        var isOverClose = false

        bubbleView?.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downParamsX = params?.x ?: 0
                    downParamsY = params?.y ?: 0
                    downRawX    = event.rawX
                    downRawY    = event.rawY
                    isDragging  = false
                    isOverClose = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()

                    if (!isDragging && (abs(dx) > 8 || abs(dy) > 8)) {
                        isDragging = true
                        // Show X zone as soon as a real drag starts
                        if (enableDismiss) showCloseZone()
                    }

                    if (isDragging) {
                        params?.x = downParamsX + dx
                        params?.y = downParamsY + dy

                        if (enableDismiss) {
                            isOverClose = isOverCloseZone()
                            if (isOverClose) {
                                snapBubbleToCloseZone()
                            } else {
                                safeUpdateLayout()
                            }
                        } else {
                            safeUpdateLayout()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    hideCloseZone()

                    when {
                        isDragging && enableDismiss && isOverClose -> stopSelf()
                        isDragging -> snapToEdge()
                        else -> openHostApp()
                    }

                    isDragging  = false
                    isOverClose = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    hideCloseZone()
                    if (isDragging) snapToEdge()
                    isDragging  = false
                    isOverClose = false
                    true
                }

                else -> false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Close zone helpers
    // -------------------------------------------------------------------------

    private fun showCloseZone() {
        closeView?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    private fun hideCloseZone() {
        closeView?.animate()?.alpha(0f)?.setDuration(150)?.start()
    }

    /**
     * Returns true if the centre of the bubble is within [SNAP_THRESHOLD_DP] dp
     * of the centre of the close zone.
     */
    private fun isOverCloseZone(): Boolean {
        val cp = closeParams ?: return false
        val pp = params      ?: return false

        val bubbleSizePx = dp2px(bubbleSizeDp)
        val closeSizePx  = dp2px(CLOSE_ZONE_SIZE_DP)

        val bubbleCx = pp.x + bubbleSizePx / 2f
        val bubbleCy = pp.y + bubbleSizePx / 2f
        val closeCx  = cp.x + closeSizePx  / 2f
        val closeCy  = cp.y + closeSizePx  / 2f

        return hypot((bubbleCx - closeCx).toDouble(), (bubbleCy - closeCy).toDouble()) <
                dp2px(SNAP_THRESHOLD_DP)
    }

    /** Moves the bubble so its centre aligns with the close zone centre. */
    private fun snapBubbleToCloseZone() {
        val cp = closeParams ?: return
        val bubbleSizePx = dp2px(bubbleSizeDp)
        val closeSizePx  = dp2px(CLOSE_ZONE_SIZE_DP)
        params?.x = cp.x + closeSizePx  / 2 - bubbleSizePx / 2
        params?.y = cp.y + closeSizePx  / 2 - bubbleSizePx / 2
        safeUpdateLayout()
    }

    // -------------------------------------------------------------------------
    // Snap animation
    // -------------------------------------------------------------------------

    private fun snapToEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val currentX    = params?.x ?: 0
        val bubbleWidth = dp2px(bubbleSizeDp)
        val targetX     = if (currentX + bubbleWidth / 2 < screenWidth / 2) 0
                          else screenWidth - bubbleWidth

        ValueAnimator.ofInt(currentX, targetX).apply {
            duration     = 350L
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { animator ->
                params?.x = animator.animatedValue as Int
                safeUpdateLayout()
            }
            start()
        }
    }

    private fun safeUpdateLayout() {
        try {
            bubbleView?.let { windowManager?.updateViewLayout(it, params) }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Open host app
    // -------------------------------------------------------------------------

    private fun openHostApp() {
        val intent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
        intent?.let { startActivity(it) }
    }

    // -------------------------------------------------------------------------
    // Icon resolution  (priority: base64 → launcher icon → colour circle)
    // -------------------------------------------------------------------------

    private fun resolveIcon(sizePx: Int): Bitmap {
        iconBase64?.let { b64 ->
            decodeBase64Bitmap(b64)?.let { raw ->
                return clipToCircle(scaleBitmap(raw, sizePx))
            }
        }
        if (useLauncherIcon) {
            getLauncherIconBitmap(sizePx)?.let { return clipToCircle(it) }
        }
        return buildColoredCircle(sizePx, bubbleColor)
    }

    private fun getLauncherIconBitmap(sizePx: Int): Bitmap? {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap   = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas   = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) { null }
    }

    private fun clipToCircle(src: Bitmap): Bitmap {
        val size   = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, maskPaint)
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(src, 0f, 0f, imagePaint)
        return output
    }

    private fun scaleBitmap(src: Bitmap, targetPx: Int): Bitmap =
        Bitmap.createScaledBitmap(src, targetPx, targetPx, true)

    private fun buildColoredCircle(sizePx: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val radius = sizePx / 2f - 4f
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(60, 0, 0, 0)
            maskFilter  = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy + 3f, radius, shadowPaint)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(cx, cy, radius, fillPaint)
        return bitmap
    }

    /**
     * Draws the dismiss zone bitmap: a semi-transparent dark circle with a
     * white ✕ in the centre, matching the standard Android chat-head style.
     */
    private fun buildCloseZoneBitmap(sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val radius = sizePx / 2f - 2f

        // Semi-transparent dark background circle
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 30, 30, 30)
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Muted light-gray ✕ lines
        val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = Color.argb(210, 200, 200, 200)
            strokeWidth = sizePx * 0.07f
            strokeCap   = Paint.Cap.ROUND
        }
        val offset = sizePx * 0.22f
        canvas.drawLine(cx - offset, cy - offset, cx + offset, cy + offset, crossPaint)
        canvas.drawLine(cx + offset, cy - offset, cx - offset, cy + offset, crossPaint)

        return bitmap
    }

    private fun decodeBase64Bitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
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
            .setContentTitle(getString(R.string.floating_bubble_notification_title))
            .setContentText(getString(R.string.floating_bubble_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
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

    // -------------------------------------------------------------------------
    // Generic helpers
    // -------------------------------------------------------------------------

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp2px(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).roundToInt()
}
