# TTSRoad Android Build Brief

This file is the handoff brief for creating a separate native Android repository for TTSRoad. The backend in this repo now exposes a mobile API designed for a sideloaded Android app with Android Auto media support.

## Goal

Build a private Android app that talks natively to TTSRoad's FastAPI backend. Do not use the podcast RSS feed as the app's primary data source. The app will only be installed manually on one phone, so it does not need Play Store release work.

Target app behavior:

- Phone UI for server setup, login, fiction list, chapter list, playback, and resume state.
- Android Auto support through a native media service.
- Stream MP3 audio from TTSRoad using bearer-token authentication.
- Save playback progress back to TTSRoad.
- Keep admin-heavy workflows out of Android Auto. Optional phone-only admin features can come later.

## Recommended Stack

Use a native Android project, not Expo Go.

- Kotlin
- Gradle Kotlin DSL
- Jetpack Compose for phone UI
- AndroidX Media3 / ExoPlayer for playback
- `MediaLibraryService` and `MediaSession` for Android Auto
- Retrofit or Ktor client for JSON API calls
- OkHttp for authenticated audio streaming headers
- DataStore for server URL and bearer token storage
- Coil for cover images
- Optional later: Room and Media3 cache for offline downloads

Project location: this repository root.

## Backend Assumptions

The backend server must be reachable from the phone over HTTPS for real use. Local HTTP is fine for LAN testing if Android network security config allows it.

The Android app should let the user enter a base URL, for example:

```text
https://ttsroad.example.com
```

All API paths below are relative to that base URL.

Use this header for authenticated API and audio requests:

```http
Authorization: Bearer <token>
```

## Mobile API

### Login

```http
POST /api/mobile/login
Content-Type: application/json
```

Request:

```json
{
  "username": "admin",
  "password": "password",
  "device_name": "Pixel 8"
}
```

Success response:

```json
{
  "token": "ttsr_...",
  "token_type": "bearer",
  "device_id": 42,
  "expires_at": "2026-10-26T12:00:00Z",
  "user": {
    "id": 1,
    "username": "admin",
    "is_admin": true
  },
  "server": {
    "name": "TTSRoad",
    "base_url": "https://ttsroad.example.com",
    "api_version": 1
  }
}
```

Store `token` in DataStore. Never log it. Tokens expire after 90 days without
use; an authenticated request renews the expiry silently. Do not build a refresh
loop around the absolute timestamp.

Errors:

- `401` invalid credentials
- `429` throttled, with `Retry-After`

An authenticated request whose bearer token can no longer be used returns a
distinguishable `401`:

```json
{
  "detail": {
    "message": "This device session expired. Sign in again.",
    "reason": "token_expired"
  }
}
```

`reason` is `token_expired`, `token_revoked`, or `invalid_token`. Clear the
stored credential, stop retrying, and show the message. This applies to JSON API
requests and bearer-authenticated `/audio/...` requests.

### Logout

```http
POST /api/mobile/logout
Authorization: Bearer <token>
```

Response:

```json
{
  "status": "ok",
  "revoked": true
}
```

### Devices

```http
GET /api/mobile/devices
Authorization: Bearer <token>
```

Returns `{"api_version": 1, "devices": [...]}`. Each device has `id`,
`device_name`, `created_at`, `last_used_at`, `expires_at`, `last_ip`, `status`,
and `is_current`.

```http
DELETE /api/mobile/devices/{token_id}
POST /api/mobile/devices/revoke-others
Authorization: Bearer <token>
```

The first revokes the named session if it belongs to the current user. The
second revokes every other mobile session and deliberately keeps the token used
for the request.

### Current User

```http
GET /api/mobile/me
Authorization: Bearer <token>
```

Response:

```json
{
  "user": {
    "id": 1,
    "username": "admin",
    "is_admin": true
  }
}
```

### Library

```http
GET /api/mobile/library
Authorization: Bearer <token>
```

Use this as the app home payload. It contains fictions, continue-listening items, and recent chapters.

Important fields:

- `fictions[]`: all tracked fictions with counts and cover URLs.
- `continue_listening[]`: resume/up-next items. Items may include `audio` with an authenticated stream URL.
- `recent_chapters[]`: recent backend chapters, including status.

Audio object shape:

```json
{
  "filename": "0001.mp3",
  "path": "/audio/my-fiction/0001.mp3",
  "url": "https://ttsroad.example.com/audio/my-fiction/0001.mp3",
  "requires_bearer_auth": true
}
```

Even when `url` is absolute, still attach the bearer token header when streaming.

### Fiction Chapters

```http
GET /api/mobile/fictions/{fiction_id}/chapters?playable_only=false&include_excluded=false
Authorization: Bearer <token>
```

Use `playable_only=true` for Android Auto browse trees and playback queues.

Response:

```json
{
  "api_version": 1,
  "fiction": {
    "id": 1,
    "title": "Example Fiction",
    "author": "Author",
    "slug": "example-fiction",
    "cover_image_url": "https://...",
    "total_chapters": 120,
    "done_chapters": 118
  },
  "total": 118,
  "chapters": [
    {
      "id": 10,
      "fiction_id": 1,
      "title": "Chapter 1",
      "chapter_number": 1,
      "display_number": 1,
      "player_index": 0,
      "status": "done",
      "playable": true,
      "audio_duration": 1420.5,
      "audio_duration_label": "23:40",
      "audio_filesize": 12345678,
      "audio": {
        "filename": "0001.mp3",
        "path": "/audio/example-fiction/0001.mp3",
        "url": "https://ttsroad.example.com/audio/example-fiction/0001.mp3",
        "requires_bearer_auth": true
      },
      "playback": {
        "position_seconds": 120.0,
        "is_played": false,
        "last_listened_at": "2026-06-23T12:00:00Z",
        "remaining_seconds": 1300.5,
        "remaining_label": "21:40"
      }
    }
  ]
}
```

