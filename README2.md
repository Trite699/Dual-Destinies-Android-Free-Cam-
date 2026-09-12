# Freecam Loader

A Frida-based mod loader for adding a free/manipulable camera to
*Phoenix Wright: Ace Attorney – Dual Destinies* (Android). This repo
contains **no game files** — it's tooling only. You need your own
legally-owned copy of the game installed on your device.

## How it works

- `app/` — a small Android (Kotlin) app that uses **Shizuku** to run
  `frida-inject` (Frida's own standalone injector) against the running
  game process, then shows a floating joystick overlay.
- `app/src/main/assets/hook.js` — the actual Frida script. It hooks
  `sAppCamera::changeCamera(uCamera*)` to capture a live pointer to
  whatever camera is currently active, then calls the engine's own
  `uCamera::applyWorldOffset()` on it every frame, driven by deltas
  streamed from the overlay over a local TCP socket (127.0.0.1:27042).
- `.github/workflows/build.yml` — builds the debug APK on every push
  and uploads it as a workflow artifact (Actions tab → latest run →
  Artifacts). No GitHub Pages, no releases — just CI builds.

## Requirements

- **Shizuku** installed and running on the device:
  - Rooted: launch Shizuku from root (its app has a one-tap "start"
    option when it detects root).
  - Non-rooted: pair Shizuku over ADB (`adb shell sh
    /sdcard/Android/data/moe.shizuku.privileged.api/.../start.sh`, or
    use wireless debugging pairing on Android 11+ — see Shizuku's own
    docs, this project doesn't reimplement that).
- Android 8.0+ (minSdk 26), arm64 device.
- Overlay ("draw over other apps") permission, granted in-app.

## Using it

1. Install the APK from the latest Actions run artifact.
2. Launch the game, let it fully load into any scene.
3. Open Freecam Loader → **Check Root** (checks/grants Shizuku
   permission) → **Overlay Perm** → **Inject**.
4. Watch the log for `attached` from frida-inject and `ready` /
   `active camera changed` from hook.js. The camera pointer is only
   captured once the game itself triggers a camera change (e.g. moving
   between an investigation scene and a close-up) — trigger one if the
   log doesn't show it within a few seconds.
5. Tap **Start Overlay** and use the floating pad to nudge the camera.

## Known limitations / next steps

- This offsets whatever camera the game currently has active rather
  than swapping in a dedicated always-free `uFreeCamera` instance (the
  engine ships one, but its exact `sizeof()` isn't confirmed yet, and
  guessing risks heap corruption). Confirming that and constructing a
  real `uFreeCamera` is the natural v2.
- No rotation/zoom yet, only positional offset — `applyWorldOffset`'s
  second parameter (rotation) is currently always zeroed.
- Target process name in the app defaults to a placeholder package
  guess; verify yours with `adb shell pidof <package>` and edit the
  field if it differs.
- `frida-inject` attaches to an already-running process rather than
  spawning it, to avoid racing any startup-time integrity checks —
  make sure the game is already open before hitting Inject.
