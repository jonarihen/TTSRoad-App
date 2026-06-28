# Changelog

Notable changes to the TTSRoad Android client.

## 2026-06-28

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
