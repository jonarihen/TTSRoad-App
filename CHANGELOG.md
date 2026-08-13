# Changelog

Notable changes to the TTSRoad Android client.

## Unreleased

### Added

- **The fiction screen says how much listening is left.** It previously answered only the storage
  question — "0 offline · 73 not downloaded" — which is not the one you ask before starting a
  400-chapter serial. It now leads with total remaining time, a played count, and how many chapters
  are left, matching what the web card shows.
- A chapter you marked played counts as finished even if you never pressed play on it. Trusting the
  saved position instead would report a book you deliberately finished as entirely unheard.
- When no chapter reports a duration, the remaining line is left out rather than shown as "0m".
  A confident zero is worse than saying nothing.
- **The player shows time left in the chapter** where it used to repeat the total duration. The
  scrubber already shows how far in you are; how much longer is the thing being asked.
- **At any speed other than 1x, both numbers appear** — the audio time and what it actually takes
  at that speed. Remaining alone stops answering "will I finish this on the drive" as soon as you
  listen at 1.75x.

No new requests: every figure is a sum over the chapter list the screen has already loaded.

### Changed

- **The speed picker goes up to 3.0x.** The clamps always allowed 0.5–3.0, but the sheet only
  offers presets, so the list itself was the real ceiling and it stopped at 2.0x. It now offers the
  same nine steps as the desktop client, spanning the whole range the server's `playback_speed`
  spec accepts.
- 0.8x is no longer one of the presets. Anyone listening at it keeps it: the sheet adds whatever
  speed is actually set to the list when it is not a preset, so the current speed stays visible and
  reachable rather than being snapped to 0.75x silently. Same mechanism will cover a speed that
  arrives from the account once preferences sync.

## 0.10.0 — 2026-08-08

Signed with the same pinned key as 0.7.0 through 0.9.0, so this installs directly over any of them
as an ordinary update. **You will not be signed out** — see the token note below.

### Changed

- **The chapter filter is remembered.** Picking **Unplayed** used to last exactly as long as the
  screen did: every fiction opened on **All**, so anyone working through a series in order re-chose
  the same filter on every book, every time. It now persists across fictions and across restarts.
  It is one setting for the whole library rather than one per book, because wanting played chapters
  out of the way is a way of reading, not an opinion about a particular fiction. Sort direction
  still resets per fiction.
- The stored value is the filter's internal name rather than its button label, so rewording a chip
  cannot silently reset everyone's choice on upgrade. A value this build does not recognise falls
  back to showing everything: hiding rows is the surprising direction, and a filter that hid
  chapters for no visible reason would read as missing chapters.
- This is still a setting on this phone. The web's **Hide played** is an account preference, and the
  two do not yet agree with each other — the app has no preferences client at all.

### Security

- **Your sign-in token is now encrypted on the device.** It previously sat in plain text for the
  ~90 days it stays valid. Backups were already blocked, so the real exposure was a lost, stolen or
  rooted phone. It is now sealed with AES-256-GCM under a key the Android Keystore will not hand
  out, even to this app.
- **The upgrade is silent.** A token written by an older build is still read, then re-sealed in the
  background shortly after launch, so nobody is signed out and no plaintext lingers waiting for the
  next sign-in — which, on a session that renews with every request, could have been months.
- If sealing fails the app stores the token as before rather than making sign-in impossible, and an
  envelope it cannot open reads as "signed out" instead of crashing on every launch. Losing access
  to your library is a worse outcome than the thing being defended against.

### Added

- **Optional crash reporting, off unless you turn it on.** There was previously no logging or crash
  reporting of any kind, so a failure on the phone produced no signal at all. Reports go to a Sentry
  instance *you* host, configured through a local build setting that is never committed. With none
  set — the default, and the state of this release's APK — the SDK is never started and nothing
  leaves the device.
- When it is enabled, **your server's address is stripped** from messages, exceptions, request URLs
  and breadcrumbs before anything is sent. Someone self-hosting is usually hosting at home, and that
  address is not diagnostic data even on their own instance.

## 0.9.0 — 2026-08-04

Signed with the same pinned key as 0.7.0 through 0.8.0, so this installs directly over any of them
as an ordinary update.

### Fixed

