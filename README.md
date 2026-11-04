# @increase21/rn-floating-bubble

A React Native library for Android that shows a draggable, tappable floating
bubble (chat-head style) that sits on top of all other apps.  Tapping the
bubble brings your host application back to the foreground.

It also bundles the helpers an always-on app usually needs alongside a bubble:
bringing the app to the front, waking the screen, showing over the lock screen,
and managing battery optimization.

> **iOS note** — iOS does not permit apps to draw over the system UI outside of
> their own window, so this library is **Android-only**.  On iOS every method
> is a no-op (`show()` rejects).

---

## Features

- Draggable bubble that floats over the home screen and every other app
- Spring-snaps to the nearest screen edge when released
- Tap to instantly reopen the host app
- **Drag-to-dismiss** — optional ✕ close zone that appears while dragging; drag onto it to remove the bubble
- **Automatic launcher-icon support** — uses your app's own icon by default
- Custom base64 image icon as an alternative
- Plain colour circle as the final fallback
- Handles the `SYSTEM_ALERT_WINDOW` permission request flow
- Optional foreground service keeps the bubble alive, with **custom notification text** — or reuse your app's own foreground service instead
- **Bring app to foreground**, **wake screen**, **show over the lock screen**
- **Battery optimization** — check, request the exemption, open system and manufacturer settings
- **You declare only the permissions you use** — the library's manifest is empty

---

## Installation

```sh
npm install @increase21/rn-floating-bubble
# or
yarn add @increase21/rn-floating-bubble
```

React Native ≥ 0.60 auto-links the native module — no manual linking needed.

---

## Android setup

The library declares **no permissions and no service** in its own manifest.
Add to your app's `android/app/src/main/AndroidManifest.xml` only what the
features you use need.  If something is missing, the method that needs it
rejects with `PERMISSION_NOT_DECLARED` or `SERVICE_NOT_DECLARED` and a message
naming exactly what to add — it never crashes.

| Feature | Add to your manifest |
|---|---|
| Bubble — `show()`, `requestPermission()` | `SYSTEM_ALERT_WINDOW` |
| Bubble's own foreground service (`foregroundService: true`, the default) | `FOREGROUND_SERVICE`, the permission for the service type you choose, and the `<service>` element |
| `wakeScreen()` | `WAKE_LOCK` |
| `requestIgnoreBatteryOptimizations()` | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| Everything else | Nothing |

### Bubble (always needed)

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

### Option A — the bubble's own foreground service (default)

Keeps the bubble alive while your app is in the background, with its own
notification.  Declare the service inside `<application>` and pick its type.
`specialUse` fits a plain bubble:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<application ...>
  <service
    android:name="com.rnfloatingbubble.FloatingBubbleService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
      android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
      android:value="floating bubble overlay" />
  </service>
</application>
```

> Google Play asks you to justify `specialUse` foreground services in the Play
> Console.  On Android 14+ the library checks that the permission matching
> each declared type is present before starting the service.

### Option B — your app already runs a foreground service

If your app already has a foreground service running while the bubble is shown
(for example a `location` service in a driver or delivery app), skip Option A
and pass `foregroundService: false`.  The bubble is then added without a
service or notification of its own, and your service keeps the process alive.

```tsx
await FloatingBubble.show({ foregroundService: false });
```

If your service stops while the bubble is showing, Android may kill the
process and the bubble with it.

### Other features

```xml
<!-- wakeScreen() -->
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- requestIgnoreBatteryOptimizations() — restricted by Google Play policy,
     only declare it if your app qualifies -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

### Notification visibility (Android 13+)

On Android 13+ the foreground service notification is hidden from the
notification drawer unless your app holds `POST_NOTIFICATIONS`.  The service
still runs.  Declaring and requesting that permission is up to your app.

---

## Quick start

```tsx
import FloatingBubble from '@increase21/rn-floating-bubble';

// 1. Request the overlay permission
const granted = await FloatingBubble.requestPermission();

if (granted) {
  // 2. Show the bubble — uses your app's launcher icon by default
  await FloatingBubble.show({
    notificationTitle: 'My App',
    notificationText: 'Tap to return to My App',
  });
}

// 3. Hide it when you're done
await FloatingBubble.hide();
```

