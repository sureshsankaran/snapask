# SnapAsk

Android floating-bubble screenshot assistant. Tap the floating bubble on any
screen to capture a screenshot and share it straight to the **Muse** app —
the share target is auto-selected, so no app chooser appears.

## Features

- **Floating bubble** — a small overlay bubble (your character art) that stays
  on top of other apps. Needs the "Display over other apps" permission.
- **Tap to capture** — one tap takes a screenshot and opens it in Muse with
  the image attached. Ask your question, press Back to return to what you
  were doing.
- **Instant capture toggle** — two modes:
  - **On**: screenshots are instant, but Android shows a permanent
    screen-recording indicator while the bubble runs.
  - **Off** (default): you approve capture on every tap, and the recording
    indicator only flashes during the capture. No persistent icon.
- **Drag to move** — drag the bubble anywhere on screen.
- **Drag to close** — drag the bubble onto the red ✕ that appears at the
  bottom to stop the bubble.
- **Version stamp** — the settings screen shows the installed version
  (e.g. `SnapAsk v1.2.11 (build 14)`) so test builds are identifiable.

## Gestures

| Gesture | Action |
|---|---|
| Tap | Take a screenshot → opens in Muse |
| Drag | Move the bubble |
| Drag onto the red ✕ | Stop / close the bubble |

## Setup

1. Install the APK from the [latest release](../../releases).
2. Open **SnapAsk** and grant **Display over other apps** when asked.
3. Tap **Allow capture & start bubble** and approve screen capture.
4. The bubble appears — tap it on any screen to screenshot & ask.

The settings screen shows three status pills: overlay permission, bubble
state, and the auto-selected ask target (Muse). **App to ask** can be
changed if you ever want a different target.

## How it works

- `BubbleService` draws the floating bubble (overlay) and runs the capture
  pipeline via `MediaProjection` as a foreground service.
- **Instant capture on**: a persistent `MediaProjection` + `ImageReader`
  grabs the frame directly — no per-tap permission prompt.
- **Instant capture off**: each tap launches the transparent
  `CaptureGateActivity`, which requests fresh one-shot capture consent from
  the system, hands the grant to the service for a single screenshot, then
  finishes. The gate runs in its own task (`com.snapask.app.gate`) so it
  never resurfaces the settings screen.
- On capture, the PNG is shared with an explicit `ACTION_SEND` intent
  addressed directly at the Muse app (resolved via `queryIntentActivities`;
  falls back to the system chooser if Muse isn't found).

## Requirements

- Android 8.0 (API 26) or higher; targets API 34
- "Display over other apps" permission (requested on first launch)
- Muse app installed (for auto-share; otherwise the chooser appears)

## Build

With a local Android SDK (no emulator needed):

```sh
./build-manual.sh
```

The APK is written to `out/`. Or open the project in Android Studio and
build normally.

> **Versioning note:** `build-manual.sh` compiles with `aapt2`/`kotlinc`/`d8`
> directly and does **not** read `build.gradle.kts`. The version that ships
> is the one in `app/src/main/AndroidManifest.xml`
> (`android:versionCode` / `android:versionName`) — bump it there, and verify
> with `aapt2 dump badging out/snapask-debug.apk`.

## Changelog

- **v1.2.12** — notification icons: the status-bar glyph is now a custom
  Muse-mascot silhouette (fluffy headphone-wearing creature, traced from the
  actual character art — Android forces status-bar icons to monochrome, so a
  clean redraw reads better than an auto-trace of the photo); the pulled-down
  notification shows the full-color Muse mascot photo as its large icon.
- **v1.2.11** — version is now bumped in the manifest (the source of truth
  for `build-manual.sh`) so the in-app version stamp is accurate; removed
  the long-press-to-open-settings gesture (tap = screenshot, drag = move,
  drag-to-✕ = close).
- **v1.2.10** — fixed the per-tap consent gate landing on the app's task and
  flashing the settings screen (screenshots then captured settings instead
  of the real foreground); added the version stamp to the settings UI.
- **v1.2.9** — long-press-to-open-settings threshold 0.6s → 3s.
- **v1.2.8** — fixed taps silently doing nothing after toggling instant
  capture on→off (stale one-shot consent fingerprint discarded fresh grants;
  replaced with an in-flight guard).
- **v1.2.7** — toggling instant capture off no longer kills the bubble (the
  persistent projection's stop callback was calling `stopSelf()` on the
  intentional mode-switch stop).
- **v1.2.6** — fixed instant-on permission rejected as a duplicate consent;
  added long-press bubble → open settings; added drag bubble onto red ✕ →
  stop bubble.
- **v1.2.5** — fixed foreground crash when starting the bubble with instant
  capture on; the instant toggle now requests capture permission itself.
- **v1.2.4** — fixed one-shot capture crash on Android 14+ (MediaProjection
  foreground-service type must be claimed before `getMediaProjection`);
  settings header shows the character art.
- **v1.1** — `<queries>` declaration for `ACTION_SEND`/`image/png` so
  auto-selecting Muse works on Android 11+; bubble icon is a white camera
  glyph on Muse blue instead of an emoji.

## Releases

APKs are published as [GitHub Releases](../../releases) and also kept under
`releases/` in this repo (e.g. `releases/SnapAsk-1.2.11.apk`).
