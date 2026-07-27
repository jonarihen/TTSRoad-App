# Changelog

Notable changes to the TTSRoad Android client.

## Unreleased

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
