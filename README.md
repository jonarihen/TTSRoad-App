# TTSRoad Android

Native Android client for a private TTSRoad server.

## Build

Open this directory in Android Studio and let it install the required JDK, Android SDK, and Gradle components.

The project is pinned to:

- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- Kotlin 2.3.10
- Compose BOM 2026.06.00

Android Studio includes the JDK and SDK needed for local builds. If building from PowerShell and `java` is not on PATH, set `JAVA_HOME` first:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
```

Build a personal debug APK with:

```powershell
.\gradlew.bat assembleDebug
```

Installable APK builds require the ignored `debug.keystore` in the repository root. Its checksum
is pinned by the build and a protected copy is kept outside the repository. Do not replace it with
a newly generated key: Android only accepts updates signed by the same key.

Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Test On Desktop Emulator

This machine needs Android Studio, the Android SDK, and emulator support before it can run the app locally. On Windows, Android Studio can be installed with:

```powershell
winget install --id Google.AndroidStudio -e --accept-package-agreements --accept-source-agreements
```

After Android Studio is installed:

1. Open this repository folder in Android Studio.
2. Let Gradle sync download the Android Gradle plugin, Kotlin, Compose, Media3, and SDK packages.
3. Open Device Manager and create a Pixel virtual device with a Google APIs system image.
4. Run the `app` configuration on the emulator.
5. On the login screen, enter the TTSRoad backend URL.

For a backend running on the same desktop as the emulator, Android's emulator host alias is:

```text
http://10.0.2.2:8000
```

Debug builds allow cleartext HTTP for local testing. Release builds should use HTTPS.

## App Scope

The first pass includes:

- Server URL and login screen
- Bearer-token storage via DataStore
- Mobile API client for login, library, chapter list, playback progress, mark played, and logout
- Compose library, fiction detail, player, and settings screens
- Media3/ExoPlayer streaming with bearer auth headers
- Playback progress sync during playback, pause, and completion
- Android Auto media browse service with root, continue listening, fictions, and recent nodes
