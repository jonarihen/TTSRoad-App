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
./gradlew test                                            # JVM unit tests (the gate)
./gradlew lint                                            # Android Lint
./gradlew assembleDebug                                   # debug APK
./gradlew assembleRelease                                 # signed release APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`app/src/test` is a real JVM test source set — 1214 tests as of 0.13.0, JUnit + Robolectric +
MockWebServer + Compose UI test, all wired in `app/build.gradle.kts`. **Run `./gradlew test` before
claiming a change works.** `app/src/androidTest` exists but holds only the R8 startup smoke test,
which needs a device and the keystore — see below.

CI runs `./gradlew test lint` on every PR and every push to `main` (`.github/workflows/ci.yml`).
It deliberately has no `debug.keystore` — that file stays gitignored and out of CI secrets, so
releases remain local and manual. Verified that `clean test lint` both configures and runs without
it; `verifyTtsRoadSigningKey` only gates the assemble tasks. Do not add signing to CI.

Robolectric tests must carry `@Config(sdk = [34])`: Robolectric 4.16.1 tops out at SDK 36 while
this app targets 37, and without it the runner fails at initialisation rather than at a test.

**Compose layout and accessibility are testable on the JVM**, no device needed — Robolectric
supplies the `Configuration`, Compose measures against it, and the semantics tree reports the
bounds. `ui/ComposeUiTestHarnessTest` proves the harness so that a failing layout test is failing
about layout. Three things to know:

- Use `androidx.compose.ui.test.junit4.v2.createComposeRule`. The non-`v2` one is deprecated and
  runs on `UnconfinedTestDispatcher`, which hides ordering bugs the real app would have.
- State the viewport with Robolectric qualifiers — `@Config(sdk = [34], qualifiers = "w320dp-h640dp")`.
  Without them a "narrow phone" test silently runs at the default device width and asserts nothing.
- A bounds assertion measures *the semantics node it matched*. `onNodeWithText` on a glyph inside a
  48 dp button reports the glyph, so touch-target tests must match the target — give it a
  `testTag`, or match on the content description the button actually carries.

Prefer `SimpleBasePlayer` (in `media3-common`) over a hand-written `Player` stub when a test needs
a player — see `app/src/test/.../player/FakePlayer.kt`. Media3 derives `hasNextMediaItem`,
`bufferedPercentage` and `currentMediaItem` from the declared state and rejects impossible
combinations, so a stub would happily assert things the real player never does.

Builds need a JDK and the Android SDK, normally provisioned by Android Studio; the Gradle daemon
toolchain is pinned to JDK 21 (`gradle/gradle-daemon-jvm.properties`) while compilation targets
JVM 17. On a machine with no `java` on PATH, Gradle cannot run at all — verify changes by reading,
not by building, and say so.

Provisioning that toolchain by hand is not hard and is worth it: a JDK 21 tarball plus
`platform-tools`, `platforms;android-37.0` and `build-tools;37.0.0` via `sdkmanager`, then
`sdk.dir` in `local.properties`. Reading alone let a stack of four PRs reach `main`-ready with a
test file that did not compile against a rename made underneath it.

`assembleDebug`/`assembleRelease` additionally need `debug.keystore` in the repo root — both build
types are signed with it, and `verifyTtsRoadSigningKey` refuses any file whose SHA-256 is not the
pinned one. `./gradlew test` and `lint` do **not** need it.

Release shrinking is deliberately disabled. Version 0.7.0 was the first minified APK and crashed
at startup because R8 renamed a Moshi-reflected model outside `data/`. Keep every reflectively
serialized model explicit in `proguard-rules.pro` — `data.**` is kept wholesale, and
`player.HistorySnapshot`, `UpdateManager$GithubRelease` and `UpdateManager$GithubAsset` each have
their own line because they live outside that package.

The on-device smoke test that guards this now exists (`app/src/androidTest`), but **it only proves
anything when run against the minified build**:

