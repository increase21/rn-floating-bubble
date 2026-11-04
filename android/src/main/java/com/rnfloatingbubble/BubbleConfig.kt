package com.rnfloatingbubble

import android.graphics.Color
import android.os.Bundle

/**
 * Appearance and behaviour of the bubble, as passed to `show()`.
 *
 * Travels to [FloatingBubbleService] as intent extras via [toBundle] /
 * [fromBundle], so it survives a redelivered intent after a process restart.
 */
data class BubbleConfig(
    val sizeDp: Int          = 60,
    val color: Int           = Color.parseColor("#4285F4"),
    val iconBase64: String?  = null,
    val useLauncherIcon: Boolean = true,
    val initialX: Int        = 0,
    val initialY: Int        = 100,
    val enableDismiss: Boolean = false,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putInt(KEY_SIZE,                  sizeDp)
        putInt(KEY_COLOR,                 color)
        putString(KEY_ICON,               iconBase64)
        putBoolean(KEY_USE_LAUNCHER_ICON, useLauncherIcon)
        putInt(KEY_INITIAL_X,             initialX)
        putInt(KEY_INITIAL_Y,             initialY)
        putBoolean(KEY_ENABLE_DISMISS,    enableDismiss)
    }

    companion object {
        private const val KEY_SIZE              = "size"
        private const val KEY_COLOR             = "color"
        private const val KEY_ICON              = "icon"
        private const val KEY_USE_LAUNCHER_ICON = "useLauncherIcon"
        private const val KEY_INITIAL_X         = "initialX"
        private const val KEY_INITIAL_Y         = "initialY"
        private const val KEY_ENABLE_DISMISS    = "enableDismiss"

        fun fromBundle(bundle: Bundle?): BubbleConfig {
            val defaults = BubbleConfig()
            if (bundle == null) return defaults
            return BubbleConfig(
                sizeDp          = bundle.getInt(KEY_SIZE,                  defaults.sizeDp),
                color           = bundle.getInt(KEY_COLOR,                 defaults.color),
                iconBase64      = bundle.getString(KEY_ICON),
                useLauncherIcon = bundle.getBoolean(KEY_USE_LAUNCHER_ICON, defaults.useLauncherIcon),
                initialX        = bundle.getInt(KEY_INITIAL_X,             defaults.initialX),
                initialY        = bundle.getInt(KEY_INITIAL_Y,             defaults.initialY),
                enableDismiss   = bundle.getBoolean(KEY_ENABLE_DISMISS,    defaults.enableDismiss),
            )
        }
    }
}
