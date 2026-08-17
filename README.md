<div align="center">

# TTSRoad for Android

**Web fiction, narrated. In your pocket and in your car.**

A native Android client for a private, self-hosted [TTSRoad](https://github.com/jonarihen/TTSRoad)
server — the backend that turns web serials into text-to-speech audiobooks.

[![CI](https://github.com/jonarihen/TTSRoad-App/actions/workflows/ci.yml/badge.svg)](https://github.com/jonarihen/TTSRoad-App/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/jonarihen/TTSRoad-App?label=release&color=e8590c)](https://github.com/jonarihen/TTSRoad-App/releases/latest)
[![Tests](https://img.shields.io/badge/tests-662-2f9e44)](#testing)
[![License](https://img.shields.io/badge/license-AGPL--3.0-4c6ef5)](LICENSE)

![Kotlin](https://img.shields.io/badge/Kotlin-2.3.10-7f52ff?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-2026.06.00-4285f4?logo=jetpackcompose&logoColor=white)
![Media3](https://img.shields.io/badge/Media3-1.10.0-ff6f00?logo=android&logoColor=white)
![Android Auto](https://img.shields.io/badge/Android%20Auto-first%20class-34a853?logo=android&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-26-555)

</div>

---

> [!NOTE]
> **This is a personal client for a private server.** It is sideloaded onto one phone, has no
> Play Store listing, and is useless without a TTSRoad backend to sign in to. It is published
> openly because the code may be useful, not because it is a product.

## Why it exists

A self-hosted TTS server is wonderful at home and awkward everywhere else. The listening happens
on a commute, on a walk, in a car, and half of that is somewhere with no signal. So the design
priorities here are not the usual ones:

- **The car is a first-class surface, not an add-on.** Android Auto browses the real library, with
  progress, voice search, and its own Up Next node.
- **Offline is a state to arrive in, not to prepare for.** The app can keep the next few chapters
  on the phone as you listen, so a tunnel is not the end of the chapter.
- **Nothing needs the phone unlocked.** Playback starts from the car and the lock screen, and
  bookmarks can be made from there too.

## Features

### Listening

| | |
|---|---|
| **Full Media3 playback** | One `ExoPlayer` in a `MediaLibraryService`, so OS media controls, the notification, audio focus and the car all drive the same session and queue. |
| **Android Auto** | Browse roots for continue-listening, fictions and recents; voice search (*"play Ashes of Aether on TTSRoad"*); a cross-library Up Next node. |
| **Speed & tuning** | 0.5×–3.0× in nine presets, skip silence, and volume boost for quiet narration. |
| **Configurable skips** | 10/15/30/45/60s, mirrored onto the lock screen, notification and car transport. |
| **Sleep timer** | Durations, end-of-chapter, and a fade-out — plus **shake to extend**, so a nudge buys more time without finding the screen. |
| **Jump back** | A 2000-entry wall-clock trail (~8 hours). *"Where was I at 23:49?"* has an answer, even if the queue was cleared overnight. |

### Reading

| | |
|---|---|
| **Read-along reader** | The chapter's text, highlighted in step with the audio via a binary-search cue engine, driven by the reported playback position rather than a clock — so skip-silence never drifts it. |
| **Follows the audio** | Reach the end of a chapter and the reader moves on with it. Open it on some *other* chapter and it stays exactly where you put it. |
| **Tap to seek** | Tap a line to jump the audio there. |
| **Appearance** | Text size, page colour and highlight style, synced to your account so the browser looks the same. |

### Library & sync

| | |
|---|---|
| **Offline downloads** | Per-chapter or whole-fiction, with a foreground service, Wi-Fi-only by default, and a storage readout. |
| **Keep chapters ahead** | Optionally keep the next 3/5/10/20 chapters downloaded as you listen. It cleans up behind you and never touches what you downloaded yourself. |
| **Bookmarks** | The same records the web client shows — including from the car and the notification. |
| **Find a chapter** | Filter a several-hundred-chapter serial by title or number, on the fiction screen and in the player's queue. |
| **Server search** | Fiction titles, chapter titles and the narration text itself, alongside the instant offline filter. |
| **Follow / unfollow** | Your shelf is yours, and it agrees with the browser's. |
| **Resilient progress** | Positions recorded offline are queued and timestamped, so a reconnect never overwrites newer progress from another device. |

### Care taken

| | |
|---|---|
| **Token encrypted at rest** | AES-256-GCM under a non-extractable Android Keystore key. Deliberately *not* bound to device unlock — the car needs to stream with the phone locked. |
| **Bearer token scoping** | Cover images get the token only when the origin matches your signed-in server. Royal Road and CDN URLs never see it. |
| **Crash reporting off by default** | No DSN, no SDK init, nothing leaves the device. With one configured, your server's origin is stripped from messages, stack traces, URLs and breadcrumbs first — a self-hosted address is usually a home address. |
| **Capability-gated UI** | The app asks the server what it supports and hides what it cannot honour, rather than offering buttons that 404. |
| **Self-updating** | Checks GitHub Releases once per launch and installs in place, since there is no Play Store to do it. |

## Design

The UI ports the web console's **AARIS** design language: dark, zero corner radius anywhere, an
orange accent, and mono uppercase labels. New surfaces use `AarisColor`, `MetaText`, `AarisTag`,
`AarisCard`, `ThinProgress` and `shape = RectangleShape` rather than Material defaults.

## Architecture

A single Gradle module, deliberately plain:

```
core/      ServiceLocator — manual DI, no Hilt or Koin
data/      Moshi DTOs, Retrofit API, token store + cipher, preferences
media/     TtsRoadMediaService (MediaLibraryService) — owns the only ExoPlayer
player/    PlaybackController, progress sync, history, sleep timer
download/  Media3 DownloadManager, cache keys, auto-download planning
nav/       Hand-rolled back stack (AppScreen sealed interface)
ui/        AARIS theme and reader palette
update/    GitHub Releases self-updater
```

Three decisions worth knowing before changing anything:

1. **The player lives in the service, never in the UI.** Compose drives playback only through
   `PlaybackController`, a thin `MediaController` wrapper. That is what lets the app, the
   notification and the car act on one session.
2. **Progress is saved only in the service** — on a 15s tick, on pause, and at the end. The UI
   never posts progress itself.
3. **Selecting a single chapter expands to the whole fiction.** A tap in the car's browse tree
   returns the full fiction queue positioned at that chapter, which is what gives the car
   next/previous and auto-advance.

`build.md` is the contract with the backend — every `/api/mobile/…` endpoint, its JSON shape, and
the audio and bearer-auth rules. Read it before touching `data/`.

## Getting started

### Requirements

- **Android Studio** (brings its own JDK and SDK), or a JDK plus `platform-tools`,
  `platforms;android-37.0` and `build-tools;37.0.0` via `sdkmanager`
- A running **TTSRoad** backend to sign in to
- Android **8.0+** (minSdk 26)

The Gradle daemon toolchain is pinned to **JDK 21**; compilation targets **JVM 17**. Point Gradle
at your SDK with `sdk.dir` in `local.properties`.

### Build and install

```bash
./gradlew test          # JVM unit tests — the gate
./gradlew lint          # Android Lint
./gradlew assembleDebug # debug APK

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, if `java` is not on `PATH`:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

> [!IMPORTANT]
> `assembleDebug` and `assembleRelease` both need the gitignored **`debug.keystore`** in the
> repository root. Its checksum is pinned by the build and a protected copy is kept outside the
> repo. **Never substitute a freshly generated key** — Android only accepts an update signed by the
> same one, so a new key means every install has to be uninstalled first.
>
> `test` and `lint` do not need it.

### Running against a local backend

Point the login screen at your server. For a backend on the same desktop as the emulator, use
Android's host alias:

```
http://10.0.2.2:8000
```

Cleartext HTTP is enabled in the **debug** build only. Release builds require HTTPS.

### Optional: crash reporting

Off unless a DSN is configured, which is the normal state. To point it at a self-hosted Sentry,
add to the gitignored `local.properties`:

```properties
ttsroad.sentry.dsn=https://<public-key>@sentry.example.com/<project>
```

## Testing

`app/src/test` is a real JVM source set — **662 tests** on JUnit, Robolectric and MockWebServer.
CI runs `./gradlew test lint` on every PR and every push to `main`.

```bash
./gradlew test lint
```

Two traps worth knowing:

- Robolectric tests need `@Config(sdk = [34])`. Robolectric 4.16.1 tops out at SDK 36 while the app
  targets 37, and without it the runner fails at initialisation rather than at a test.
- Prefer `SimpleBasePlayer` over a hand-written `Player` stub. Media3 derives `hasNextMediaItem`,
  `bufferedPercentage` and `currentMediaItem` from declared state and rejects impossible
  combinations — a stub will happily assert things the real player never does.

There is also an on-device smoke test in `app/src/androidTest`, but it only proves anything against
a minified build:

```bash
./gradlew connectedAndroidTest -PttsroadTestBuildType=release
```

> [!WARNING]
> **Release shrinking is deliberately disabled.** 0.7.0 was the first minified APK and crashed at
> startup because R8 renamed a Moshi-reflected model. Every reflectively serialized class stays
> explicit in `proguard-rules.pro`. To turn shrinking back on, set `isMinifyEnabled = true`, run the
> command above **on a real phone**, and only keep it on if that passes.

## Releasing

Local and manual on purpose — CI has no signing key and must not get one.

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`
2. Turn the changelog's `Unreleased` section into `## X.Y.Z — YYYY-MM-DD`
3. `./gradlew clean test lint assembleRelease`
4. Verify the certificate against the **previous release**, not just the keystore checksum —
   `apksigner verify --print-certs` on both APKs must report the same SHA-256. That match is what
   lets the in-app updater install over the existing build.
5. Commit with the version in the subject, tag `vX.Y.Z`, push both
6. Publish a GitHub release with the signed APK attached — that APK is what the updater installs

## Related

| Repository | What it is |
|---|---|
| [**TTSRoad**](https://github.com/jonarihen/TTSRoad) | The FastAPI backend and web console *(private)* |
| [**TTSRoad-Desktop**](https://github.com/jonarihen/TTSRoad-Desktop) | The desktop client |

## License

[GNU AGPL-3.0](LICENSE). [`CHANGELOG.md`](CHANGELOG.md) records what shipped in each version.
