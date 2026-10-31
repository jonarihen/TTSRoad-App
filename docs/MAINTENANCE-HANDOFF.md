# Maintenance run handoff (2026-10-28)

State left by an autonomous maintainer run that ran out of capacity. The next maintenance
run should start by reading this file, then resume from "Still to do".

## Completed (merged or ready to merge)

- **PR #515** `fix/480-sign-out-flush` — flushes offline progress before sign-out with a
  "Sign out anyway" warning and a not-revoked notice. Full `test lint` passed (2450 tests).
- **PR #518** `fix/513-address-probe` — probes single-label LAN hosts and shows
  "Can't reach this address yet" quietly. Full `test lint` passed (2437 tests).

## In progress (pushed as WIP, NO PR yet, NOT run against the full gate)

- **`fix/481-session-playback-errors`** (issue #481) — compiles; classifies playback errors
  (`media/SessionPlaybackErrors.kt`, `player/PlaybackErrorPresentation.kt`) and publishes them
  on the Media3 session, plus a Details fold on the phone banner
  (`PlaybackErrorBannerTest`, `SessionPlaybackErrorsTest`). TODO: run focused tests, then the
  full `test lint`, review the diff, open the PR. Likely needs a rebase onto any #482 merge
  (both touch `PlaybackErrors.kt`/`TtsRoadMediaService.kt`).
- **`fix/485-end-of-chapter-played`** (issue #485) — compiles; decision taken: a sleep-timer
  pause does not by itself mark the chapter played (`PlayedThreshold.reached(deferForSleepTimer=…)`,
  `media/SleepTimerProgress.kt`). TODO: same as above — focused tests, full gate, PR.

## Still to do (in priority order)

Batch 2: #483 cold-start resumption deadline; #486 persist chapter lists per fiction (offline
browse); #499 Auto Continue/Recent row caps + subtitle; #487 fine speed steps (WIP branch
`feat/487-fine-speed` exists, paused); #510 key chapter rows by id; #512 arbitrary range mark
played/unplayed; #488 chapter-state listener wording (WIP branch `fix/488-chapter-state-wording`,
paused); #508 empty-home guidance; #489 error copy for non-sysadmins.

Batch 3: #491 readable-text default at ≥1.3 font scale; #492 heading semantics; #493 spoken
descriptions; #494 a11y bundle (chips, grid titles, login error state, live regions).

Batch 4: #495 grouped notification names + Play/Queue all; #496 New chapters grouping/lazy/badge;
#501 widget 48 dp targets; #500 sleep-timer icon/haptic/widget button.

Batch 5: #509 cache feed/bookmarks/stats; #514 history cap by time + stats range picker (server
already supports `weeks`); #511 drag-to-reorder queue (server accepts full-list `reorder`);
#497 downloads cleanup + cross-library overview.

Batch 6: #504 build-time per-host CA trust + hostname-mismatch message (README documents the
decision #366: narrowest form is a build-time `<domain-config>` for a named host from
`local.properties`, not a runtime toggle); #505 automatic LAN/Tailscale failover (design in
#372 comment 1: active address, fall back on connect/DNS failure, verified-alternates only);
#498 Auto chapter skip (verify on head unit; check existing `DefaultHeadsetButtonsSeek` setting);
#503 sleep-timer auto-arm window; #502 jump-back rows (chapter text, doze-off estimate).

## Out of scope / blocked

- #70 `manual-only` label — do not touch without explicit assignment.
- #152 and #14 — need a physical device (airplane-mode download test, minified-build instrumented
  run); not closable from a read-only environment.
- #175 — remaining half needs a Firebase project + server service-account credential
  (owner-provisioned); client polled half already shipped.
- #490 — terminology ("fiction" vs "books/stories") is a product decision for the owner; left
  open without a fix.

## Environment notes

- Gradle slot wrapper: `/tmp/opencode/maint/gw` — always use it instead of `./gradlew`
  (host is memory-constrained; two build slots, waits for ≥7 GB free, `--no-daemon`).
- Each issue has a worktree under `/tmp/opencode/maint/wt-<n>`; main checkout is
  `/Projekts/TTSRoad-App` and must stay untouched. `local.properties` already copied per worktree.
- Baseline: `main` at 3832e13 (0.21.0), `test lint` passes (2434 tests). CI: single self-hosted
  runner, ~9 min/run — merge one PR at a time.
