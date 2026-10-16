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
   * Mimics the standard Android chat-head dismiss pattern.
   *
   * When `false` (the default) the bubble can only be hidden programmatically
   * via `FloatingBubble.hide()`.
   *
   * @default false
   */
  enableDismiss?: boolean;
}

const FloatingBubble = {
  /**
   * Display the floating bubble.
   *
   * Requires SYSTEM_ALERT_WINDOW permission — call `requestPermission()`
   * and wait for user approval before calling `show()`.
   *
   * @throws if the permission has not been granted.
   */
  show(options: FloatingBubbleOptions = {}): Promise<void> {
    if (Platform.OS !== 'android') {
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
    };
    return NativeFloatingBubble.show(opts);
  },

  /**
   * Hide and destroy the floating bubble.
   */
  hide(): Promise<void> {
    if (Platform.OS !== 'android') {
      return Promise.resolve();
    }
    return NativeFloatingBubble.hide();
  },

  /**
   * Returns `true` if the bubble is currently visible.
   */
  isShowing(): Promise<boolean> {
    if (Platform.OS !== 'android') {
      return Promise.resolve(false);
    }
    return NativeFloatingBubble.isShowing();
  },

  /**
   * Returns `true` if the SYSTEM_ALERT_WINDOW permission has been granted.
   */
  hasPermission(): Promise<boolean> {
    if (Platform.OS !== 'android') {
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
    if (Platform.OS !== 'android') {
      return Promise.resolve(false);
    }
    return NativeFloatingBubble.requestPermission();
  },
};

export default FloatingBubble;