---

## API

### Bubble

#### `FloatingBubble.show(options?)`

Shows the floating bubble, replacing any bubble already shown.

| Option | Type | Default | Description |
|---|---|---|---|
| `size` | `number` | `60` | Bubble diameter in dp |
| `color` | `number` | `0xFF4285F4` | ARGB fill colour used by the colour-circle fallback |
| `icon` | `string` | — | Base64-encoded PNG/JPG; overrides `useLauncherIcon` |
| `useLauncherIcon` | `boolean` | `true`* | Use the app's launcher icon as the bubble image |
| `initialX` | `number` | `0` | Initial X position in screen pixels |
| `initialY` | `number` | `100` | Initial Y position in screen pixels |
| `enableDismiss` | `boolean` | `false` | Show a ✕ close zone while dragging; drag onto it to remove the bubble |
| `foregroundService` | `boolean` | `true` | Run in the library's foreground service (Option A). `false` = no service (Option B) |
| `notificationTitle` | `string` | string resource | Foreground service notification title |
| `notificationText` | `string` | string resource | Foreground service notification text |
| `notificationIcon` | `string` | library icon | Name of a drawable/mipmap in your app to use as the notification's small icon |

\* `useLauncherIcon` defaults to `true` when no `icon` is provided, and to
`false` when an `icon` is provided.

**Icon resolution order (first match wins):**

1. `icon` (base64) — developer-supplied custom image, clipped to a circle
2. Launcher icon — `useLauncherIcon: true`, clipped to a circle
3. Colour circle — always-available fallback using `color`

