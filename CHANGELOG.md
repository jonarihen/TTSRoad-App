# Changelog

Notable changes to the TTSRoad Android client.

## Unreleased

### Added

- **The shelf now reads the progress answer the server already sends.** Each fiction in the
  library response carries one caller-scoped aggregate — chapters ready, played and left, plus
  total and remaining listening time — computed in one grouped query. The app discarded it and
  could only recover the same answer after opening every book and downloading its complete chapter
  list. Grid cards now state time and chapters left directly from that aggregate; the detail screen
  prefers the same answer when it is available, including the server's rounding, so the phone and
  web shelf do not maintain separate arithmetic. The browse order gains **Most left to hear** for
  absolute time and **Least finished** for the remaining share of a book. An older server's missing
  key remains null, renders no invented `0 left`, and sorts after known answers.
  ([#163](https://github.com/jonarihen/TTSRoad-App/issues/163))

- **The shelf can be put in an order.** "All fictions" offered a text filter and nothing else, so
  finding the book that gained a chapter yesterday meant remembering its name. It now sorts by
  recently updated, recently added, title, author or rating. The dates turned out to have been on
  the wire the whole time — the backend has serialised `created_at` and `updated_at` on every
  fiction since before this app existed, and the client had simply never decoded them, which is why
  no order but the server's own was ever possible. **Recently updated** is deliberately not called
  *new chapters*: it follows the fiction row, and the poller touches that row whether or not a check
  found anything, so it means *recently active*. The sheet says so under the option rather than
  leaving it to be discovered. Nulls sort last everywhere — an older server that never sent a date
  is saying "we were not told", which is not "a long time ago", and letting it read as the latter
  would bury the book that actually just arrived. The control is the browse header's own action
  rather than another full-width button, and its label is the order currently in force, so the grid
  never has to be read to work out how it is arranged.
  ([#164](https://github.com/jonarihen/TTSRoad-App/issues/164))

### Changed

- **The fiction screen leads with one button instead of ten.** For an admin the header stacked
  RESUME, FOLLOW, DOWNLOAD, RETRY, CHECK FOR NEW CHAPTERS, SHARE PODCAST FEED, REGENERATE FEED LINK,
  EDIT DETAILS, MAINTENANCE and DELETE FICTION — ten full-width bands of identical weight, with four
  paragraphs of explanation threaded between them, above the chapter list the screen exists for.
  The code already sorted them by rarity and said so in its comments, but ordering ten identical
  rectangles by rarity does not make them fewer; it moves the wall further down the page. Now:
  **RESUME** alone as the filled control, **FOLLOW · DOWNLOAD · MORE** on one line under it, and
  everything else behind MORE as rows that state their consequence — which is the only place a
  sentence like "regenerating makes everyone re-subscribe" can sit without the reader having to
  guess which button it belongs to. The door is deliberately not admin-gated: checking the source
  for new chapters and handing the feed to a podcast app are things any reader does, and the sheet
  gates its own rows one by one. **Retry failed chapters** is the one action that stayed on the
  face, and it stays because it is conditional on a fault rather than permanent — it appears beside
  the count that reports the problem and disappears when the problem clears, which was the whole
  complaint in #107. The header had no layout test before this, which is some of how it reached ten;
  it has one now, and it asserts rank rather than appearance.
  ([#160](https://github.com/jonarihen/TTSRoad-App/issues/160))

- **Settings reads as bands now, not as one unbroken column of cards.** The screen was already
  grouped — nine `// Session`, `// Server`, `// Playback`, `// Audio` captions and the rest — but in
  the weakest idiom the app has: an accent line with no rule under it, which at eleven repetitions
  down one scroll separates nothing. They are the section rule now, the same landmark the home
  screen uses. Two bands that were drawn from their own files, **account security** and **server
  storage**, join them: a header only `SettingsScreen` could draw is most of the reason those two
  ended up with a caption in the first place. **Playback** and **Audio** were one subject filed
  under two look-alike cards — skip silence and volume boost are how a chapter *sounds*, which is
  not a different question from how it *plays* — so they are one band, divided rather than doubled.
  Which kicker each band wears follows the rule written down with the section rule: a number for a
  band drawn on every visit, a mnemonic for one a capability or an admin flag can take away. That
  distinction is now a list in one place rather than eleven independent decisions, and a test holds
  it there — the failure it guards is the eleventh band, added behind a capability, given the next
  number in the sequence because the band above it has one, quietly making every number below it
  depend on what the server happens to publish.
  ([#162](https://github.com/jonarihen/TTSRoad-App/issues/162))
- **The player's eight equal buttons now have two ranks, and the app has a written rule for rank.**
  AARIS says no radius, thin border, mono uppercase label. Applied to one control that is handsome;
  applied to every control on a screen it makes a stack of identical grey rectangles with nothing
  for the eye to land on. That is what "too many buttons" actually was — 52 full-width controls, a
  fiction header reaching ten of them, and a player whose eight tertiary actions all rendered in the
  same accent orange as pause, because Material's `TextButton` takes its colour from the scheme's
  primary. Colour was the only thing differentiating anything, and colour here carries *severity*,
  not rank, so a rare destructive action and an everyday one looked equally loud.
  The player now splits along the line that survives how it is actually used — at the wheel, on
  headphones, phone locked. **Acts on the moment being heard** (speed, sleep, bookmark, said wrong)
  sits first and keeps the accent; **leaves the player** (read, jump back, chapters, up next) is
  muted below it. Bookmark and Said wrong deliberately stay in the loud group: pressing them without
  looking is the entire point of those features, and demoting them would have undone one to tidy the
  other. The rule behind it is written down beside the components rather than left to taste, in
  three ranks — one filled primary per screen, secondary in a *row* rather than a stack of
  full-width bands, and anything rare or destructive as a row in a sheet. The test for the third
  rank: if a control needs a sentence under it to be safe to press, it is not a button.
  That row is now a shared primitive. It had proved itself twice as a private helper in two sheets
  and could not be reached a third time; it also picks up the 48 dp touch target it never had, which
  matters where its neighbour deletes a chapter for everybody.
  ([#159](https://github.com/jonarihen/TTSRoad-App/issues/159))
- **The section rule now runs through the whole app instead of just the home screen.** The accent
  `§` kicker, the uppercase title and the hairline under them are what make the home screen read as
  designed rather than assembled — and they appeared on exactly three screens out of thirteen. The
  reason turned out to be mechanical rather than aesthetic: the component was private to
  `MainActivity`, so the screens living in their own files could import `AarisCard`, `MetaText` and
  `AarisTag` but not this one. What grew in its place was a weaker stand-in — a plain `// Something`
  accent line, 54 of them against the header's five. Bookmarks, pronunciation reports, Up Next,
  device sessions, the server log and All fictions now carry a real header, and All fictions gained
  a count that finally says what its filter did (`12 OF 240`). The `//` line keeps the job it is
  good at: a caption *inside* a card or a sheet, where a full rule would be too much furniture. The
  split between the two is written down on the component now, along with the rule for which kicker
  to use — a running ordinal for sections that are always present and always in the same order, a
  short mnemonic for anything conditional. A conditional section must never be numbered: the fiction
  screen shows why, since numbering there would make **Chapters** `01` or `02` depending on whether
  the reader happened to leave a bookmark. The header's trailing action also picks up the 48 dp
  touch target it never had, which matters most where it sits at the top of a list that scrolls.
  ([#158](https://github.com/jonarihen/TTSRoad-App/issues/158))

## 0.13.0 — 2026-08-26

### Added

- **Choose how a fiction's next chapters sound without reaching for the web console.** The server
  already accepted a voice and synthesis rate on the fiction update route, but the phone had no way
  to learn which voices existed. Admins now get a Narration card in the fiction editor: its picker
  groups the server's full voice catalogue by locale, opens beside the current narrator, and can be
  searched by voice, language, region or gender instead of presenting several hundred names as one
  list. The rate field accepts the pipeline's signed-percent form and catches a typo before it turns
  into a failed conversion hours later. The editor says the important consequence at the point of
  the change: existing audio keeps its old voice and pace; only future conversions use the new
  choice unless **Re-narrate every chapter** is run separately. Older servers and non-admin accounts
  never see the controls, and the capability panel now reports the voice list as used.
  ([#156](https://github.com/jonarihen/TTSRoad-App/issues/156))
- **The chapter you are on, on the home screen.** Pressing pause meant unlocking the phone, finding
  TTSRoad, waiting for it to open and then finding the player — three steps and a few seconds for
  something the launcher can do in one tap, and the phone spends much of its TTSRoad time in a car
  mount where those steps are worse than merely slow. There is now a **continue-listening widget**:
  the cover, the book, the chapter, how much of it is left, and play/pause with the same
  30-second skips the notification offers. Its buttons act on the one real playback session, so a
  tap here and a tap on the notification are the same thing to the player — and pressing play with
  the app closed starts it exactly as a Bluetooth button does, resuming whatever was last heard.
  Resize it: one cell high keeps the book and pause, two adds the time remaining and the skips.
  The interesting problem was honesty about what it knows. A widget cannot hold a connection to the
  player — the launcher draws it long after Android has reaped the app — so the service leaves a
  small note behind on the paths it already runs, and the widget renders that. If the note stops
  being updated, the widget stops claiming to be playing and says **Last heard** instead: a pause
  button and a moving progress bar over audio that actually stopped at 2am invites a tap that does
  the opposite of what it looks like. Signing out removes the note rather than blanking it, so the
  previous account's book cannot survive on a home screen.
  ([#150](https://github.com/jonarihen/TTSRoad-App/issues/150))
- **"That name is said wrong" is now something you can say from the car.** Wanting a pronunciation
  rule starts with *hearing* the mispronunciation, and that happens forty chapters into a serial, on
  headphones or at the wheel — nowhere near the browser where rules are actually made. By the time
  there is a keyboard the spelling is gone and so, usually, is the chapter. The player gains **SAID
  WRONG** beside BOOKMARK, and the media session gains a flag action that reaches the Android Auto
  overflow, the media notification and the lockscreen: one press, phone still locked, playback
  untouched, and the chapter and the exact second are filed for review in a browser later. When a
  timed read-along document happens to be loaded, the word under the playhead goes with the report;
  most of the time none is loaded, and that is fine by design — a report that names ten seconds to
  listen back to is the whole value, so the action never waits for a word and never refuses without
  one. Settings → **PRONUNCIATION REPORTS** is where they land, open ones first with a delete on
  each, because a press that files something invisible and cannot be undone is a press nobody makes
  twice. Everything the small screen buys nothing for stays on the web: the rule editor, the dry
  run, the impact list and the raw → cleaned → spoken preview are desk work, and were never the
  reason to reach for a phone mid-chapter. Every surface disappears entirely on a server that cannot
  store a report — the backend gates writing as well as reading, which matters more than usual for a
  control whose entire point is being used without looking at it.
  ([#125](https://github.com/jonarihen/TTSRoad-App/issues/125))
- **The server's own log, on the phone.** "Why did that chapter fail" and "is the poller running"
  are questions you have with the app open, and the answer was a laptop away: the pipeline's log
  lived only on the web console, so the client that made you want to ask was the one client that
  could not tell you. Settings → **SERVER LOG** now shows it, newest first, filtered by level or
  narrowed to one book by tapping the book on any line. It pages backwards on the server's own
  cursor rather than an offset, so walking back through a busy night cannot show the same failure
  twice. An empty list is treated as the answer it usually is rather than as a shrug — "no errors,
  nothing on this server has failed" is not the same sentence as "this server's log is empty", and
  neither is the same as a server too old to publish one, which says so plainly instead of showing
  a blank screen. Read-only and admin-only, exactly as the server has it; there is no route that
  writes or clears a log and none was added.
  ([#124](https://github.com/jonarihen/TTSRoad-App/issues/124))
- **How much disk the server is using, beside how much this phone is.** "Storage" in Settings meant
  the download cache — a few hundred megabytes on a phone — while the volume actually at risk of
  filling up is the one the server writes its MP3s to, and seeing that meant opening a browser.
  A new card sits next to the app's own cache figures with the volume's free space, the totals for
  audio, exports, source EPUBs, cover art and voice samples, and the per-fiction table, largest
  first, so the serial holding three hundred gigabytes is obvious at a glance. Audio belonging to
  excluded chapters is called out separately, because that is the part that could be reclaimed. A
  server without ffmpeg says so where the export total is, rather than letting a missing encoder
  surface as a failure after you have already asked for an export. Every size on the card is the
  string the server rendered, never one the phone worked out again: two clients disagreeing about
  whether something is 1.4 GB is a support question nobody needs. Read-only on purpose and staying
  that way — the orphan scan and every delete stay on the web console, where an irreversible
  removal of somebody's audio library can be confirmed properly.
  ([#124](https://github.com/jonarihen/TTSRoad-App/issues/124))
- **Your password and your second factor, from the phone.** Securing the account the app signs into
  meant opening the web console — so the one device that holds a long-lived token was the one device
  that could not rotate the credential behind it. Settings now changes a password natively,
  enrols two-factor by handing the secret to an authenticator app, shows the one-time recovery codes
  once and regenerates them on request, and disables the whole thing behind a password confirmation.
  Two details are the reason this is worth doing rather than linking out: a password change hands
  back a replacement token, which the app adopts without dropping the server and session it was
  branded with, and a stale 401 still in flight from the old credential can no longer race in and
  clear the new one — which would have signed you out at the exact moment you secured the account.
  Older servers without the capability keep the controls hidden.
  ([#118](https://github.com/jonarihen/TTSRoad-App/issues/118))
- **The device that does the listening can finally show it.** The web has had a Stats page for as
  long as TTSRoad has — hours listened, chapters finished against chapters still open, a per-day
  activity grid, streaks, where the hours went, badges — and the app, which writes nearly every
  playback row those figures are counted from, was the only client that could not display any of
  it. Settings → **LISTENING STATS** now shows both halves of the answer. The top of the screen is
  worked out on the phone from the same playback log that powers jump-back: how long you have
  actually been listening today, split by book, with no network involved. Underneath it are the
  account's lifetime totals, streaks, twelve weeks of activity and the milestones, from the server's
  `/api/mobile/stats`. The two are kept apart on purpose, because each answers something the other
  cannot: the local half is a recent window of roughly eight hours and says so rather than implying
  a total it has no way to hold, while the server counts a chapter's hours against the day it was
  last touched and so can total a year but not a morning. A server too old to publish the endpoint
  says that plainly, which is a different sentence from an account that has not listened to
  anything yet. ([#117](https://github.com/jonarihen/TTSRoad-App/issues/117))
- **A book that is already on the phone can go straight into the library.** An EPUB bought in a shop
  app or mailed to yourself needed a laptop and a browser to get into TTSRoad — the server has
  accepted uploads on the mobile surface all along and nothing here used them. Admin accounts now
  get **UPLOAD AN EPUB** beside the add-by-URL field on the browse screen: pick the file and the
  server splits its chapters and starts narrating, exactly as the web console does it. A file that
  is not an `.epub`, or one past the size limit the server advertises, is refused on the phone
  rather than after a hundred megabytes have been pushed up a mobile connection, and the book is
  streamed off the device instead of being read into memory first — a large illustrated one uploads
  without the app ever holding it. A server that recognises the book from a previous upload says so
  in its own words, which is an answer rather than an error.
  ([#114](https://github.com/jonarihen/TTSRoad-App/issues/114))
- **The audiobook exports on your server, from the phone.** TTSRoad can encode a whole fiction to
  one M4B file, and the only way to find out whether last night's export had finished was to open
  the web console on a laptop. Settings now lists the finished files with their size, running time,
  chapter range and when they completed, and **SHARE LINK** sends one wherever it needs to go. A
  server with no ffmpeg says so instead of showing an empty list that reads as "nothing was ever
  exported". The list is read-only and admin-only, exactly as the server has it — starting an
  export and deleting one stay on the web — and the app does not offer to play these: it streams a
  fiction chapter by chapter with a position in each, which beats one multi-gigabyte file carrying
  a single position. The download carries your account's bearer token, so the link is meant for
  something that can send that header, not for a browser.
  ([#113](https://github.com/jonarihen/TTSRoad-App/issues/113))
- **Podcast feed links, from the phone.** Serving a private podcast feed is what TTSRoad is for, and
  the phone is where a podcast app lives — yet the only way to get a tokenised feed URL onto the
  phone was to mail it to yourself from a laptop. Settings now shows the combined feed and the OPML
  link with a **SHARE** action each, and every fiction screen shares that book's feed. Both go to
  the share sheet rather than the clipboard, because handing the URL straight to a podcast app is
  the actual goal. Regenerating is there too, behind a confirmation that says what it breaks — if a
  private token leaks, the device in your hand is the natural place to respond from.
  ([#115](https://github.com/jonarihen/TTSRoad-App/issues/115))
- **A backup of where you are in everything.** Settings → Listening state saves every position and
  bookmark on the account to a file you choose, and restores one. Audio can always be made again;
  where you are in a four-hundred-chapter serial cannot — and the phone is where most of that state
  is made. Restoring merges rather than overwrites: a position only ever moves forward and bookmarks
  are added, so an old backup cannot undo newer listening.
  ([#116](https://github.com/jonarihen/TTSRoad-App/issues/116))
- **"N failed" is something you can act on.** The fiction screen stated a count of failed chapters
  and offered nothing to do about it — a number you cannot act on and cannot explain says something
  is wrong and then refuses to help. Admin accounts now get **RETRY N FAILED** next to the count,
  and every chapter's long-press sheet gains **Convert again**, which is open to any account exactly
  as the server has it. ([#107](https://github.com/jonarihen/TTSRoad-App/issues/107))
- **Fiction maintenance from the phone.** **CHECK FOR NEW CHAPTERS** sits in the fiction header —
  the answer to "the author posted an hour ago, where is it" without waiting for the scheduler — and
  a **MAINTENANCE** sheet holds fetch-all, re-apply chapter filter, refresh MP3 tags and re-narrate
  everything. That last one asks first, because a four-hundred-chapter serial is four hundred
  conversions. Admin accounts also get exclude and delete on a single chapter. Refresh MP3 tags is
  the other half of the metadata editing that shipped in 0.11.0: until now the app could rename a
  book and could not rewrite the files still carrying the old title.
  ([#112](https://github.com/jonarihen/TTSRoad-App/issues/112))
- **Bookmarks show up where you would look for them.** The only place to see a mark was Settings →
  Bookmarks — a flat, account-wide, newest-first list. The fiction screen now has a bookmarks section
  scoped to that book with a count, and the player draws every mark in the current chapter as a tick
  under the scrub bar, tappable to seek there. That closes the loop the car action opened: you press
  BOOKMARK at the wheel precisely so you can come back to that spot, and coming back used to mean
  Settings → Bookmarks → tap → the reader.
  ([#121](https://github.com/jonarihen/TTSRoad-App/issues/121))
- **The Up Next queue is a screen you can look at.** Since 0.11.0 the app could put a chapter on
  the cross-library queue from two places and show it in none: added the wrong one and you could
  neither correct it nor empty it without plugging the phone into a car or opening a browser. The
  queue now has its own screen, reached from the player's **UP NEXT** action and from Settings, with
  reorder, remove, play-from-here and **CLEAR QUEUE** — the same rows the browser and Android Auto
  see. ([#108](https://github.com/jonarihen/TTSRoad-App/issues/108))
- **What happens when the queue runs out is finally settable from the phone.** The app has read and
  acted on the account's `queue_when_empty` since the queue shipped, and offered no way to change
  it — so the behaviour at the end of a book was set in a browser and only ever observed here. The
  control sits with the queue, as it does on the web.
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

- **A refresh asks what changed instead of refetching the library.** Every pull-to-refresh and every
  screen entry pulled full payloads — including the whole chapter list of a four-hundred-chapter
  serial — to discover, most of the time, that nothing had moved. The server has advertised
  `delta_sync` since before the app could parse it, and the app parsed it and never read it. Now a
  refresh spends one small request asking the sync index what moved, and pulls sparsely for only the
  books it names; when the answer is "nothing", that one request is the entire cost. Cursors come
  from the server, never the device clock, and are committed only after the pull they belong to
  succeeds — a refresh that fails on a bad connection repeats itself rather than skipping the
  chapters it never received. A server without the flag keeps refetching in full, exactly as before.
  ([#110](https://github.com/jonarihen/TTSRoad-App/issues/110))
- **Third-rank text is readable.** The `Dim` token was 2.19:1 against the hover surface and 2.39:1
  on a card, well under the 4.5:1 floor — so remaining time, chapter metadata, bookmark notes,
  search snippets and every Settings explanation were effectively decorative for anyone with
  imperfect sight or an imperfect screen. It now clears WCAG AA on all four surfaces, at the same
  value the desktop client moved to. Error text moved with it: `Danger` was 4.27:1 on the hover
  surface. ([#102](https://github.com/jonarihen/TTSRoad-App/issues/102))
- Disabled controls have a token of their own instead of borrowing the one used for body text, so
  raising that one did not leave an unavailable row looking available.
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

- **Two things your server can do were listed by their database names.** The Settings capability
  panel exists so that "this server is older than my app", "I am not an admin" and "this app is
  broken" stop looking identical — it says, in words, what the server behind you supports. Two
  flags had never been given words: listening statistics and the server's voice list showed up as
  `listening_stats` and `voice_catalogue`, raw column names in a list of sentences. Both are named
  properly now, and the voice list says plainly that this app does not use it — it shows which
  voice a fiction was narrated in and does not offer to change it, which is a different thing from
  the server being unable to. A test now checks the whole set against the server's own list, so the
  next flag added on the backend cannot arrive here nameless.
  ([#120](https://github.com/jonarihen/TTSRoad-App/issues/120))

- **Player, chapter and reader controls are big enough to hit.** The transport buttons, the mini
  player, the four actions on every chapter row and the reader's option chips made their tap area
  exactly as small as the glyph drawn in it — 36 dp on a chapter row, where Read, Download, Mark
  played and Play sit next to each other and a near miss starts a chapter instead of downloading
  it. Every one of them is now at least Android's 48 dp minimum, without anything looking bigger.
  Reader chips also announce themselves as a choice with one selected, which colour alone never
  told a screen reader. ([#104](https://github.com/jonarihen/TTSRoad-App/issues/104))
- **A downloaded chapter can no longer be silently out of date.** Chapter audio is not immutable —
  re-converting, retrying, retagging or re-narrating after a text rule change all rewrite the MP3
  in place, and the URL does not change when they do. A chapter already on the phone therefore kept
  playing the old narration indefinitely, and nothing gave anyone a reason to delete it. The app now
  asks the server what each downloaded chapter's audio hashes to, marks the ones that no longer
  match as **Outdated copy**, and offers to fetch them again — one line on the fiction screen when
  a re-convert touched twenty of them at once. It reports and offers rather than acting: re-fetching
  a book's worth of audio is the user's decision and possibly their mobile data. Downloads made
  before this shipped are adopted as-is rather than assumed stale, so upgrading does not re-download
  a library. ([#109](https://github.com/jonarihen/TTSRoad-App/issues/109))
- **Now Playing fits a landscape screen.** The cover took whatever height was spare, and when there
  was none spare the scrubber, the transport row and every tertiary action were laid out below the
  window with nothing to scroll them into view — so in landscape, split screen, or at a large
  display size, pause and the sleep timer could be genuinely unreachable. Short windows now put the
  cover beside the title instead of above it and scroll, while a phone held upright is unchanged.
  ([#101](https://github.com/jonarihen/TTSRoad-App/issues/101))
- **A server search no longer hides the rest of the screen.** Results were drawn above the only
  part of the browse screen that scrolled, so a search answering in all three groups — fictions,
  chapter titles, narration text — pushed its own later hits and the entire fiction catalogue past
  the bottom of the window, with no way to reach either. The screen now has one scrolling owner, so
  every hit and the catalogue below it stay reachable however much the server found.
  ([#100](https://github.com/jonarihen/TTSRoad-App/issues/100))
- **Settings choices wrap instead of running off the side of the card.** Skip interval, sleep-timer
  default, volume boost, keep-chapters-ahead and keep-streamed-audio each laid their options out in
  a row that could not wrap. On a 320 dp phone, in split screen, or at a large display scale, the
  last one or two choices were placed outside the card — so a preference that existed in the app
  could not be selected from it. They now wrap onto as many lines as they need, and a screen reader
  says which choice is in force rather than only reading the labels.
  ([#99](https://github.com/jonarihen/TTSRoad-App/issues/99))
- **Settings says what your server can do.** The app hides any control the server cannot back —
  which is right, and invisible: a missing button looked the same whether the server was older than
  the app, the account was not an admin, or the app was broken. A **Server** card now lists the
  server's version and every capability it advertises, so a control you expected and cannot find
  has a stated reason. ([#120](https://github.com/jonarihen/TTSRoad-App/issues/120))
- Features the server offers and the app does not yet use say so rather than showing a bare tick,
  and a capability newer than the app is listed under its own name instead of being dropped.
- **Every settings group now says where it is actually kept.** The reader's sheet had claimed
  "Kept on this phone only" since before those settings started following the account, so it told
  you the opposite of what it did — someone avoiding an account-wide change was reassured wrongly,
  and someone wanting the browser to match thought sync was broken. It now says which it is, and
  says it honestly on an older server that genuinely cannot hold them.
  ([#103](https://github.com/jonarihen/TTSRoad-App/issues/103))
- **Speed, skip interval, skip silence and volume boost say they are phone-only**, which they have
  always been and deliberately are — earbuds and speakers want different settings. The old wording
  said only that they survived a restart, which left you to guess the rest.
  ([#126](https://github.com/jonarihen/TTSRoad-App/issues/126))
- **A downloaded chapter can now be read offline, not just heard.** Downloads fetched the audio and
  nothing else, so pressing READ on a plane showed an empty reader for every chapter you had not
  happened to open beforehand — the half of the feature you cannot arrange in advance, missing
  exactly when it is needed. Downloading a chapter now keeps its text and cues with it.
  ([#123](https://github.com/jonarihen/TTSRoad-App/issues/123))
- **They are held rather than cached**, so a flight's worth of chapters cannot push each other out.
  The reader's ordinary cache is bounded and drops the oldest; documents belonging to a download are
  exempt from that, and are released when the download is deleted. Deleting downloads frees them
  too. Chapters you only read stay bounded exactly as before.
- **Line spacing in the reader, and it follows your account.** The reader had text size, page
  colour and highlight; spacing was the one appearance setting the account could hold and the app
  could not offer, so it was the one that did not match the browser. Five steps from tight to airy,
  under **Aa** in the reader. ([#122](https://github.com/jonarihen/TTSRoad-App/issues/122))
- The default spacing is a shade more open than before — the reader used a fixed ratio of 1.65,
  and the shared default is 1.75. Anything you set yourself overrides it.
- **"Mark chapters played automatically" is honoured, and is a setting.** The account has held
  `auto_mark_played` all along and the web player has always respected it; the app ignored it and
  marked a chapter played past 96% regardless. That was worse than not supporting it: the phone
  writes played state to the same records the browser reads, so unticking the box in a browser was
  being overridden by the device doing most of the listening. It now follows your account, and
  there is a switch for it under Settings → Playback.
  ([#119](https://github.com/jonarihen/TTSRoad-App/issues/119))
- Marking a chapter played by hand still works with the setting off — it governs the automatic
  path only, which is how the web reads it too.
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
