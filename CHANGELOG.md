# Changelog

Notable changes to the TTSRoad Android client.

## Unreleased

### Server capability discovery

- The login screen now checks the server URL as you type it and says what it found: the server
  name and version, which optional features it offers, or that it could not be reached. Sign-in
  itself is unchanged and never waits for the check.
- The app asks the signed-in server what it supports before the library loads, and Settings lists
  the answer, so a feature that is missing reads as "this server does not have it" rather than as
  the app misbehaving.
- Servers older than capability discovery keep working exactly as before, with every optional
  feature off.

## 0.7.2 — 2026-07-27

### Cover artwork hotfix

- Fixes every Royal Road cover rendering as a black tile. Absolute third-party cover URLs were
  incorrectly rewritten onto the TTSRoad server, where they returned 404.
- The image client now sends the bearer token only to the exact TTSRoad server origin; external
  artwork hosts never receive it.
- A failed or unavailable image now leaves the fiction's letter tile visible instead of an empty
  black rectangle.
- Signed with the same pinned key as 0.7.0 and 0.7.1, so it installs directly over either release.

## 0.7.1 — 2026-07-27

### Startup crash hotfix

- Fixes the app closing immediately after launch in 0.7.0. That was the first minified release,
  and R8 renamed a playback-history model that Moshi reads through reflection during application
  startup.
- Release shrinking is disabled until an on-device startup smoke test is automated, and the
  reflected models now have explicit keep rules for when shrinking is re-enabled.
- Signed with the same pinned key as 0.7.0, so this APK installs directly over it.

## 0.7.0 — 2026-07-27

### One-time reinstall for durable future updates

- **This release starts a new signing lineage.** Android cannot install it over versions 0.4.0
  through 0.6.0, whose signing key was lost. Uninstall the existing app once, install 0.7.0, and
  sign in again. Server-side playback progress is unaffected, but local jump-back history and app
  preferences are cleared by the uninstall.
- The replacement key is now pinned by checksum, used by both debug and release builds, and copied
  to a protected backup on a separate encrypted volume. The build refuses to sign with a missing
  or different key, so future in-app updates remain installable.

### No more full-screen spinner on every back-navigation

- **Going back to the library shows it instantly.** Screen data now lives above the screens, so
  leaving a screen no longer destroys it and returning no longer refetches from a blank spinner.
  The refresh still happens - underneath the content, as a hairline progress strip.
- **Marking a chapter played updates that row only.** It used to reload the whole list, tearing down
  a 500-row chapter list and dropping you back at the top, for the sake of one checkmark.
- **Pull-to-refresh** on the library, all-fictions and fiction screens.
- A refresh that fails while content is already loaded now shows a one-line notice instead of
  replacing a perfectly readable library with an error page.

### Cover images load wherever audio does

- Artwork was fetched with Coil's default loader and the raw URL from the API, so it missed both
  things playback already did: the bearer token, and the host rewrite that points server-built URLs
  at the address the phone actually connected to. On any setup where the backend's `BASE_URL`
  differs from the connect address (LAN IP vs domain, VPN, `10.0.2.2` on the emulator), or where
  covers sit behind auth, every cover rendered as the letter-fallback tile while audio played fine.
  Phone UI and Android Auto artwork both fixed.

### Android Auto: voice search and a browse tree that shows progress

- **"Hey Google, play Ashes of Aether on TTSRoad"** now works, and starts that fiction at its resume
  position. Voice is the only safe way to start something new while driving, and it previously did
  nothing at all.
- **Searching in the car** returns matching fictions and chapters, matched on title, author and tags.
- **The Fictions node renders as a grid** with artwork instead of plain rows, and **chapters show
  completion progress** — a started chapter no longer looks identical to an untouched one.
- A weak match deliberately does *not* start playing. Matching a shared tag or an author with more
  than one book resolves to nothing rather than starting the wrong book at speed.

### −30s / +30s outside the app

- The **notification, lockscreen, and Android Auto transport row** now carry −30s and +30s
  buttons either side of play/pause, so catching a missed sentence while driving — or rewinding
  after waking up mid-chapter — no longer means unlocking the phone. Previous/next chapter move
  to the secondary slots.
- Both seek **within the current chapter**: near the end, forward stops at the end rather than
  rolling into the next chapter; near the start, back stops at zero.

### Playback failures are visible, and mostly fix themselves

- A stream that dies — home server down, VPN dropped, Wi-Fi handover, a tunnel — used to leave the
  app looking like it had quietly stopped. It now **retries on its own** after 2s, 5s and 15s, so a
  brief outage heals with no user action at all.
