# SiliconGames — Android App

A native Android wrapper for the SiliconGames arcade, featuring Slingshot and all other games. Built as a WebView app that bundles the HTML games locally for offline play.

## Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17** (bundled with Android Studio)
- **Android SDK 34** (install via SDK Manager)

## Quick Start

### 1. Open in Android Studio

```bash
# From the repo root
cd android-app
```

Open this folder as an Android Studio project. Android Studio will download Gradle and all dependencies automatically.

### 2. Sync Game Files

If you've updated any HTML game files, re-sync them before building:

```bash
./sync-games.sh
```

This copies the latest `index.html` and `games/*.html` into the Android assets folder.

### 3. Run on Device/Emulator

- Connect an Android device (USB debugging enabled) or start an emulator
- Click **Run ▶** in Android Studio
- Or from the command line:

```bash
./gradlew installDebug
```

## Building for Google Play Store

### Step 1: Create a Signing Key

You need a keystore to sign your release APK/AAB. Create one (do this ONCE and keep it safe forever):

```bash
keytool -genkey -v \
  -keystore silicongames-release.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias silicongames
```

You'll be prompted for a password and identity info.

**IMPORTANT:** Back up this keystore file and password. If you lose it, you can never update your app on the Play Store.

### Step 2: Configure Signing

Create a file called `keystore.properties` in the `android-app/` directory (it's gitignored):

```properties
storeFile=../silicongames-release.jks
storePassword=YOUR_PASSWORD
keyAlias=silicongames
keyPassword=YOUR_KEY_PASSWORD
```

Then add this to `app/build.gradle` inside the `android { }` block:

```gradle
signingConfigs {
    release {
        def props = new Properties()
        props.load(new FileInputStream(rootProject.file("keystore.properties")))
        storeFile file(props['storeFile'])
        storePassword props['storePassword']
        keyAlias props['keyAlias']
        keyPassword props['keyPassword']
    }
}

buildTypes {
    release {
        signingConfig signingConfigs.release
        // ... existing release config
    }
}
```

### Step 3: Build the Release Bundle

```bash
# Sync latest game files first
./sync-games.sh

# Build Android App Bundle (preferred for Play Store)
./gradlew bundleRelease

# Output: app/build/outputs/bundle/release/app-release.aab
```

Or build an APK instead:

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### Step 4: Upload to Google Play Console

1. Go to [Google Play Console](https://play.google.com/console)
2. Create a new app called **SiliconGames**
3. Fill in the store listing:
   - **Title:** SiliconGames
   - **Short description:** Your digital arcade — 14+ games including Slingshot, Lolo's Quest, Smash Arena, and more!
   - **Category:** Games → Arcade
   - **Content rating:** Complete the questionnaire (likely Everyone or Everyone 10+)
4. Upload screenshots (take them from the emulator)
5. Upload the `.aab` file from Step 3
6. Submit for review

## Project Structure

```
android-app/
├── app/
│   ├── build.gradle          # App-level build config
│   ├── proguard-rules.pro    # Code shrinking rules
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── index.html     # Arcade homepage
│       │   └── games/         # All game HTML files
│       ├── java/.../
│       │   └── MainActivity.kt  # WebView wrapper
│       └── res/               # Icons, themes, colors
├── build.gradle              # Root build config
├── settings.gradle
├── sync-games.sh             # Script to sync HTML files
└── README.md                 # This file
```

## Updating Games

When you add or modify games in the web version:

1. Make your changes to the HTML files in the repo root
2. Run `./sync-games.sh` to copy them into the Android assets
3. Bump `versionCode` and `versionName` in `app/build.gradle`
4. Build and upload a new release

## Technical Notes

- **Offline-first:** All games run locally from bundled assets — no internet required
- **localStorage:** WebView preserves localStorage, so game saves, likes, and high scores persist
- **Fullscreen:** The app runs in immersive fullscreen mode for maximum screen space
- **Back button:** Pressing back navigates within the WebView (e.g., from a game back to the arcade), then exits the app
- **Hardware acceleration:** Enabled for smooth Canvas 2D rendering in all games
- **Min SDK 24:** Supports Android 7.0+ (covers ~97% of active devices)
