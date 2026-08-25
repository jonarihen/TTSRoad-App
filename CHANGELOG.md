# Changelog

Notable changes to the TTSRoad Android client.

## Unreleased

### Added

- **Fix a fiction's details from the phone.** Admin accounts get an **EDIT DETAILS** button on the
  fiction screen: title, author, synopsis, tags, and a new cover image picked from the gallery.
  Scraped metadata is often not what you want to look at for the next three hundred chapters — a
  title with the author's release-schedule note stapled to it, a marketing blurb where a synopsis
  should be, a Patreon import whose cover is the creator's avatar — and correcting any of it used to
  mean reaching for the database.
  ([jonarihen/TTSRoad#133](https://github.com/jonarihen/TTSRoad/issues/133))
- **The correction sticks.** A field you edit stops being refreshed from the source, so a fixed
  title is not quietly restored by the next poll. Fields being held that way are marked
  **HAND-EDITED** in the editor, and **USE SOURCE VALUES** hands them back. That lifts the
  protection only — it does not bring the old text back, and the confirmation says so before you
  agree to it.
- **The cover is an image you pick, not a link you paste.** JPEG, PNG, WEBP or GIF up to 10 MB,
  uploaded to the server and used everywhere the cover appears. A file of the wrong type or over the
  limit is refused on the phone rather than after it has been pushed up a mobile connection.
- Only the fields you actually changed are sent, so opening the editor to read it and closing it
  again never freezes a fiction's metadata against its own updates.
- The button is hidden entirely for non-admin accounts and for servers without fiction management.
  On a server that predates hand-edited metadata the title and author are still editable, while the
  synopsis, tags and cover art are disabled with a line saying why — rather than accepting edits
  that would silently go nowhere.
- **A size cap on cached streaming audio.** Settings → Offline gains **Keep streamed audio**: 256 MB
  through 5 GB, or no limit. Past the cap the chapters you have not touched in longest are dropped
  and play again from the server if you want them. One gigabyte by default.
- **Storage is broken out into what was downloaded and what was streamed**, because the two now
  behave differently, and **CLEAR STREAMED AUDIO** frees the disposable half on its own. Until now
  the only way to reclaim streamed audio was to delete the chapters you had downloaded for a flight
  along with it. ([#93](https://github.com/jonarihen/TTSRoad-App/issues/93), closing the last item
  of [#14](https://github.com/jonarihen/TTSRoad-App/issues/14) and
  [#60](https://github.com/jonarihen/TTSRoad-App/issues/60))
- **"End of chapter, or 30 minutes", whichever is sooner.** The sleep timer's boundary mode is what
  you want at night right up until the chapter has fifty minutes left, at which point it is not a
  sleep timer but a promise to be awake at one in the morning. The new option appears in the
  sleep-timer sheet only when the current chapter is actually longer than half an hour, since below
  that it would do exactly what the row above it already does.
  ([#68](https://github.com/jonarihen/TTSRoad-App/issues/68))
- The ceiling survives a seek: rewinding twenty minutes no longer buys back the twenty minutes the
  ceiling was there to refuse. Shaking to add five minutes drops it, because that is an explicit
  answer to the question it was asking.
- **A playback speed per book.** The player's speed sheet gains **Only for this book**: on, the pace
  you pick applies to this fiction and nowhere else; off, it follows the global speed again,
  including later changes to it. Different voices and different narrators want different paces, and
  switching between two books used to mean re-setting the speed every time.
  ([#68](https://github.com/jonarihen/TTSRoad-App/issues/68))
- The override follows the book rather than the screen, so auto-advancing into a different fiction —
  or starting one from Android Auto or the notification, with no UI running at all — plays it at its
  own pace.

### Changed

- **Downloads and streaming no longer share one cache.** They could not have separate policies while
  they did: Media3's LRU evictor cannot tell a downloaded span from a streamed one, so any cap on the
  shared store would eventually have deleted a chapter someone downloaded on purpose — the exact
  failure offline downloads exist to prevent, discovered in a tunnel. Downloads keep a store with no
  evictor at all and are still never removed automatically. Playback reads the download store first,
  then the streamed one, then the network.
- Replaying a downloaded chapter no longer copies it into the streaming cache, so it costs no capped
  space and pushes nothing else out.
- Upgrading frees whatever streaming left in the download store before the split. Those bytes are
  unreachable now that read-through looks elsewhere, and chapters the download index does not claim
  are exactly the ones nobody asked for by name. Every download is left untouched, and the sweep is
  abandoned entirely if the index cannot be read.
- **Jump back reaches most of the night instead of the last two hours.** The history has held about
  eight and a half hours since 0.6.0, but the sheet spent all two dozen of its rows at a flat
  five-minute spacing — so someone who fell asleep at midnight and reached for it at six was offered
  nothing older than four in the morning, which is the wrong two hours for the one case the feature
  exists for. The steps now widen with age: five minutes inside the last half hour, where a rewind
  is a correction, fifteen out to two hours, half an hour beyond that. Same two dozen rows, and the
  oldest now sits just under eight hours back.
  ([#68](https://github.com/jonarihen/TTSRoad-App/issues/68))

### Fixed

- **A failed chapter says why it failed.** The server has always sent a reason; the app dropped it
  at the parse layer, so every chapter that could not be converted rendered as the bare word
  "error". The reason now appears on the chapter row and in full when you long-press it — which is
  the difference between a chapter locked behind a pledge tier and one that is genuinely broken.
  ([#106](https://github.com/jonarihen/TTSRoad-App/issues/106))
- **The fiction screen says how a book is produced.** Which voice reads it, at what rate, where its
  chapters come from and when the server last looked for new ones. All five have been in the
  library payload since before the app existed — the client simply never decoded them, so "which
  narrator is this" had no answer on a phone.
  ([#111](https://github.com/jonarihen/TTSRoad-App/issues/111))
- **A paused fiction says it is paused.** A book switched off server-side looks exactly like an
  up-to-date one until you notice nothing has arrived for a fortnight. It now carries a warning
  saying the server is not polling it or converting anything new.
- **A chapter being converted shows how far along it is.** Every chapter without audio read
  "pending", whether it was queued behind two hundred others or ninety percent finished. Rows now
  say `FETCHING`, `CLEANING` or `CONVERTING 62%`, from the stage and percentage the server was
  already sending.

## 0.12.0 — 2026-08-17

Signed with the same pinned key as 0.7.0 through 0.11.0, so this installs directly over any of them
as an ordinary update.

Nothing in this release needs a newer server. The bookmark action appears only where the server
already advertises bookmarks, and the rest is entirely client-side.

### Added

- **Bookmark from the car and the notification.** A **Bookmark this moment** action on the Android
  Auto transport, the media notification and the lockscreen marks where you are without unlocking
  the phone, opening the app or interrupting playback. Hearing a line worth keeping while driving is
  the moment the web's bookmarks page cannot serve and an in-app list barely serves either.
  ([#68](https://github.com/jonarihen/TTSRoad-App/issues/68))
- It writes the same bookmarks everything else does, so a mark made at the wheel is in the player's
  list, under Settings → Bookmarks, and in the browser. The moment is read the instant you press,
  not when the network write finishes, so it lands on the line you heard.
- The action sits in the overflow rather than taking a slot from the −30s / +30s skips, which are
  what a driver actually reaches for, and it appears only on a server that can hold a bookmark.
  If the write fails the car says so; a silent no-op would be worse than the button not being there.
- **Find a chapter by name or number.** A **FIND A CHAPTER** field on the fiction screen and in the
  player's chapter sheet narrows the list as you type. A several-hundred-chapter serial was
  scrollable and nothing else; typing `173` or `lighthouse` now gets you there.
  ([#68](https://github.com/jonarihen/TTSRoad-App/issues/68))
- Numbers match from the start, so `17` finds chapter 17 and the 170s rather than every chapter with
  a 17 buried in it. Titles match anywhere, and matching ignores case.
- It filters what is already loaded, so it works offline and has no lag. It is not the server-side
  search added in 0.11.0 and does not replace it: this one answers "which one was chapter 173", that
  one searches the narration text. The All/Unplayed/Ready chips still apply on top.
- Filtering the player's chapter sheet never renumbers the queue — a row keeps the position it holds
  in the book, so tapping it plays the chapter it says it will.

### Fixed

- **The reader follows the audio into the next chapter.** Leaving the reader open on the chapter
  that is playing used to strand it there: the chapter ended, the next one played on, and the page
  kept showing the finished text under a highlight that had stopped moving. It now moves on with
  the audio — new text, highlight running again, scrolled to the top of the new chapter — and the
  same applies when you skip with next or previous rather than reaching the end.
  ([#89](https://github.com/jonarihen/TTSRoad-App/issues/89))
- A reader you opened on some *other* chapter is still never dragged off it, which is the whole
  reason the page does not simply track the player. Reading ahead stays exactly as it was; if the
  audio catches up to the chapter you skipped forward to, the reader starts following again from
  there. BACK still returns to whatever opened the reader, not to every chapter that played on the
  way, and a chapter with no read-along text says so rather than leaving the previous one on screen.

## 0.11.0 — 2026-08-14

Signed with the same pinned key as 0.7.0 through 0.10.0, so this installs directly over any of them
as an ordinary update.

### Added

- **The app can keep the next few chapters downloaded for you.** Settings → Offline → *Keep chapters
  ahead*. Pick 3, 5, 10 or 20 and the chapter playing plus the ones after it are fetched as you
  listen, so a tunnel, a dead zone or a home connection dropping out no longer stops playback. The
  case this exists for is the one nobody remembers to prepare for.
  ([#14](https://github.com/jonarihen/TTSRoad-App/issues/14))
- **It is off until you switch it on.** Every other download in the app is something you asked for by
  name, and an upgrade that quietly started filling a phone is the kind of surprise the Wi-Fi-only
  default exists to prevent.
- **It cleans up after itself, and only after itself.** Chapters it fetched are deleted once you are
  past them, so the space it uses stays roughly constant instead of growing all the way through a
  serial. Chapters *you* downloaded are never deleted automatically, however far behind you they
  are — including ones downloaded by earlier versions, which are all treated as yours.
- Switching it back off gives the space back, deleting what it fetched and nothing else. It obeys
  the existing **Download on Wi-Fi only** switch, so on the default settings it never spends mobile
  data. Alternating between two books keeps a window in each rather than re-fetching the one you
  just left.
- **An Up Next queue that spans books.** Long-press any chapter for **Play next** or **Add to
  queue**. It is the same queue the browser drives, so a chapter lined up on the phone is there in
  the browser too.
- **Android Auto gets an "Up Next" browse node**, listing the queue across fictions with each
  entry's book as its subtitle. The node appears only on servers that can back it.
- **A book no longer just stops at the end.** When the last chapter finishes, the server decides
  what follows: the head of your queue, or — if your account is set to keep going — the oldest
  unplayed chapter in your library. Set it to stop and nothing changes.
- **Admins can add and delete fictions from the phone.** Paste a Royal Road URL or ID into **All
  fictions** to start tracking it. The fiction screen can remove a fiction and its chapters, audio,
  saved positions and bookmarks after an explicit destructive confirmation.
- Both controls appear only for an admin connected to a server that advertises fiction management;
  the server still enforces every write and older servers keep the controls hidden.

### Changed

- Playing a fiction is unchanged: tapping a chapter still queues the whole book in order and
  auto-advances within it. The server queue only gets a say once that book is finished.
- **Search the text of your chapters, not just the titles you have loaded.** The search box filtered
  whatever the app had already fetched and could not match chapter text at all. A new **Search
  chapters and text on the server** action finds fictions, chapter titles and the narration itself —
  so "which chapter was the bit about the lighthouse in" now has an answer.
- Results are grouped by what matched, with the passage shown, and tapping a text result opens the
  reader at that chapter.
- **The instant filter is unchanged and still first.** It works offline and has no lag; server
  search is a second, explicit action rather than a replacement, so searching still works with no
  connection. Servers without search show only the local filter.
- **Your library is now yours.** The app showed whatever the server held and called that "my
  library", while the web showed a followed subset — so the two disagreed about what the library
  even was. A **FOLLOW / FOLLOWING** toggle on the fiction screen now puts a book on your shelf or
  takes it off, and the home screen shows the shelf.
- **All fictions browses the whole server**, which is where you follow something from. Unfollowing
  leaves the fiction on the server; it stays reachable from there.
- Servers whose library is still one shared list show none of this, rather than a toggle they
  cannot honour.
- **Reader appearance and Hide played now follow your account.** Text size, page colour and how
  much is highlighted were settings on this phone; so was the chapter filter, which is the web's
  **Hide played** under another name. The 0.10.0 notes flagged that the two disagreed with each
  other. They no longer do.
- **A sleep timer default.** Pick a duration in Settings → Playback and the player's sleep sheet
  marks it. The app had no concept of one before; the account has always had somewhere to keep it.
- Everything still works with no network and on a server too old to hold account preferences —
  the phone's own copy is what the app reads, and syncing improves on it rather than replacing it.
- **Bookmarks.** Tap **BOOKMARK** in the player to mark where you are, without stopping playback.
  Marks are listed under Settings → Bookmarks, and tapping one opens the reader at that chapter.
- **They are the same bookmarks the browser shows.** Not a copy — the same records, so one made on
  the phone appears in the web bookmarks page and deleting it in either place removes it from both.
- Servers without bookmark support show none of this, rather than offering a button that fails.
- **Jump back reaches across devices.** The player's jump-back sheet used to list only moments this
  phone recorded. It now also shows where the account was listening in the browser or on another
  device, so "where was I at 23:49" is answerable on the device you happen to be holding.
- The phone keeps its own full-resolution trail exactly as before — that is still the deepest
  jump-back reach of the three clients, and it works with no server and no network. What is new is a
  much coarser five-minute trail written to the account and merged into the same sheet. Where a
  moment was recorded both ways, the local copy is the one shown.
- The shared trail is kept bounded on purpose. Bookmarks and breadcrumbs share one budget on the
  server, so a client writing breadcrumbs without limit would eventually spend the whole allowance
  and start refusing to save the marks you made deliberately.
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

- **Speed, skip interval, skip silence and volume boost stay on this phone deliberately.** A phone
  on earbuds and a laptop on speakers want different values, and signing out should not reset them.
  This matches the desktop client's reasoning.
- A setting changed in the browser wins here only when it is genuinely different. The app has four
  highlight modes to the web's three and its own **Night** page, so "the same setting" is not always
  the same value — the phone keeps the more specific choice rather than being flattened to the
  nearest web equivalent.
- **The speed picker goes up to 3.0x.** The clamps always allowed 0.5–3.0, but the sheet only
  offers presets, so the list itself was the real ceiling and it stopped at 2.0x. It now offers the
  same nine steps as the desktop client, spanning the whole range the server's `playback_speed`
  spec accepts.
- 0.8x is no longer one of the presets. Anyone listening at it keeps it: the sheet adds whatever
  speed is actually set to the list when it is not a preset, so the current speed stays visible and
  reachable rather than being snapped to 0.75x silently. Same mechanism will cover a speed that
  arrives from the account once preferences sync.

### Fixed

- **The sleep wind-back field now works with the numeric phone keyboard.** Enter a four-digit
  24-hour time such as `2349` or `0945`; pasted and hardware-keyboard values such as `23:49` remain
  valid. The old numeric keypad often had no colon key, making the only accepted format impossible
  to type. ([#85](https://github.com/jonarihen/TTSRoad-App/issues/85))

- **Listening offline no longer loses your place, and no longer overwrites a newer one.** A position
  recorded with no connection used to be discarded outright, and the next write that did get through
  carried no timestamp — so a phone reconnecting after a long offline stretch could silently roll
  back a position you had reached in the browser since. Positions are now queued on the device with
  the wall-clock moment they were recorded and sent to the server's timestamped batch endpoint,
  which applies only the genuinely newer one.
- When your phone's position does lose to a newer one, it is dropped rather than retried forever,
  and the app takes the server's position instead of holding one that no longer exists.
- The queue survives the app being killed, which is the normal case for a phone that goes offline
  and stays offline. It keeps one position per chapter, so an eight-hour night is a handful of
  entries rather than two thousand.
- On a server without batched progress the old single-chapter endpoint is still used — unchanged,
  and still unordered — but a position recorded offline is now retried instead of dropped.
- Positions are stamped to the millisecond rather than the second. The browser stamps that finely,
  and the server keeps whichever is newer, so a rounded-down stamp from the phone could lose to a
  browser write from earlier in the same second and roll your place back.

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
