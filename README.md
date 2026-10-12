# Text AutoClick

Android auto-clicker triggered by on-screen text detection.

- **Jetpack Compose** UI (grey/white theme)
- **AccessibilityService** for tap simulation (`dispatchGesture`)
- **MediaProjection** screen capture (0.5x downscaled frames)
- **ML Kit Text Recognition** (on-device, latin)
- **Foreground overlay service** with a floating panel that starts with a logo,
  minimizes to a small floating bubble, and can never be dismissed — only minimized
  (stop it from the persistent notification's "Stop" action)

## Setup

1. Install the APK
2. Grant **Display over other apps**
3. Enable **Text AutoClick** in Accessibility settings
4. Tap **Start** and accept the screen-capture dialog
5. In the floating panel, type the text to watch (e.g. `OK`, `CLAIM`) and press **Start**
6. The app taps the center of the first text block containing the keyword (2s cooldown)

## Build

```
./gradlew assembleDebug
```

CI: `.github/workflows/build.yml` builds the debug APK on every push to `main`.

## License

MIT
