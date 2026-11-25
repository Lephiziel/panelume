# Panelume roadmap

Panelume is an ad-free Android reader for manhwa, manga, manhua, webtoons and comics. The work below focuses on getting into a chapter quickly, reading offline and returning to the same spot.

## Available now

- Continuous vertical reading with bookmarks and a Continue Reading card.
- Pinch-to-zoom, double-tap reset, and panning; the 0.2.6 hotfix separates the stable base image cache from detail rendering.
- Offline chapters and support for local PDF, CBZ and ZIP files.
- Reading time by chapter and day, plus English and Russian interfaces.

[Download the latest signed APK](https://github.com/Lephiziel/panelume/releases/latest) · [What changed](CHANGELOG.md)

## Priorities

| Stage | What readers can expect us to work on |
| :-- | :-- |
| **Now** | P0: verify the zoom hotfix on real devices (cross the old 1.25× boundary repeatedly, pan, reset, and resume a chapter); verify upgrades, bookmarks and offline chapters across Android versions. Make failed or interrupted downloads easier to understand and retry. |
| **Next** | 0.2.6 candidate: add direct find/import actions to an empty library, remember the last search, try known alternate names, report partial catalogue failures and retry only failed sources, and show chapter preparation/cancel/retry. Next: validate on pilot devices and measure time to first panel before implementing streaming. |
| **After that** | Add a way to back up and restore your library and reading progress. Make it easier to see and manage what is saved offline. |
| **Exploring** | A right-to-left page mode for manga, faster online chapter opening and better ways to move between chapters. |

Timing depends on testing and reader feedback. If you try Panelume, tell us where you got stuck or what would make you use it again. [Reader feedback](https://github.com/Lephiziel/panelume/issues/1) · [Report a bug or suggest a feature](https://github.com/Lephiziel/panelume/issues/new/choose)
