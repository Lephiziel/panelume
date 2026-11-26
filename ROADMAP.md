# Panelume roadmap

Panelume is an ad-free Android reader for manhwa, manga, manhua, webtoons and comics. The work below focuses on getting into a chapter quickly, reading offline and returning to the same spot.

## Available now — 0.2.9

- Continuous vertical reading with bookmarks and a Continue Reading card.
- Pinch-to-zoom, double-tap reset, and panning; the 0.2.6 hotfix separates the stable base image cache from detail rendering.
- Offline chapters and support for local PDF, CBZ and ZIP files.
- Reading time by chapter and day, plus English and Russian interfaces.
- Empty-library search/import actions, last search, known alternate titles, per-source progress and retry, chapter preparation controls.
- Stable APK download filename and release SHA-256 checksums.

[Download the latest signed APK](https://github.com/Lephiziel/panelume/releases/latest/download/Panelume.apk) · [What changed](CHANGELOG.md)

## 0.2.7 shipped — reliability and progress export

- Synthetic offline catalogue fixtures, queue recovery and bookmark tests.
- Local timing of first visible panel (local, cached, network); collect actual pilot samples before choosing streaming work.
- Read-only JSON progress export; files are excluded. Restore and merge remain a separate gated task.

## 0.2.8 shipped — conservative progress import

- Validate version 1 exports, preview matches/skips, merge only identical chapter files already present. Existing valid bookmarks are retained; per-day time and read state use max for idempotent imports. No chapter or settings replacement.
- Clean-install reconstruction, offline-file transfer, settings import and device testing remain open.

## 0.2.9 shipped — offline storage overview

- Report chapter archive sizes and missing or empty files by series. No deletion, repair, or full archive validation yet.
- Record device and pilot scenarios in [testing/pilot.md](docs/testing/pilot.md); no device results claimed.

## 0.2.10 preview — portable library ZIP

- Export metadata and registered chapter archives/covers; restore only into an empty database, with strict ZIP paths, bounded extraction and SHA-256 verification before inserting rows.
- Repeated restore refuses to modify the populated library. Existing JSON merge remains available.
- [Signed preview](https://github.com/Lephiziel/panelume/releases/tag/v0.2.10): 17 regression tests passed on synthetic fixtures. Latest stable remains 0.2.9. Full physical-device restore with real CBZ/PDF, preferences transfer, and safe removal of offline files while retaining progress remain open.

## 0.2.11 preview — optional settings transfer

- Preview and individually opt in to restoring language, download-network policy and last search from existing v1 library ZIPs.
- Validate known fields strictly; ignore missing, unknown or invalid fields. No settings are changed if library restoration fails. Never transfer setup completion, diagnostic metrics or pending jobs.
- [Signed preview](https://github.com/Lephiziel/panelume/releases/tag/v0.2.11): 20 regression tests passed. Keep the latest stable release at 0.2.9 until physical-device clean-install recovery is verified. Next: separate offline availability from reading metadata before enabling file removal.

## 0.2.12 preview — free offline space without losing progress

- Separate registered chapter metadata from actual archive presence without a schema migration.
- Explicit single-chapter eviction for catalogue downloads; local imports are excluded. Reopen through the current catalogue and retain chapter ID, reading history and bookmark on same-page-count replacement.
- ZIP backups keep metadata and covers of unavailable online chapters; old complete v1 ZIPs still import. ZIPs with omitted archives require the new reader.
- [Signed preview](https://github.com/Lephiziel/panelume/releases/tag/v0.2.12): 24 regression tests passed. Real-device eviction, restore and re-download checks remain outstanding. Same page count does not prove identical content. Latest stable remains 0.2.9. Next: storage management and first-panel performance.

## 0.2.13 preview — storage management

- Select catalogue downloads by series, sorted by archive size, with completed-only filter enabled by default, selection preview and explicit confirmation.
- Remove only confirmed file identities; skip changed entries, reject active readers/downloads, protect local imports and report partial results.
- [Signed preview](https://github.com/Lephiziel/panelume/releases/tag/v0.2.13): 26 regression tests passed. Real-device storage and recovery testing remains open. Latest stable remains 0.2.9. Next: first-panel performance and pilot measurements before streaming.

## 0.3.0 candidate — opening performance

- Hash completed online chapter ZIP bytes while writing, including the central directory, rather than reopening the whole file for a separate SHA-256 pass. Keep archive/image inspection and durable sync.
- Show last and median successful first-panel latency over up to 20 samples per opening type, local to the device and scoped to this build. No speedup is claimed before measured comparisons.
- Next: collect paired baseline/candidate measurements and live catalogue checks; evaluate bounded prefetch or streaming only after identifying bottlenecks. Device recovery and upgrade gates remain open.

## Priorities

| Stage | What readers can expect us to work on |
| :-- | :-- |
| **Now** | P0: verify the zoom hotfix on real devices (cross the old 1.25× boundary repeatedly, pan, reset, and resume a chapter); verify upgrades, bookmarks and offline chapters across Android versions. Validate the new download status and cancellation controls; check interrupted downloads and retries. |
| **Next** | Validate the first-chapter improvements shipped in 0.2.6 with pilot readers, collect live catalogue checks and pilot first-panel measurements before deciding whether streaming is needed. |
| **After that** | Add previewed, idempotent restore of reading progress, including clean-install recovery. Make it easier to see and manage what is saved offline. |
| **Exploring** | A right-to-left page mode for manga, faster online chapter opening and better ways to move between chapters. |

Timing depends on testing and reader feedback. If you try Panelume, tell us where you got stuck or what would make you use it again. [Reader feedback](https://github.com/Lephiziel/panelume/issues/1) · [Report a bug or suggest a feature](https://github.com/Lephiziel/panelume/issues/new/choose)