### Save Playback Progress

```http
POST /api/mobile/playback/progress
Authorization: Bearer <token>
Content-Type: application/json
```

Request:

```json
{
  "fiction_id": 1,
  "chapter_id": 10,
  "position_seconds": 180.5,
  "is_played": false
}
```

Response:

```json
{
  "status": "saved",
  "chapter_id": 10
}
```

Call this periodically during playback, on pause, and near completion. Mark `is_played=true` when playback reaches the app's completion threshold, for example 96% or last 20 seconds.

### Mark Played/Unplayed

```http
POST /api/mobile/playback/mark
Authorization: Bearer <token>
Content-Type: application/json
```

Request:

```json
{
  "chapter_ids": [10, 11, 12],
  "played": true
}
```

Response:

```json
{
  "status": "ok",
  "played": true,
  "chapter_ids": [10, 11, 12],
  "count": 3
}
```

## Existing API Access

Bearer tokens are also accepted by the existing protected `/api/...` endpoints through backend middleware. That means the Android app can optionally use existing endpoints such as:

- `GET /api/fictions`
- `GET /api/fictions/{fiction_id}`
- `GET /api/fictions/{fiction_id}/status`
- `GET /api/fictions/{fiction_id}/chapter-meta`
- `POST /api/fictions` for adding Royal Road fictions

Keep first Android MVP on the mobile endpoints unless a specific existing endpoint is needed.

## Audio Streaming

Stream chapter audio with Media3/ExoPlayer from the `audio.url` value.

The backend accepts bearer auth on `/audio/{fiction_slug}/{filename}`. Configure the Media3 data source to send:

```http
Authorization: Bearer <token>
```

Recommended Media3 pieces:

- `ExoPlayer`
- `DefaultMediaSourceFactory`
- `DefaultHttpDataSource.Factory` or OkHttp data source with default request properties
- `MediaItem` with metadata title, artist/author, artwork URI, duration if available

## Android Auto Architecture

Implement Android Auto as a media app, not as a templated car app.

Core service:

- Create a `MediaLibraryService` subclass.
- Create a `MediaSession`/`MediaLibrarySession` backed by ExoPlayer.
- Expose browse roots that Android Auto can render safely.

Suggested browse tree:

```text
Root
- Continue Listening
- Fictions
  - Fiction title
    - Playable chapters
- Recent
```

For each playable chapter, build a Media3 `MediaItem` using:

- media ID: stable ID such as `chapter:<chapter_id>`
- URI: `audio.url`
- title: chapter title
- album/title group: fiction title
- artist: author if available
- artwork URI: fiction cover URL if available
- extras: `fiction_id`, `chapter_id`, `display_number`, `position_seconds`

On playback start, seek to `playback.position_seconds` when appropriate.

On playback events, call `/api/mobile/playback/progress`.

## Android Manifest Requirements

Add Android Auto media metadata.

Example concept only; adapt to the generated project/package names:

```xml
<application>
    <meta-data
        android:name="com.google.android.gms.car.application"
        android:resource="@xml/automotive_app_desc" />

    <service
        android:name=".media.TtsRoadMediaService"
        android:exported="true"
        android:foregroundServiceType="mediaPlayback">
        <intent-filter>
            <action android:name="androidx.media3.session.MediaSessionService" />
            <action android:name="android.media.browse.MediaBrowserService" />
        </intent-filter>
    </service>
</application>
```

`res/xml/automotive_app_desc.xml`:

```xml
<automotiveApp>
    <uses name="media" />
</automotiveApp>
```

Also include the permissions needed by the target SDK, for example internet and foreground media playback permissions.

## Phone UI MVP

Screens:

1. Server setup/login
2. Library
3. Fiction detail/chapter list
4. Player
5. Settings/logout

Avoid admin features in v1. The existing web UI remains the admin surface.

## Suggested Implementation Order

1. Create native Android project.
2. Add dependencies: Compose, Navigation, Media3, Retrofit/Ktor, DataStore, Coil.
3. Implement API client and DTOs for the endpoints in this file.
4. Implement token storage and login flow.
5. Implement library and chapter list screens.
6. Implement ExoPlayer playback on phone.
7. Implement playback progress sync.
8. Add `MediaLibraryService` and Android Auto manifest metadata.
9. Build Android Auto browse tree from cached/latest API data.
10. Test sideloaded APK on phone.
11. Enable Android Auto developer mode and unknown sources if needed.
12. Test with Android Auto Desktop Head Unit and then the real car.

## Personal Install Notes

Because this app is only for one phone, use a debug or release APK installed manually:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For Android Auto sideload visibility during personal testing, enable Android Auto developer settings on the phone and allow unknown sources. The app still must expose a valid media service; Android Auto will not show an arbitrary normal Activity as a car app.

## Backend Files Changed For This Support

In the TTSRoad backend repo:

- `app/database.py`
  - Added `MobileApiToken` table and `User.mobile_tokens` relationship.
- `app/services/mobile_auth.py`
  - Generates, hashes, resolves, and revokes bearer tokens.
- `app/main.py`
  - Registers mobile router.
  - Allows `/api/mobile/login` without a session.
  - Resolves bearer-token users before falling back to browser sessions.
- `app/routers/feeds.py`
  - Allows bearer-token access to feed/audio endpoints.
- `app/routers/mobile.py`
  - Adds the native/mobile API described above.

## Verification Done In Backend Repo

The backend source compiles with:

```bash
python -m compileall -q -f app
```

In the current environment, `pytest` reports no tests and the direct import smoke test cannot run because the local Python environment does not have FastAPI installed. Run full backend verification in an environment with `requirements.txt` installed.