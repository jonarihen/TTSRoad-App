# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Single-module Android client (Kotlin, Compose, Media3) for a private, self-hosted TTSRoad
FastAPI backend that converts web fiction to TTS audio. Installed by sideloading on one phone;
no Play Store. Android Auto is a first-class surface, not an add-on.

`build.md` is the contract with the backend: every `/api/mobile/...` endpoint, its JSON shape,
and the audio/bearer-auth rules. Read it before touching `data/` or anything that talks to the
server. `CHANGELOG.md` records what shipped in each version.

## Commands

```bash
./gradlew assembleDebug                                   # debug APK
./gradlew assembleRelease                                 # signed release APK
./gradlew lint                                            # Android Lint
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JVM unit tests live in `app/src/test` and run with `./gradlew test`. There is no
`app/src/androidTest` — nothing is exercised on a device or emulator.

Builds need a JDK and the Android SDK, normally provisioned by Android Studio; the Gradle daemon
toolchain is pinned to JDK 21 (`gradle/gradle-daemon-jvm.properties`) while compilation targets
JVM 17. On a machine with no `java` on PATH, Gradle cannot run at all — verify changes by reading,
not by building, and say so.

Release shrinking is deliberately disabled. Version 0.7.0 was the first minified APK and crashed
at startup because R8 renamed a Moshi-reflected model outside `data/`. Do not re-enable shrinking
until there is an on-device startup smoke test; keep every reflectively serialized model explicit
in `proguard-rules.pro`.

Emulator hitting a backend on the same host: `http://10.0.2.2:8000`. Cleartext HTTP is enabled in
the debug build only (`app/src/debug/`).

## Architecture

**Manual DI.** `core/ServiceLocator` holds the process-wide singletons (`TokenStore`,
`TtsRoadRepository`, `PlaybackController`, `PlaybackHistoryStore`, `UpdateManager`), created in
`TtsRoadApplication`. There is no Hilt/Koin. Both the UI and the media service pull from it, which
is what lets them share one repository and one token store.

**The player lives in the service, never in the UI.** `media/TtsRoadMediaService`
(a `MediaLibraryService`) owns the single `ExoPlayer`. `player/PlaybackController` is a thin
`MediaController` wrapper that connects to it and publishes `PlayerUiState` as a `StateFlow`
(1s ticker + `Player.Listener`). Compose only ever drives playback through that controller — do not
instantiate a player in the UI layer. Consequence: OS media controls, the notification, audio focus,
and Android Auto all act on the same session and queue.

**Auth has three paths.**
- API calls: `TtsRoadRepository` keeps *one* `OkHttpClient` with an interceptor reading a volatile
  `authHeader`, plus a per-base-URL Retrofit cache. Building a client per call was a real bug
  (fresh TLS handshake on every 15s progress save) — keep the sharing.
- Audio streaming: `ResolvingDataSource` in the service injects `Authorization` per request from the
  latest session token, so login/logout does not require recreating the player.
- Cover images: Coil uses a dedicated client that injects `Authorization` only when the request
  origin exactly matches the signed-in TTSRoad server. Royal Road/CDN URLs must remain on their
  original host and must never receive the bearer token. Use `ServerUrls.resolveCoverOrNull` for
  artwork; `rewriteHost` is only for known server-owned media such as audio.

**MediaItem contract** (`media/TtsRoadMediaItems`) — the glue between screens, the car, and the service:
- media id `chapter:<chapter_id>` / `fiction:<fiction_id>`; browse roots `root`, `continue`,
  `fictions`, `recent`.
- extras carry `fiction_id`, `chapter_id`, `display_number`, `position_seconds`. Progress sync and
  queue expansion read these — an item without them plays but never saves progress.
- The URI must also be set as `requestMetadata.mediaUri`. Controllers hand items back across the
  binder with the playback URI stripped; `BrowserCallback.restoreItem` puts it back.
- Audio URLs from the server are rewritten to the host the user actually logged in to
  (`rewriteHost`), because the backend builds them from its own configured `BASE_URL`.

**Single-chapter selection expands to the whole fiction.** `onSetMediaItems` sees a one-item set
(a tap in the Android Auto browse tree), fetches `chapters?playable_only=true`, and returns the full
fiction queue positioned at that chapter — that is what gives the car next/previous and auto-advance.
The in-app player already sends a multi-item queue via `PlaybackController.playQueue`, which passes
straight through. `onPlaybackResumption` starts the newest "continue listening" item.

**Progress sync happens only in the service** (`saveCurrentProgress`): on a 15s tick while playing,
on pause, and at `STATE_ENDED`. `is_played` is set when past 96% or within the last 20s. The UI
never posts progress itself.

**Jump back** (`player/PlaybackHistoryStore`): the same `saveCurrentProgress` call records a
wall-clock → chapter+position snapshot, capped at 2000 entries (~8h) and persisted to
`filesDir/playback_history.json`. The player's jump-back sheet either seeks inside the loaded queue
or, if the queue was cleared overnight, reloads the fiction from `fiction_id`/`chapter_id` and
resumes at the historical position.

**UI is one file.** `MainActivity.kt` (~2k lines) holds every screen as a private composable.
Navigation is a hand-rolled `AppScreen` sealed interface in `remember { mutableStateOf }` — the
`navigation-compose` dependency is present but unused. Per-screen loading uses the local
`LoadState<T>` (Loading/Loaded/Error) with a local `refresh()`; there are no ViewModels.

**Theme.** `ui/TtsRoadTheme` ports the web console's AARIS design language: dark, zero corner
radius everywhere, orange accent, mono uppercase labels. Use `AarisColor`, `MetaText`, `AarisTag`,
`AarisCard`, `ThinProgress` and `shape = RectangleShape` on buttons rather than Material defaults,
or the new UI will not match.

**Self-updater.** `update/UpdateManager` polls GitHub Releases of `jonarihen/TTSRoad-App` once per
launch, downloads the attached APK to `cacheDir`, and hands it to the package installer via the
FileProvider. Updates only apply in place if the release APK is signed with the same key as the
installed build.

**Signing is pinned.** Both build types use the ignored repository-root `debug.keystore`, whose
file checksum is enforced in `app/build.gradle.kts` and recorded in `debug.keystore.sha256`.
Never generate or substitute another key. Restore a lost local file from the protected offline
backup and confirm it with `sha256sum --check debug.keystore.sha256` before building.

## Conventions

- DTOs are Moshi data classes in `data/MobileModels.kt` using reflection
  (`KotlinJsonAdapterFactory`) with `@param:Json` for snake_case. R8 keeps `data.**`
  (`proguard-rules.pro`) — new DTOs must live in that package or release builds will break.
- Backend fields are inconsistent between endpoints, so `ChapterSummary` exposes `resolved*`
  properties (`resolvedChapterId`, `resolvedPositionSeconds`, …). Use those, not the raw fields.
- Release flow: bump `versionCode` + `versionName` in `app/build.gradle.kts`, turn the Unreleased
  changelog section into `## X.Y.Z — YYYY-MM-DD`, run `./gradlew clean test lint assembleRelease`,
  verify `app-release.apk` with `apksigner`, commit with the version in the subject, tag `vX.Y.Z`,
  push the commit and tag, and publish a GitHub release with the signed APK attached. The attached
  APK is what the in-app updater installs.
