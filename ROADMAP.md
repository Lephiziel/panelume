# Panelume roadmap

Panelume is an ad-free Android reader for manhwa, manga, manhua, webtoons and comics. The work below focuses on getting into a chapter quickly, reading offline and returning to the same spot.

## Available now — 0.2.6

- Continuous vertical reading with bookmarks and a Continue Reading card.
- Pinch-to-zoom, double-tap reset, and panning; the 0.2.6 hotfix separates the stable base image cache from detail rendering.
- Offline chapters and support for local PDF, CBZ and ZIP files.
- Reading time by chapter and day, plus English and Russian interfaces.
- Empty-library search/import actions, last search, known alternate titles, per-source progress and retry, chapter preparation controls.
- Stable APK download filename and release SHA-256 checksums.

[Download the latest signed APK](https://github.com/Lephiziel/panelume/releases/latest/download/Panelume.apk) · [What changed](CHANGELOG.md)

## 0.2.7 candidate — reliability and progress export

- Synthetic offline catalogue fixtures, queue recovery and bookmark tests.
- Local timing of first visible panel (local, cached, network); collect actual pilot samples before choosing streaming work.
- Read-only JSON progress export; files are excluded. Restore and merge remain a separate gated task.

## Priorities

| Stage | What readers can expect us to work on |
| :-- | :-- |
| **Now** | P0: verify the zoom hotfix on real devices (cross the old 1.25× boundary repeatedly, pan, reset, and resume a chapter); verify upgrades, bookmarks and offline chapters across Android versions. Validate the new download status and cancellation controls; check interrupted downloads and retries. |
| **Next** | Validate the first-chapter improvements shipped in 0.2.6 with pilot readers, add catalogue adapter fixtures, and measure time to first panel before deciding whether streaming is needed. |
| **After that** | Add a way to back up and restore your library and reading progress. Make it easier to see and manage what is saved offline. |
| **Exploring** | A right-to-left page mode for manga, faster online chapter opening and better ways to move between chapters. |

Timing depends on testing and reader feedback. If you try Panelume, tell us where you got stuck or what would make you use it again. [Reader feedback](https://github.com/Lephiziel/panelume/issues/1) · [Report a bug or suggest a feature](https://github.com/Lephiziel/panelume/issues/new/choose)
