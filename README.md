# ZenPanel

Android kiosk app for Home Assistant dashboards.

This app was built to solve one specific problem: camera/video streams that work in Firefox but fail in many WebView-only kiosk apps.

## What it does

- Runs Home Assistant dashboards in fullscreen kiosk mode
- Lets you switch browser engine:
  - GeckoView (Firefox engine)
  - Android WebView
  - Chromium-style in-app profile
- Keeps the screen awake (optional)
- Retries loading if the page fails or network drops
- Can auto-start after boot
- Has a hidden admin entry (tap top-right corner 5 times)
- Supports optional admin password before opening settings

## Settings you can change

- Home Assistant base URL
- Dashboard path
- Auto-append `?kiosk`
- Reload interval
- Fullscreen on/off
- Keep screen on on/off
- Auto-start on boot on/off
- Browser engine

Default dashboard URL:

`http://192.168.0.231:8123/dashboard-tablet/panel?kiosk`

## Build requirements

- Android Studio
- JDK 17
- Android SDK 36

## Run locally

1. Open the project in Android Studio.
2. Let Gradle sync.
3. Build and run the `app` module on your device.

## Install with ADB

```powershell
.\gradlew.bat assembleDebug
$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## Notes

- If you use Chromium mode, it stays inside the app (so kiosk fullscreen and admin gesture still work).
- For camera/mic streams, confirm Android runtime permissions when prompted.