- If the retries do not get there, the player and the mini player bar show **what went wrong and a
  RETRY button**, instead of just sitting in the paused state.
- A **401 on the audio stream** is now treated as what it is: the token has been revoked, so the app
  signs out and returns to the login screen rather than retrying forever against a server that will
  keep refusing.
- Errors clear by themselves the moment playback recovers.

### Playback speed sticks, and the skip interval is yours to pick

- **Speed now survives** a swipe-away, a force-stop and a reboot. It used to live only in the
  ExoPlayer instance, so every service restart silently dropped you back to 1.0x.
- **Speed is selectable directly** from a picker instead of a cycle-only button — getting from 2.0x
  back to 1.5x was five taps.
- **The 30s skip is configurable**: 10 / 15 / 30 / 45 / 60s in Settings, used by the player, the
  mini player bar and the transport button labels. 30s suits a dozed-off rewind; 10-15s suits "what
  did that sentence just say".
- Preferences live in their own store, so signing out no longer forgets how you listen.

### Audio tuned for synthesised speech

- **Skip silence** (on by default, switchable in Settings). Synthesised chapters carry pauses around
  headings, scene breaks and sentence boundaries that are far longer than a human narrator's — over
  an eight-hour night that is a lot of dead air. Turn it off if it clips a dramatic pause.
- **Volume boost** — Off / Low / Medium / High. Chapters converted at different times or with
  different voices come out at different levels: in the car that means reaching for the volume knob,
  and in bed it means a loud chapter after a quiet one wakes you up. Capped at 10 dB, because past
  that a chapter already near full scale starts to clip.
- Both are applied by the media service, so they survive a swipe-away, a process kill and a reboot,
  and they apply to playback started from the car with no UI running.

### Sleep timer

- The player has a **SLEEP** button: 5 / 15 / 30 / 45 / 60 minutes, or **end of the current
  chapter**. The remaining time replaces the label while the timer is armed, and playback stops
  on its own instead of streaming all night.
- The last 30 seconds **fade out** rather than cutting mid-sentence, and **shaking the phone**
  during the fade adds 5 minutes and brings the volume back — no bright screen, no hunting for
  a button.
- "End of chapter" stops at the chapter boundary instead of auto-advancing.
- The timer lives in the media service, so it keeps counting with the app backgrounded and the
  screen off. Pausing by hand freezes the countdown rather than spending it; pressing play
  resumes where it left off.

### Lockscreen and notification controls on Android 13+

- The app now **asks for notification access** after you sign in. Android 13 and newer default it
  to denied, which silently suppressed the media notification — so playback had no controls in the
  notification shade or on the lockscreen, and no way to pause without opening the app.
- If you decline, playback is unaffected and **Settings** explains what is missing, with a button
  that opens the app's notification settings.

### BACK follows the path you took

- Navigation now keeps a real back stack. BACK from a fiction you opened via **Browse all
  fictions** returns to the **All fictions** list — with your search text and scroll position
  intact — instead of jumping straight to the library front page. Same for the player and
  settings opened from a nested screen.
- The **hardware/gesture back button** now participates in in-app navigation: it steps back one
  screen and only leaves the app from the library front page.

### Chapter list controls

- **Jump to the chapter you're on**: opening a fiction that is currently playing now scrolls
  straight to that chapter instead of the top of a 500-row list, and the row is highlighted. A
  **JUMP TO CURRENT** button appears once you scroll away from it. The player's chapter sheet
  opens on the playing chapter too.
- **Filter chips**: All / Unplayed / Ready, filtered client-side on the already-loaded list.
- **Sort toggle**: switch between oldest-first and newest-first.
- **Bulk mark played**: long-press any chapter row for "Mark all previous as played" and "Mark
  all as played" — one API call for the whole range, and the rows update in place. Toggling a
  single chapter no longer reloads the entire list either.

### "Last heard" resume on the library screen

- Opening the app after a night now offers **LAST HEARD 23:49 — Ashes of Aether — Ch 7 — 1:12:34**
  above Continue listening, with **RESUME THERE**. The app already recorded where playback was at
  every wall-clock moment; this stops you having to open the jump-back sheet and hunt for the time.
- Shown only when nothing is playing and the last snapshot is at least 30 minutes old, so it reads
  as catching up after a night rather than a rewind offer mid-listen. **DISMISS** hides it for that
  snapshot, not for a fixed period, so it does not come back all day.
- Where playback stalled and kept logging the same position, the banner offers the moment the audio
  actually stopped moving rather than the last identical repeat.

## 0.6.0 — 2026-07-02

### Media-app UI overhaul (Netflix/Audible-style)

