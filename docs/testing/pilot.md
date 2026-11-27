# Reader pilot and release checks

No physical-device or reader results are claimed by this checklist. Record actual observations; a passing compile or Robolectric fixture is not a substitute for them.

## Before inviting readers

- Use a disposable device or emulator profile; do not uninstall a reader's existing copy.
- Use the official signed APK and verify its published SHA-256. For upgrade tests start from a compatible released version.
- Use original or openly licensed comic panels and a small CBZ/PDF. Record exact device model, Android version, APK tag, network conditions, time and outcome.
- Do not collect titles, history, or identifying information from participants unless they separately consent.

## Five repeatable checks

| ID | Steps | Pass condition |
| --- | --- | --- |
| U1 | Install the previous signed release; import a CBZ and PDF, download an available chapter, scroll mid-panel, read one chapter, record daily time. Install the candidate over it. | Library, files, poster, bookmark, completed state and daily time remain. Any loss blocks stable release. |
| R1 | Open a long vertical chapter; cross 1.25x repeatedly, pan, double tap, rotate, close and reopen. | No visible flicker or jumps during pinch; progress remains near the expected position; no accidental completion. |
| D1 | Queue two chapters; interrupt network or stop the process; restart with permitted Wi-Fi, retry and cancel another job. | No phantom ready chapters or duplicates; completed downloads remain readable offline. |
| B1 | Export JSON; re-import twice; try an invalid/truncated copy and a copy whose chapter file is absent. | Preview agrees with actual matches; valid bookmarks stay; time does not double; invalid file rejected; absent files skipped. Clean-install reconstruction is still outstanding. |
| T1 | Open the same local, cached and newly downloaded chapter on a fixed network and log first-panel latency; lock screen and resume across midnight. | Time to first panel is recorded by type; reading time excludes background/locked periods. Record measurements, not estimates. |

## Pilot record

Start with 10–15 volunteers. Observe five independent installation attempts and aim for at least four successful first readings. For the first ten launches with known outcomes, at least eight should reach a visible panel within a day before treating first-session work as validated. Use a fresh group to repeat if a gate fails.

| Date | APK | Device/Android | Scenario ID | Network | First-panel seconds | Outcome | Failure reason |
| --- | --- | --- | --- | --- | ---: | --- | --- |
| — | — | — | — | — | — | Not yet measured | — |

Do not infer installations from APK download counts. Revisit the streaming prototype only if repeated first-session delays are observed on comparable chapters and networks. Keep offline deletion disabled until reading metadata can survive file removal and restoration.

## Paired opening-performance experiment (0.3.0)

Compare 0.2.13 and 0.3.0 on separate disposable profiles with the same device, chapter, page count, network and source. Never downgrade a populated installation. Record at least 10 attempts per version and opening type, including failed attempts and bytes transferred. Alternate versions or use matching network conditions; distinguish cached, local and newly downloaded chapters. Report median latency for successful attempts, success fraction and range; do not infer a speedup from CI or combine different sources. The app shows a rolling median of up to 20 successful openings per type for 0.3.0; older last-only metrics are not included. No measurements yet.

## Tile prefetch comparison (0.3.1)

Compare 0.3.0 and 0.3.1 using identical long CBZ and PDF chapters on disposable profiles. Observe blank-tile time, frame timing, peak memory and scroll/zoom/rotation behavior during the same slow-scroll and fast-fling sequences. Test a malformed image and rapid direction changes. A single speculative decode can finish before a newly visible tile; no extra worker is created. Record actual results before claiming improved smoothness. No device results yet.

## Page index comparison (0.3.2)

Use a real long CBZ and PDF on disposable profiles. Compare 0.3.1 and 0.3.2, recording cold index miss and subsequent index hits separately; test imported and freshly downloaded files. Delete only the derived page-index sidecar in a test profile and verify opening still works and positions match. Repeat with a truncated sidecar and a restored ZIP backup (index absent). Record first-panel latency, page count, position and errors. No device measurements yet.

## Interactive priority (0.3.3)

On an unmetered network start a long bulk download and wait until at least one page has arrived. Tap another chapter: at the next safe image boundary, the bulk job must return to the queue with its partial archive removed, and the interactive chapter must start before any queued bulk job. After completion the original job may restart from page one. Tap the currently running bulk chapter in a separate run: it must continue without restarting or duplicating. Repeat with a metered network, bulk downloads disallowed, and an explicit chapter request; queued bulk jobs must stay blocked. Record wait time, bytes retransferred, failures and progress before and after restart. Also test cancellation, process death, offline use and bookmark retention. No physical-device results yet.

## Next chapter preparation (0.3.4)

The signed preview is published at [v0.3.4](https://github.com/Lephiziel/panelume/releases/tag/v0.3.4). CI reports 43 tests, with zero failures or skips; this is not a substitute for physical-device testing.

In a disposable profile, enable the new option, open a downloadable catalogue chapter, read contiguously past 75% and verify at most one following chapter is queued when idle. Repeat with the option off, metered network blocked, a paused queue, a failed job, a previously evicted successor, an external successor and the final chapter. Confirm no automatic duplicate or retry. Tap the end card after the download completes and verify the actual next chapter opens at its own saved position. Also test navigating away during catalogue lookup and process restart. Record actual wait time and network bytes. No device results yet.
