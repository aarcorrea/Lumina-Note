# Lumina Note — Android Port

This folder contains the native Android app for Lumina Note.

## Architecture
The Android port runs the full Lumina Note React frontend inside a secure, hardware-accelerated Android WebView, backed by a native Kotlin IPC bridge:
- **Local-first File System**: Notes are stored in plain Markdown files on Android storage (`LuminaVault`), fully offline-capable.
- **Native Android Bridge**: Complete parity with desktop IPC commands (`read_file`, `save_file`, `list_workspace`, `store`, `path`, etc.).
- **Asset Loading**: Uses `WebViewAssetLoader` with domain `https://appassets.androidplatform.net` to guarantee secure origin context without CORS or file:// protocol limitations.
- **Hardware Back Navigation**: Android back gesture/button closes open drawers, modals, and panels.

## Building & Running

### Option 1: GitHub Actions (Recommended)
Push your code or trigger the **Android Build** workflow manually in GitHub Actions:
- The workflow compiles the web bundle and Android Gradle project on a high-memory runner.
- Download the generated `LuminaNote-android-debug.apk` directly from GitHub Actions artifacts and install it on your device.

### Option 2: Local Build
1. Build the web app and sync assets:
   ```bash
   npm run build:vite
   npm run android:sync
   ```
2. Build the Android APK:
   ```bash
   cd mobile/android
   ./gradlew :app:assembleDebug
   ```
   Or open `mobile/android` in Android Studio.
