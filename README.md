# TTSRoad Android

Native Android client for a private TTSRoad server.

## Build

Open this directory in Android Studio and let it install the required JDK, Android SDK, and Gradle components.

The project is pinned to:

- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- Kotlin 2.3.10
- Compose BOM 2026.06.00

This workspace does not currently have Java, Gradle, or the Android SDK on PATH, so command-line builds could not be run here.

This environment did not have a local Gradle install, so the Gradle wrapper scripts and wrapper JAR could not be generated here. Android Studio can generate or repair the wrapper after the first sync; after that, build a personal debug APK with:

```bash
./gradlew assembleDebug
```

Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## App Scope

The first pass includes:

- Server URL and login screen
- Bearer-token storage via DataStore
- Mobile API client for login, library, chapter list, playback progress, mark played, and logout
- Compose library, fiction detail, player, and settings screens
- Media3/ExoPlayer streaming with bearer auth headers
- Playback progress sync during playback, pause, and completion
- Android Auto media browse service with root, continue listening, fictions, and recent nodes