```bash
./gradlew connectedAndroidTest -PttsroadTestBuildType=release   # needs a device + debug.keystore
```

`testBuildType` defaults to `debug`, which is not minified — the same tests pass there while
testing nothing R8-related. CI never sets the property and never needs the keystore.

To turn shrinking back on: set `isMinifyEnabled = true`, run the command above on a real phone, and
only keep it on if that passes. Until someone has actually done that on a device, leave it off.

**Crash reporting is off unless a DSN is configured, and that is the normal state.** With no DSN
the Sentry SDK is never initialised and nothing leaves the device — which is what every build made
without deliberately opting in gets, CI included. To point it at a self-hosted instance, add to the
gitignored `local.properties`:

```properties
ttsroad.sentry.dsn=https://<public-key>@sentry.example.com/<project>
```

(`-PttsroadSentryDsn=…` and `TTSROAD_SENTRY_DSN` also work.) Auto-init is disabled in the manifest
on purpose: the SDK's ContentProvider would otherwise start before `core/CrashReporter` installs
the redaction, and the first events of a session would carry the server's address. Do not re-enable
it. The signed-in server's origin is stripped from messages, exceptions, request URLs and
breadcrumbs before sending — a self-hosted address is usually a home address, and it is not
diagnostic data even on your own instance.

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

**The token is encrypted at rest** (`data/TokenCipher`, `data/TokenEnvelope`). AES-256-GCM under a
non-extractable Android Keystore key, stored as `enc1:<base64 nonce‖ciphertext>`. Three rules:
- A stored value without the prefix is a plaintext token from a build before 0.9.0. It must keep
  working — rejecting it would sign out every existing install on upgrade. `ServiceLocator.init`
  re-seals it in the background via `TokenStore.encryptStoredTokenIfNeeded`.
- The key is deliberately **not** bound to device unlock. Playback starts from Android Auto and the
  media notification with the phone locked, and the service needs the token to stream.
- An envelope that cannot be opened yields null, i.e. "signed out", never an exception. This runs
  while restoring a session at startup, where a throw is a crash on every launch.

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

**The home-screen widget reads a note, never a player.** `widget/` is Glance
(`androidx.glance:glance-appwidget`), rendered by the launcher on demand — usually with this app's
process already reaped — so it cannot hold a `MediaController`. `TtsRoadMediaService.publishNowPlaying`
writes one `NowPlayingSnapshot` to `filesDir/widget_now_playing.json` on the paths it already runs
(the 15s progress tick, `onIsPlayingChanged`, item transitions, discontinuities, speed changes) and
calls `updateAll`. Three rules:
- Everything the widget *decides* lives in `widget/WidgetPresentation.kt`, which is plain arithmetic
  over a snapshot and a clock, and is unit-tested. The Glance composable is deliberately thin,
  because none of Glance is reachable from `app/src/test`.
- A snapshot claiming `isPlaying` is only believed for `StalePlayingThresholdMs` (90s, six missed
  ticks). Past that the widget says "Last heard" — a pause button over audio that stopped hours ago
  invites a tap that does the opposite of what it looks like.
- Widget buttons go through a short-lived `MediaController` **on `Dispatchers.Main`**. Media3 verifies
  the application thread on every controller call and Glance dispatches `ActionCallback` on a worker,
  so anywhere else throws. Covers are fetched to a bitmap through the app's Coil loader before
  composition — the launcher is handed pixels, not a URL, and that loader is what keeps the bearer
  token off Royal Road/CDN origins.

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
- Verify the signing certificate against the *previous* release, not just against the keystore
  checksum: `apksigner verify --print-certs` on both APKs must report the same SHA-256 digest
  (`0a2c1997…5f24` since 0.7.0). That digest matching is what lets the in-app updater install over
  the existing build instead of demanding an uninstall — the keystore check alone does not prove it.
- Once shrinking is re-enabled, `./gradlew connectedAndroidTest -PttsroadTestBuildType=release` on a
  real device joins this list, before the tag.
