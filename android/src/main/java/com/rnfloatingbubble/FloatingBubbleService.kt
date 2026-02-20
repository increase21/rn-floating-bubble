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
import kotlin.math.roundToInt

/**
 * Foreground service that renders and manages the floating bubble overlay.
 *
 * The bubble is added to the [WindowManager] so it persists over all
 * other apps.  Touch handling supports:
 *   - Drag to reposition
 *   - Spring-snap to the nearest screen edge on release
 *   - Tap to bring the host app to the foreground
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
        /** When true (default) the app launcher icon is used if no custom icon is given. */
        const val EXTRA_USE_LAUNCHER_ICON = "useLauncherIcon"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "rn_floating_bubble_channel"
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var windowManager: WindowManager?       = null
    private var bubbleView: ImageView?              = null
    private var params: WindowManager.LayoutParams? = null

    private var bubbleSizeDp     = 60
    private var bubbleColor      = Color.parseColor("#4285F4")
    private var iconBase64: String? = null
    private var useLauncherIcon  = true      // default: try the launcher icon
    private var initialX         = 0
    private var initialY         = 100

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
        }

        // Must call startForeground before doing UI work on API 26+
        startForeground(NOTIFICATION_ID, buildNotification())
        setupBubble()

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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sizePx = dp2px(bubbleSizeDp)
        bubbleView = ImageView(this).apply {
            elevation = 8f
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        bubbleView!!.setImageBitmap(resolveIcon(sizePx))

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        windowManager!!.addView(bubbleView, params)
        attachTouchListener()
    }

    private fun removeBubble() {
        try {
            bubbleView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { /* already detached */ }
        bubbleView = null
    }

    // -------------------------------------------------------------------------
    // Icon resolution  (priority: base64 → launcher icon → colour circle)
    // -------------------------------------------------------------------------

    /**
     * Resolves the bubble icon following the three-tier priority:
     *
     * 1. **Developer base64** — if [iconBase64] is non-null and decodable, it is
     *    scaled to [sizePx] × [sizePx] and clipped to a circle.
     * 2. **Launcher icon** — if [useLauncherIcon] is true, the app's own launcher
     *    icon (as returned by [android.content.pm.PackageManager.getApplicationIcon])
     *    is scaled and clipped to a circle.
     * 3. **Colour circle** — a simple filled circle in [bubbleColor] with a drop
     *    shadow (always succeeds).
     */
    private fun resolveIcon(sizePx: Int): Bitmap {
        // 1. Developer-supplied base64 image
        iconBase64?.let { b64 ->
            decodeBase64Bitmap(b64)?.let { raw ->
                return clipToCircle(scaleBitmap(raw, sizePx))
            }
        }

        // 2. App launcher icon
        if (useLauncherIcon) {
            getLauncherIconBitmap(sizePx)?.let { return clipToCircle(it) }
        }

        // 3. Coloured circle fallback
        return buildColoredCircle(sizePx, bubbleColor)
    }

    /**
     * Retrieves the app's launcher icon as a [Bitmap] scaled to [sizePx].
     * Returns null on any failure so the caller can fall through gracefully.
     */
    private fun getLauncherIconBitmap(sizePx: Int): Bitmap? {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap   = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas   = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clips [src] to a circle using a Porter-Duff DST_IN mask so the result
     * has a transparent background outside the circle boundary.
     */
    private fun clipToCircle(src: Bitmap): Bitmap {
        val size   = minOf(src.width, src.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Draw a solid white circle as a mask
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, maskPaint)

        // Draw the source bitmap restricted to the circle
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(src, 0f, 0f, imagePaint)

        return output
    }

    /** Scales [src] to exactly [targetPx] × [targetPx]. */
    private fun scaleBitmap(src: Bitmap, targetPx: Int): Bitmap =
        Bitmap.createScaledBitmap(src, targetPx, targetPx, true)

    // -------------------------------------------------------------------------
    // Touch handling
    // -------------------------------------------------------------------------

    private fun attachTouchListener() {
        var downParamsX = 0
        var downParamsY = 0
        var downRawX    = 0f
        var downRawY    = 0f
        var isDragging  = false

        bubbleView?.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downParamsX = params?.x ?: 0
                    downParamsY = params?.y ?: 0
                    downRawX    = event.rawX
                    downRawY    = event.rawY
                    isDragging  = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!isDragging && (abs(dx) > 8 || abs(dy) > 8)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params?.x = downParamsX + dx
                        params?.y = downParamsY + dy
                        safeUpdateLayout()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) snapToEdge() else openHostApp()
                    true
                }

                else -> false
            }
        }
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
        } catch (_: Exception) { /* view already removed */ }
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
    // Notification (required for foreground service)
    // -------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val openIntent   = packageManager.getLaunchIntentForPackage(packageName)
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

    /**
     * Builds a circular [Bitmap] filled with [color] and a subtle drop shadow.
     * Used as the final fallback when no other icon source is available.
     */
    private fun buildColoredCircle(sizePx: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx     = sizePx / 2f
        val cy     = sizePx / 2f
        val radius = sizePx / 2f - 4f

        // Drop shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(60, 0, 0, 0)
            maskFilter  = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawCircle(cx, cy + 3f, radius, shadowPaint)

        // Fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        }
        canvas.drawCircle(cx, cy, radius, fillPaint)

        return bitmap
    }

    private fun decodeBase64Bitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun dp2px(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).roundToInt()
}