- **The player's "CHAPTERS 53/246" button no longer reads top to bottom, one letter per line.** On a
  narrow phone it was squeezed into the leftover space at the right edge and wrapped between
  characters, which also stretched the controls into a tall column of empty space and pushed Speed
  and Sleep down the screen. The row of buttons now wraps onto a second line instead of crushing any
  one of them, and no label breaks mid-word.
  ([#47](https://github.com/jonarihen/TTSRoad-App/issues/47))
- **Playback no longer runs faster on the phone than in the web console.** Skip silence was on out
  of the box, and the browser's player has nothing like it, so the same chapter at a nominal 1.0x
  got through itself audibly sooner here and the speech ran together. It is now off by default and
  the two match. If you had deliberately switched it on or off, your choice is untouched — only the
  never-touched case changes. ([#43](https://github.com/jonarihen/TTSRoad-App/issues/43))
- The speed picker offers **1.25x** where it used to offer 1.2x, so every step now exists in both
  the app and the web console. A stored 1.2x still plays at 1.2x; it just is not one of the
  presets any more.

### Added

- **Downloads wait for Wi-Fi by default.** A chapter is tens of megabytes and "download next 20" is
  one tap, so the old behaviour could spend a data plan without being asked. Settings → Offline has
  the switch if you want downloads on mobile data. Either way a queued chapter waits for a
  connection rather than failing, so turning it back on releases whatever was waiting — nothing has
  to be queued twice. ([#14](https://github.com/jonarihen/TTSRoad-App/issues/14))

### Changed

- **Skip silence is switchable from the player's speed sheet**, not only from Settings — that is
  where you look when playback feels wrong.
- **Downloads are now filed under the server they came from.** Connect this app to a second TTSRoad
  instance holding a fiction with the same name, and the two can no longer be mistaken for each
  other — previously the wrong server's audio could play from the cache. Downloads still survive
  signing in over a different address for the same server, which is the point of having them.
- Chapters downloaded with 0.8.0 are re-downloaded once, the first time this version reaches a
  server that says what it is. They are re-filed rather than silently kept, so a row that says
  "downloaded" is telling the truth. "Delete all downloads" in Settings still clears everything.

## 0.8.0 — 2026-08-03

Signed with the same pinned key as 0.7.0 through 0.7.2, so this installs directly over any of
them as an ordinary update.

### Read along while you listen

- **A new reader shows the chapter's text and follows the audio.** Open it from the player, or from
  the icon on any chapter row. The sentence being read is banded, with the current word picked out
  inside it — a lone moving word is impossible to follow at 1.5x or 2x, which is how this app is
  actually listened to.
- **Tap any word to jump the audio there.** Useful for going back over the sentence you missed
  without hunting for it on the scrubber.
- The text scrolls itself to keep the current line high on the screen. **Scroll it yourself and it
  stops fighting you** — a "back to current" button appears instead, and nothing moves until you
  ask.
- **Reading works without playing anything.** It is also what a chapter converted before timings
  existed falls back to: the text is there, it just does not follow.
- **A chapter you have opened once reads offline.** The text is kept on the phone, and reopening a
  chapter asks the server only whether anything changed rather than downloading it again.
- Text size, page colour (console, paper, or a dimmed night page) and how much is highlighted are
  all adjustable from the reader. These are kept on this phone; the web reader still has its own
  copy, because the server has nowhere to share them yet.
- The screen stays awake while you read — until the sleep timer starts fading out, at which point it
  lets go rather than shining at someone falling asleep.
- The reader is deliberately not available in Android Auto.
- Servers without read-along show none of this rather than offering a button that fails.

### Knows what your server can do

- The app now asks the server which optional features it supports instead of assuming. Features the
  server does not have stay hidden rather than appearing and then failing.
- Typing a server URL on the sign-in screen now confirms what answered, showing the server name and
  version under the field once it responds.
- An older server that has never heard of the question is treated as a perfectly good server with
  none of the optional extras — signing in, the library, and playback are unchanged.
- Signing out forgets what the previous server could do, so connecting to a different one does not
  inherit its features.

### See and sign out your other devices

- **Settings > Device sessions** lists every phone, tablet and car head unit signed in to your
  account: when it signed in, when it was last used, when its session lapses, and the address it
  last connected from. The device you are holding is marked and listed first.
- Sign out a single device, or sign out every other device in one go — the phone in your hand always
  stays signed in. Both ask first, because neither can be undone from here.
- On a server too old to know about device sessions the screen simply says so, instead of showing an
  error you can do nothing about.

### The login screen now says why you are looking at it

- Being bounced to the sign-in screen used to be silent. It now explains itself: whether the session
  lapsed from 90 days of disuse, was signed out from another device, or is no longer recognised by
  the server at all — in the server's own words.
- Sessions renew themselves whenever the app talks to the server, so an app you use is never signed
  out for being old.

### A rejected session no longer stalls playback

- If the server refused a chapter's audio because the session was over, the player treated it like a
  dropped connection and spent the full retry backoff asking again before anything happened. It now
  recognises the refusal immediately and takes you to the sign-in screen, with the same explanation
  as everywhere else.
- Genuine network trouble — a tunnel, a Wi-Fi handover, a server restart — still retries exactly as
  before.

### Chapters you can take with you

- **Download a chapter and it plays with the server unreachable** — in a tunnel, on a plane, or
  when the home connection drops mid-drive. Every chapter row has a download button, and the
  fiction header downloads the next ten chapters from wherever you are up to.
- **Streamed chapters are kept as well.** Rewinding an hour of overnight playback no longer
  re-fetches the whole chapter from the server.
- Downloads keep running with a progress notification, survive the app being closed, and pick up
  again the next time you open it.
- **Nothing is ever deleted automatically.** Settings shows how much space the offline audio takes
  and offers "delete all downloads" to clear the lot. A size cap, a Wi-Fi-only switch and automatic
  clean-up of played chapters are deliberately not in yet.
- A download stays valid when you sign in again on a different address for the same server: it is
  tied to the chapter, not to the URL you happened to connect through.
- Chapters that are not downloaded stream exactly as before.

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
