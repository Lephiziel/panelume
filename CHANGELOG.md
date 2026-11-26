# Changelog

## 0.2.11 (preview)

- Add individually selectable settings restoration from existing portable library ZIPs: language, download-network policy and last search.
- Keep all choices off by default, warn when mobile-data downloads would be enabled, and avoid submitting a restored search until the reader opens Search.
- Ignore invalid or unknown preferences; apply selected settings only after successful library recovery. No database or backup-format migration.

## 0.2.10 (preview)

- Add a local ZIP library backup with version-1 progress metadata, chapter archives and covers.
- Preview and restore into an empty library only, validate archive hashes and bounds, stage files before committing metadata, refuse duplicate restores.
- Preferences and pending download jobs remain excluded from restoration; real-device clean-install verification is required.

## 0.2.9

- Add a read-only offline storage overview with local chapter archive sizes and missing/empty file counts, grouped by series and source.
- Add isolated tests for missing, empty and present chapter archives; no deletion or data-schema change.
- Document repeatable device upgrade, reading, offline and pilot scenarios.

## 0.2.8

- Preview and merge v1 progress exports into existing identical chapter files without replacing valid bookmarks.
- Merge read status and daily reading time monotonically using max, avoiding duplicate imports.
- Skip missing/different content; no clean-install library reconstruction or chapter file import yet.

## 0.2.7

- Add offline regression fixtures for catalogues and queue recovery, and bookmark/export checks.
- Show local-only first-panel opening measurements in settings.
- Export library metadata, bookmarks, daily reading time and settings as JSON; no restore yet and no chapter files included.
- Keep database schema, application ID and existing chapter storage unchanged.

## 0.2.6

- Keep base image tiles stable while pinching and double-tapping; zoom no longer clears the displayed tile cache.
- Decode higher-resolution detail tiles after the gesture, using a separate bounded cache and retaining base images as a fallback.
- Keep the existing database, bookmark format, source IDs and Android package ID unchanged.
- Add tile-geometry and search-alias regression checks; real-device gesture and upgrade checks are still required.
- Empty library offers Find a title and Open a file; the last search is restored after restarting.
- Search shows completed source count and partial failures, with targeted retries and alternate-title fallback.
- Series pages offer direct chapter opening and visible preparation, retry and cancellation controls.

## 0.2.5

- Added pinch-to-zoom from 1× to 4×, double-tap zoom/reset and horizontal panning.
- Increased tile resolution while zoomed and preferred original-quality MangaDex pages for new downloads.
- Expanded MangaDex discovery to all original languages with English chapters.
- Added Internet Archive Comics, Pepper & Carrot and xkcd catalogues.
- Retained the Android application ID, original signing certificate, database schema and chapter storage format.

## 0.2.4

- Added Atsumaru as an additional English comic catalogue using its public JSON API.
- Improved discovery for My Life as a Loser / Loser Life and supported direct Weeb Central series URLs.
- Published an update APK signed with the original 0.2.3 release certificate, preserving Android app identity and existing local data.
- Kept the SQLite schema, reading position files, and existing source IDs unchanged.

## 0.2.3

- Restored the Panelume launcher name and reduced the symbol inside the icon.
- Preserved the application ID and signing identity of Panelume Next 0.2.2 for the distributed APK.

## 0.2.2

- Removed rendering seams between panels.
- Gave decimal chapter numbers room beside their titles.
- Cached series posters separately so they remain visible offline.