Rejects with `PERMISSION_DENIED` if the overlay permission has not been granted
yet, or with a [setup error](#error-codes) if something is not declared.

Returns `Promise<void>`.

#### `FloatingBubble.hide()`

Removes the bubble and stops its foreground service, if any.

Returns `Promise<void>`.

#### `FloatingBubble.isShowing()`

Returns `Promise<boolean>` — `true` if the bubble is shown (in either mode).

#### `FloatingBubble.updateNotification({ title?, text? })`

Changes the foreground service notification while the bubble is showing.
Omitted fields keep their current value.  Rejects with `NOT_RUNNING` for
bubbles shown with `foregroundService: false`.

```tsx
await FloatingBubble.updateNotification({ text: '3 trips nearby' });
```

Returns `Promise<void>`.

#### Default notification text

Without `notificationTitle` / `notificationText`, the notification uses string
resources your app can override in `android/app/src/main/res/values/strings.xml`:

```xml
<string name="floating_bubble_notification_title">My App</string>
<string name="floating_bubble_notification_text">Tap to open the app</string>
<string name="floating_bubble_channel_name">Floating Bubble</string>
<string name="floating_bubble_channel_desc">Shows while the floating bubble is active</string>
```

The channel name and description are only settable this way.

---

### Overlay permission

#### `FloatingBubble.hasPermission()`

Returns `Promise<boolean>` — `true` if `SYSTEM_ALERT_WINDOW` is granted.

#### `FloatingBubble.requestPermission()`

Opens the **"Display over other apps"** system settings page.  Resolves with
`true`/`false` once the user navigates back to the app.

Returns `Promise<boolean>`.

---

### App and screen

#### `FloatingBubble.bringAppToForeground()`

Brings the app's existing task to the front, or launches its main activity.

Android 10+ blocks apps from opening activities from the background.  Holding
the overlay permission is an exemption; on Android 15+ the exemption also needs
a visible overlay window, such as the bubble.  When no exemption applies,
Android silently ignores the call.

Returns `Promise<void>`.

#### `FloatingBubble.wakeScreen(durationMs = 3000)`

Turns the screen on for `durationMs` using a wake lock.  Requires `WAKE_LOCK`.

Best effort: newer Android versions may ignore it.  To reliably show the app
on a locked device, use `enableLockScreenUI()` + `bringAppToForeground()`.

Returns `Promise<void>`.

#### `FloatingBubble.enableLockScreenUI({ dismissKeyguard? })`

Lets the app's activity show over the lock screen and turn the screen on when
it comes to the front.  With `dismissKeyguard: true` it also asks the system
to dismiss the keyguard (the user may still need to enter their PIN).

Stays in effect until `disableLockScreenUI()` or until the activity is
destroyed — **call `disableLockScreenUI()` when you're done** so your app does
not keep appearing over the lock screen.

Returns `Promise<void>`.

#### `FloatingBubble.disableLockScreenUI()`

Undoes `enableLockScreenUI()`.

Returns `Promise<void>`.

---

### Battery optimization

#### `FloatingBubble.isIgnoringBatteryOptimizations()`

Returns `Promise<boolean>` — `true` if the app is exempt (always `true` below
Android 6).

#### `FloatingBubble.requestIgnoreBatteryOptimizations()`

Shows the system **"Allow app to run in background?"** dialog.  Resolves with
`true`/`false` once the user returns.  Requires
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which Google Play only allows for
qualifying apps.

Returns `Promise<boolean>`.

#### `FloatingBubble.openBatteryOptimizationSettings()`

Opens the system list of battery-optimized apps.  Needs no permission.

Returns `Promise<void>`.

#### `FloatingBubble.openManufacturerBatterySettings()`

Xiaomi, Huawei, Oppo, Vivo, Samsung, OnePlus and Asus add their own background
restrictions (autostart, "sleeping apps", …) that the standard exemption does
not cover.  This opens the maker's screen when it can find one and resolves
`true`; otherwise it opens the standard list and resolves `false`.

These screens are not public APIs and change between OS versions, so treat
this as best effort.  See [dontkillmyapp.com](https://dontkillmyapp.com) for
per-device instructions.

Returns `Promise<boolean>`.

---

### Error codes

Rejected promises carry a `code`:

| Code | Meaning |
|---|---|
| `PERMISSION_NOT_DECLARED` | A permission the feature needs is missing from your manifest; the message names it |
| `SERVICE_NOT_DECLARED` | `FloatingBubbleService` is not declared — add it (Option A) or pass `foregroundService: false` |
| `SERVICE_TYPE_MISSING` | Android 14+: `FloatingBubbleService` has no `android:foregroundServiceType` |
| `PERMISSION_DENIED` | The overlay permission is declared but the user has not granted it |
| `START_FAILED` / `SHOW_FAILED` | Android refused to start the service / add the overlay |
| `NOT_RUNNING` | `updateNotification()` called without the bubble's foreground service running |
| `NO_ACTIVITY` | The method needs the app's activity, and there is none |
| `NO_LAUNCH_INTENT` | The app has no launcher activity to bring to the front |
| `REQUEST_IN_PROGRESS` | Another permission / battery request is still waiting for the user |
| `SETTINGS_UNAVAILABLE` | The system settings screen could not be opened |
| `WAKE_FAILED` / `LOCK_SCREEN_UI_FAILED` | The system call failed; see the message |
| `DESTROYED` | The activity was destroyed while a request was pending |

The `FloatingBubbleErrorCode` type lists them for TypeScript.

---

## Usage examples

### Default — launcher icon (no configuration needed)

```tsx
await FloatingBubble.show(); // uses app launcher icon automatically
```

### Force colour circle (no icon)

```tsx
await FloatingBubble.show({
  useLauncherIcon: false,
  color: 0xFF34A853, // green
});
```

### Drag-to-dismiss

Enable the ✕ close zone so users can remove the bubble themselves without needing a button in your UI. When `enableDismiss` is `true`, a semi-transparent close zone appears at the bottom of the screen as soon as the user starts dragging. Dragging the bubble onto it snaps it in place — releasing removes the bubble (and stops its service, if any).

```tsx
await FloatingBubble.show({
  enableDismiss: true,
});
```

This matches the standard Android chat-head dismiss pattern (as seen in Facebook Messenger and similar apps).

### Custom base64 image

```tsx
// Read any PNG/JPG and encode it to base64
import RNFS from 'react-native-fs';

const base64 = await RNFS.readFile('/path/to/icon.png', 'base64');
await FloatingBubble.show({ icon: base64, size: 72 });
```

### Custom notification

```tsx
await FloatingBubble.show({
  notificationTitle: 'My App Driver',
  notificationText: "You're online — tap to return",
  notificationIcon: 'ic_stat_notification', // res/drawable/ic_stat_notification.xml in your app
});

// Later, while the bubble is showing
await FloatingBubble.updateNotification({ text: '3 trips nearby' });
```

### Show the app over the lock screen (e.g. an incoming job)

```tsx
await FloatingBubble.enableLockScreenUI();
await FloatingBubble.bringAppToForeground();
FloatingBubble.wakeScreen().catch(() => {}); // best effort

// once the job is handled
await FloatingBubble.disableLockScreenUI();
```

### Ask to be exempt from battery optimization

```tsx
if (!(await FloatingBubble.isIgnoringBatteryOptimizations())) {
  // explain why to the user first, then:
  const exempt = await FloatingBubble.requestIgnoreBatteryOptimizations();
  if (!exempt) {
    await FloatingBubble.openManufacturerBatterySettings();
  }
}
```

### Full permission + toggle flow

```tsx
import React, { useCallback, useEffect, useState } from 'react';
import { AppState } from 'react-native';
import FloatingBubble from '@increase21/rn-floating-bubble';

export function useBubble() {
  const [hasPermission, setHasPermission] = useState(false);
  const [isShowing, setIsShowing]         = useState(false);

  const refresh = useCallback(async () => {
    setHasPermission(await FloatingBubble.hasPermission());
    setIsShowing(await FloatingBubble.isShowing());
  }, []);

  useEffect(() => {
    refresh();
    const sub = AppState.addEventListener('change', s => {
      if (s === 'active') refresh();
    });
    return () => sub.remove();
  }, [refresh]);

  const show = async () => {
    if (!hasPermission) {
      const granted = await FloatingBubble.requestPermission();
      if (!granted) return;
    }
    await FloatingBubble.show({ size: 60 });
    setIsShowing(true);
  };

  const hide = async () => {
    await FloatingBubble.hide();
    setIsShowing(false);
  };

  return { hasPermission, isShowing, show, hide };
}
```

---

## Migrating from 1.x

2.0 stops declaring permissions and the service in the library's manifest, so
upgrading without changes makes `show()` reject with `PERMISSION_NOT_DECLARED`
or `SERVICE_NOT_DECLARED`.  To keep 1.x behaviour, add the
[bubble permission](#bubble-always-needed) and [Option A](#option-a--the-bubbles-own-foreground-service-default)
to your app's manifest.  No JavaScript changes are needed.

---

## How it works

1. `show()` starts **`FloatingBubbleService`** as a foreground service with a
   persistent low-priority notification — or, with `foregroundService: false`,
   adds the bubble directly from the application context.
2. **`BubbleController`** uses **`WindowManager`** with `TYPE_APPLICATION_OVERLAY`
   to place a draggable `ImageView` on top of all other apps.
3. **Icon**: reads the base64 option, falls back to `PackageManager.getApplicationIcon()`,
   then falls back to a coloured circle.  Every bitmap is clipped to a circle via a
   `PorterDuff.Mode.SRC_IN` canvas operation before display.
4. **Drag**: `ACTION_MOVE` repositions the view live through
   `WindowManager.updateViewLayout`.
5. **Snap**: `ACTION_UP` triggers a `ValueAnimator` + `OvershootInterpolator`
   that slides the bubble to the nearest horizontal edge.
6. **Tap**: `getLaunchIntentForPackage` + `FLAG_ACTIVITY_REORDER_TO_FRONT`
   brings the host app to the foreground instantly.
7. **Dismiss zone**: when `enableDismiss: true`, a second `WindowManager` overlay (a dark circle with a ✕) is added at the bottom-centre of the screen. It fades in the moment a drag is detected. `isOverCloseZone()` uses Euclidean distance to check proximity; the bubble snaps magnetically once inside the threshold. Releasing over the zone removes the bubble and stops the service.
8. **Setup checks**: before using a feature, the module reads your app's
   declared permissions (`PackageManager.GET_PERMISSIONS`) and, for the service,
   its `ServiceInfo` and foreground service types, rejecting with a clear error
   if anything is missing.
9. **`hide()`** stops the service, whose `onDestroy` removes all overlay views.

---

## Running the example

```sh
cd example
yarn install
yarn android
```
---
## License

MIT
