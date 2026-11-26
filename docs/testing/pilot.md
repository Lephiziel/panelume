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
