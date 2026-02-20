# @increase21/rn-floating-bubble

A React Native library for Android that shows a draggable, tappable floating
bubble (chat-head style) that sits on top of all other apps.  Tapping the
bubble brings your host application back to the foreground.

> **iOS note** — iOS does not permit apps to draw over the system UI outside of
> their own window, so this library is **Android-only**.

---

## Features

- Draggable bubble that floats over the home screen and every other app
- Spring-snaps to the nearest screen edge when released
- Tap to instantly reopen the host app
- **Automatic launcher-icon support** — uses your app's own icon by default
- Custom base64 image icon as an alternative
- Plain colour circle as the final fallback
- Handles the `SYSTEM_ALERT_WINDOW` permission request flow
- Foreground service keeps the bubble alive even when the app is killed

---

## Installation

```sh
npm install @increase21/rn-floating-bubble
# or
yarn add @increase21/rn-floating-bubble
```

React Native ≥ 0.60 auto-links the native module — no manual linking needed.

### Android manifest

All required permissions are declared in the library's own `AndroidManifest.xml`
and merged automatically.  You do **not** need to add anything to your app
manifest.  Permissions merged automatically:

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

---

## Quick start

```tsx
import FloatingBubble from '@increase21/rn-floating-bubble';

// 1. Request the overlay permission
const granted = await FloatingBubble.requestPermission();

if (granted) {
  // 2. Show the bubble — uses your app's launcher icon by default
  await FloatingBubble.show();
}

// 3. Hide it when you're done
await FloatingBubble.hide();
```

---

## API

### `FloatingBubble.show(options?)`

Shows the floating bubble.  Rejects with `PERMISSION_DENIED` if the overlay
permission has not been granted yet.

| Option | Type | Default | Description |
|---|---|---|---|
| `size` | `number` | `60` | Bubble diameter in dp |
| `color` | `number` | `0xFF4285F4` | ARGB fill colour used by the colour-circle fallback |
| `icon` | `string` | — | Base64-encoded PNG/JPG; overrides `useLauncherIcon` |
| `useLauncherIcon` | `boolean` | `true`* | Use the app's launcher icon as the bubble image |
| `initialX` | `number` | `0` | Initial X position in screen pixels |
| `initialY` | `number` | `100` | Initial Y position in screen pixels |

\* `useLauncherIcon` defaults to `true` when no `icon` is provided, and to
`false` when an `icon` is provided.

**Icon resolution order (first match wins):**

1. `icon` (base64) — developer-supplied custom image, clipped to a circle
2. Launcher icon — `useLauncherIcon: true`, clipped to a circle
3. Colour circle — always-available fallback using `color`

Returns `Promise<void>`.

---

### `FloatingBubble.hide()`

Stops the foreground service and removes the bubble.

Returns `Promise<void>`.

---

### `FloatingBubble.isShowing()`

Returns `Promise<boolean>` — `true` if the bubble service is running.

---

### `FloatingBubble.hasPermission()`

Returns `Promise<boolean>` — `true` if `SYSTEM_ALERT_WINDOW` is granted.

---

### `FloatingBubble.requestPermission()`

Opens the **"Display over other apps"** system settings page.  Resolves with
`true`/`false` once the user navigates back to the app.

Returns `Promise<boolean>`.

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

### Custom base64 image

```tsx
// Read any PNG/JPG and encode it to base64
import RNFS from 'react-native-fs';

const base64 = await RNFS.readFile('/path/to/icon.png', 'base64');
await FloatingBubble.show({ icon: base64, size: 72 });
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

## How it works

1. `show()` starts **`FloatingBubbleService`** as a foreground service with a
   persistent low-priority notification (required by Android 8+).
2. The service uses **`WindowManager`** with `TYPE_APPLICATION_OVERLAY` to place
   a draggable `ImageView` on top of all other apps.
3. **Icon**: reads the base64 option, falls back to `PackageManager.getApplicationIcon()`,
   then falls back to a coloured circle.  Every bitmap is clipped to a circle via a
   `PorterDuff.Mode.SRC_IN` canvas operation before display.
4. **Drag**: `ACTION_MOVE` repositions the view live through
   `WindowManager.updateViewLayout`.
5. **Snap**: `ACTION_UP` triggers a `ValueAnimator` + `OvershootInterpolator`
   that slides the bubble to the nearest horizontal edge.
6. **Tap**: `getLaunchIntentForPackage` + `FLAG_ACTIVITY_REORDER_TO_FRONT`
   brings the host app to the foreground instantly.
7. **`hide()`** stops the service, whose `onDestroy` removes the `ImageView`.

---

## Running the example

```sh
cd example
yarn install
yarn android
```

---

## Publishing (maintainers)

```sh
# In the library root:
yarn build       # compiles src/ → lib/
yarn typecheck   # sanity-check the types

npm version patch   # or minor / major
npm publish         # runs prepublishOnly (clean + build + typecheck) first
```

`prepublishOnly` is wired in `package.json`, so `npm publish` always ships a
fresh, type-checked build.

---

## License

MIT
