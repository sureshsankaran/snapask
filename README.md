# SnapAsk

Android floating-bubble screenshot assistant. Tap the bubble to capture a
screenshot and share it straight to the Muse app — the share target is
auto-selected, so no app chooser appears.

## How it works

- `BubbleService` draws a floating camera bubble (overlay permission) and
  runs the capture pipeline via `MediaProjection` as a foreground service.
- On capture, the PNG is shared with an explicit `ACTION_SEND` intent
  addressed directly at the Muse app.

## Requirements

- Android 8.0 (API 26) or higher
- "Display over other apps" permission (requested on first launch)

## Build

With a local Android SDK (no emulator needed):

```sh
./build-manual.sh
```

The APK is written to `out/`. Or open the project in Android Studio and
build normally.

## Notes

- v1.1: added a `<queries>` declaration for `ACTION_SEND` / `image/png` in
  the manifest. Without it, Android 11+ hides other apps' share handlers
  from `queryIntentActivities()`, which made the auto-select silently fail
  and the chooser appear on every tap.
- v1.1: bubble icon is now a white vector camera glyph on Muse blue
  (`#2563EB`) instead of an emoji.