- **Persistent mini player bar**: playback now stays visible at the bottom of every screen —
  cover, title, −30s and play/pause, with a hairline progress strip. Tap it to open the full
  player. No more hunting for the PLAYER button in the top bar.
- **Library**: the most recent in-progress chapter is a **hero billboard** with one big RESUME
  action; the rest of "Continue listening", Fictions, and Recent are **cover-forward rail tiles**
  — the artwork is the tap target and your listening progress is drawn directly on it.
- **Fiction page**: the chapter list is a flat, scannable list (number · title · time left);
  the row plays, a single check toggles played/unplayed, and unready chapters show a status tag.
- **Player**: one icon transport row (prev / −30 / play / +30 / next) replaces three rows of
  text buttons, and the seek bar only seeks when released, so scrubbing no longer stutters.

## 0.5.0 — 2026-07-01

### Jump back to an exact clock time

- The "Jump back" sheet now has a **"Fell asleep at"** field: type the time from your health
  app's sleep log (e.g. `23:49`) and it finds the closest recorded position in your playback
  history and seeks there directly — no more scrolling through a relative "2h 15m ago" list to
  guess which entry matches.
- Each entry in the pick-a-moment list now shows the **actual clock time** it was recorded at
  (not just "ago"), so it's easy to match against another app's timestamps.

### App icon

- Replaced the launcher icon, which rendered as a stray "P", with a simple play-triangle mark.

## 0.4.0 — 2026-06-28

### In-app self-updater

- The app now checks **GitHub Releases** on launch (and on demand from Settings → App → Check
  for updates). When a newer build is published it offers to **download the APK and install it**
  in place — no more copying APKs by hand. Requires `REQUEST_INSTALL_PACKAGES` and a FileProvider;
  new releases must be signed with the same (debug) key to update over the top.

### Jump back — now works after playback has stopped

- "Jump back" previously only seeked within the loaded queue. It now stores the fiction/chapter id
  per history point and, if the queue was cleared (e.g. a sleep tracker auto-stopped playback hours
  after you dozed off), **reloads the fiction and resumes at the exact historical position**.
- The **Player is now reachable when nothing is playing** as long as there's history — so you can
  open it the next morning, see when you drifted off, and roll back.

## 0.3.0 — 2026-06-28

### Jump back to where you fell asleep

- The media service now records a rolling, persisted **playback-position history** (wall-clock →
  chapter + position) while playing, capped at ~8 hours. Because it lives in the foreground
  service it keeps logging with the app backgrounded.
- New **Jump back** sheet on the player: pick a moment from the timeline (e.g. "1h 47m ago —
  Chapter 5 · 1:12:34") and it seeks the queue right there, even across chapter boundaries — so if
  playback rolled on while you slept, you can rewind to where you actually dozed off. Inspired by
  Audiobookshelf's listening-history rewind.

## 0.2.0 — 2026-06-28

### Performance & correctness

- **Networking now reuses a single `OkHttpClient`.** Every API call previously built a
  brand-new client and Retrofit instance, forcing a fresh TLS handshake per request (worst
  during playback, which saves progress frequently). The shared client with a dynamic auth
  header and a per-base-URL Retrofit cache restores connection pooling.

### Earlier on 2026-06-28

### Library & browsing

- Added an **All fictions** page: browse every fiction in a searchable grid (filter by
  title, author, or tag), reachable via **Browse all** on the library.
- Fleshed out the **fiction detail page**: cover, rating, conversion-progress bar, tag
  chips, and an expandable synopsis, plus a Resume/Play action.

### Android Auto

- Tapping a chapter in the car now expands the whole fiction into the playback queue
  (`onSetMediaItems`), so **next/previous chapter, auto-advance, and resume-at-position** work
  in the car — matching the in-app player — and the now-playing queue shows the chapter list.
- Pressing play in the car with nothing loaded **resumes the most recent "Continue listening"
  chapter** at its saved position (`onPlaybackResumption`).

### Player

- **Chapter-queue playback.** Starting a chapter from a fiction loads the whole fiction as
  a playlist, which enables next/previous chapter, auto-advance at the end of a chapter, and
  a jump-to-chapter list. The queue is shared with the OS media controls and Android Auto.
- Added **previous/next chapter**, **playback speed** (0.8×–2×), and a **chapter-list bottom
  sheet** to the player.
- The player cover now **scales to fill the available space** instead of a fixed size.

### Design & security (earlier in this cycle)

- Adopted the **AARIS design language** (dark, square, orange-accent, mono labels) to match
  the web console.
- **Enforced two-factor authentication** on login: when the server reports that a code is
  required, the app prompts for a TOTP or single-use recovery code before signing in.
