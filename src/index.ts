import { NativeModules, Platform } from 'react-native';

const LINKING_ERROR =
  "The package '@increase21/rn-floating-bubble' doesn't seem to be linked. Make sure: \n\n" +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const NativeFloatingBubble = NativeModules.FloatingBubble
  ? NativeModules.FloatingBubble
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

/**
 * `code` values the returned promises can reject with.
 *
 * - `PERMISSION_NOT_DECLARED` — a permission the feature needs is missing
 *   from your app's AndroidManifest.xml (the message names it)
 * - `SERVICE_NOT_DECLARED` / `SERVICE_TYPE_MISSING` — `FloatingBubbleService`
 *   is not declared, or has no `android:foregroundServiceType` on Android 14+
 * - `PERMISSION_DENIED` — the overlay permission is declared but not granted
 */
export type FloatingBubbleErrorCode =
  | 'PERMISSION_NOT_DECLARED'
  | 'PERMISSION_DENIED'
  | 'SERVICE_NOT_DECLARED'
  | 'SERVICE_TYPE_MISSING'
  | 'START_FAILED'
  | 'SHOW_FAILED'
  | 'NOT_RUNNING'
  | 'NO_ACTIVITY'
  | 'NO_LAUNCH_INTENT'
  | 'REQUEST_IN_PROGRESS'
  | 'SETTINGS_UNAVAILABLE'
  | 'WAKE_FAILED'
  | 'LOCK_SCREEN_UI_FAILED'
  | 'DESTROYED';

export interface FloatingBubbleOptions {
  /**
   * Bubble diameter in dp. Defaults to 60.
   */
  size?: number;
  /**
   * Bubble background color as an ARGB integer (e.g. 0xFF4285F4).
   * Only used when both `icon` and `useLauncherIcon` resolve to nothing.
   */
  color?: number;
  /**
   * Base64-encoded PNG/JPG to use as the bubble icon.
   * Takes priority over `useLauncherIcon`.
   */
  icon?: string;
  /**
   * When `true` (the default when no `icon` is provided) the library reads
   * the host app's launcher icon from the PackageManager and clips it to a
   * circle.  Set to `false` to force the plain colour circle instead.
   *
   * The icon resolution order is:
   *   1. `icon` (base64)  — developer-supplied custom image
   *   2. launcher icon    — `useLauncherIcon: true` (default)
   *   3. colour circle    — fallback when everything else fails
   *
   * @default true (when `icon` is not set)
   */
  useLauncherIcon?: boolean;
  /**
   * Initial X position of the bubble in screen pixels. Defaults to 0 (left edge).
   */
  initialX?: number;
  /**
   * Initial Y position of the bubble in screen pixels. Defaults to 100.
   */
  initialY?: number;
  /**
   * When `true`, dragging the bubble reveals a dismiss zone (✕) at the bottom
   * of the screen; releasing the bubble onto it closes it.
   *
   * When `false` (the default) the bubble can only be hidden programmatically
   * via `FloatingBubble.hide()`.
   *
   * @default false
   */
  enableDismiss?: boolean;
  /**
   * When `true` (the default) the bubble runs in the library's own foreground
   * service, which your app must declare (see README, "Android setup").
   *
   * Set to `false` if your app already runs its own foreground service (e.g.
   * location): the bubble is then added without a service or notification of
   * its own, and lives only as long as your app's process.
   *
   * @default true
   */
  foregroundService?: boolean;
  /**
   * Title of the foreground service notification.  Defaults to the
   * `floating_bubble_notification_title` string resource, which your app can
   * override in its own `res/values/strings.xml`.
   */
  notificationTitle?: string;
  /**
   * Body text of the foreground service notification.  Defaults to the
   * `floating_bubble_notification_text` string resource.
   */
  notificationText?: string;
  /**
   * Name of a drawable or mipmap resource in your app to use as the
   * notification's small icon (e.g. `'ic_stat_notification'`).  Falls back to
   * the library's default icon if not found.
   */
  notificationIcon?: string;
}

export interface NotificationUpdate {
  /** New notification title; omit to keep the current one. */
  title?: string;
  /** New notification text; omit to keep the current one. */
  text?: string;
}

export interface LockScreenUIOptions {
  /**
   * Also ask the system to dismiss the keyguard (the user may still need to
   * enter their PIN).  Without it the app shows over the lock screen but the
   * device stays locked.
   *
   * @default false
   */
  dismissKeyguard?: boolean;
}

const isAndroid = Platform.OS === 'android';

const FloatingBubble = {
  // ---------------------------------------------------------------------------
  // Bubble
  // ---------------------------------------------------------------------------

  /**
   * Display the floating bubble.
   *
   * Requires SYSTEM_ALERT_WINDOW permission — call `requestPermission()`
   * and wait for user approval before calling `show()`.  With the default
   * `foregroundService: true`, your app must also declare
   * `FloatingBubbleService` and its permissions (see README).
   *
   * @throws if a required permission or the service is not declared, or the
   *         overlay permission has not been granted.
   */
  show(options: FloatingBubbleOptions = {}): Promise<void> {
    if (!isAndroid) {
      return Promise.reject(new Error('FloatingBubble is only supported on Android.'));
    }
    const hasCustomIcon = options.icon != null;
    const opts = {
      size: options.size ?? 60,
      color: options.color ?? 0xff4285f4,
      icon: options.icon ?? null,
      // Default: use launcher icon only when no custom icon is provided
      useLauncherIcon: options.useLauncherIcon ?? !hasCustomIcon,
      initialX: options.initialX ?? 0,
      initialY: options.initialY ?? 100,
      enableDismiss: options.enableDismiss ?? false,
      foregroundService: options.foregroundService ?? true,
      notificationTitle: options.notificationTitle ?? null,
      notificationText: options.notificationText ?? null,
      notificationIcon: options.notificationIcon ?? null,
    };
    return NativeFloatingBubble.show(opts);
  },

  /**
   * Hide and destroy the floating bubble.
   */
  hide(): Promise<void> {
    if (!isAndroid) {
      return Promise.resolve();
    }
    return NativeFloatingBubble.hide();
  },

  /**
   * Returns `true` if the bubble is currently visible.
   */
  isShowing(): Promise<boolean> {
    if (!isAndroid) {
      return Promise.resolve(false);
    }
    return NativeFloatingBubble.isShowing();
  },

  /**
   * Change the foreground service notification while the bubble is showing.
   * Rejects with `NOT_RUNNING` for bubbles shown with `foregroundService: false`.
   */
  updateNotification(update: NotificationUpdate): Promise<void> {
    if (!isAndroid) {
      return Promise.resolve();
    }
    return NativeFloatingBubble.updateNotification({
      title: update.title ?? null,
      text: update.text ?? null,
    });
  },

  // ---------------------------------------------------------------------------
  // Overlay permission
  // ---------------------------------------------------------------------------

  /**
   * Returns `true` if the SYSTEM_ALERT_WINDOW permission has been granted.
   */
  hasPermission(): Promise<boolean> {
    if (!isAndroid) {
      return Promise.resolve(false);
    }
    return NativeFloatingBubble.hasPermission();
  },

  /**
   * Opens the system settings screen so the user can grant the
   * SYSTEM_ALERT_WINDOW ("Display over other apps") permission.
   *
   * The returned promise resolves once the user returns to the app.
   * The resolved value is `true` if permission was granted.
   */
  requestPermission(): Promise<boolean> {
    if (!isAndroid) {
      return Promise.resolve(false);
    }
    return NativeFloatingBubble.requestPermission();
  },

  // ---------------------------------------------------------------------------
  // App / screen
  // ---------------------------------------------------------------------------

  /**
   * Brings the app to the foreground, launching its main activity if needed.
   *
   * From the background this relies on the overlay permission (Android 15+
   * also needs a visible overlay, such as the bubble); otherwise Android may
   * silently block it.
   */
  bringAppToForeground(): Promise<void> {
    if (!isAndroid) {
      return Promise.resolve();
    }
    return NativeFloatingBubble.bringAppToForeground();
  },

  /**
   * Turns the screen on for `durationMs` (default 3000) with a wake lock.
   * Requires the WAKE_LOCK permission in your app's manifest.
   *
   * Best effort: newer Android versions may ignore it.  For reliably showing
   * the app on a locked device, use `enableLockScreenUI()` followed by
   * `bringAppToForeground()`.
   */
  wakeScreen(durationMs: number = 3000): Promise<void> {
    if (!isAndroid) {
      return Promise.resolve();
    }
    return NativeFloatingBubble.wakeScreen(durationMs);
  },

  /**
   * Lets the app's activity show over the lock screen and turn the screen on
   * when it comes to the front.  Stays in effect until `disableLockScreenUI()`
   * is called or the activity is destroyed.
   */
  enableLockScreenUI(options: LockScreenUIOptions = {}): Promise<void> {
    if (!isAndroid) {
      return Promise.resolve();
    }
    return NativeFloatingBubble.enableLockScreenUI(options.dismissKeyguard ?? false);
  },

  /**
   * Undoes `enableLockScreenUI()`.
   */
  disableLockScreenUI(): Promise<void> {
    if (!isAndroid) {
      return Promise.resolve();
    }
    return NativeFloatingBubble.disableLockScreenUI();
  },

  // ---------------------------------------------------------------------------
  // Battery optimization
  // ---------------------------------------------------------------------------

  /**
   * Returns `true` if the app is exempt from battery optimization (always
   * `true` below Android 6 and on iOS).
   */
  isIgnoringBatteryOptimizations(): Promise<boolean> {
    if (!isAndroid) {
      return Promise.resolve(true);
    }
    return NativeFloatingBubble.isIgnoringBatteryOptimizations();
  },

  /**
   * Shows the system "Allow app to run in background?" dialog.  Resolves with
   * `true` if the app is exempt once the user returns.
   *
   * Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in your app's manifest —
   * a permission Google Play restricts to qualifying apps.
   */
  requestIgnoreBatteryOptimizations(): Promise<boolean> {
    if (!isAndroid) {
      return Promise.resolve(true);
    }
    return NativeFloatingBubble.requestIgnoreBatteryOptimizations();
  },

  /**
   * Opens the system list of battery-optimized apps.  Needs no permission.
   */
  openBatteryOptimizationSettings(): Promise<void> {
    if (!isAndroid) {
      return Promise.resolve();
    }
    return NativeFloatingBubble.openBatteryOptimizationSettings();
  },

  /**
   * Best effort: opens the device maker's own autostart / battery screen
   * (Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus, Asus).  Resolves `true` if
   * one opened; otherwise opens the standard battery optimization list and
   * resolves `false`.
   */
  openManufacturerBatterySettings(): Promise<boolean> {
    if (!isAndroid) {
      return Promise.resolve(false);
    }
    return NativeFloatingBubble.openManufacturerBatterySettings();
  },
};

export default FloatingBubble;
