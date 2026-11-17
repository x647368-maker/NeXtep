# Vivo/Atomic Workbench v89 continuation — 2026-11-17 (independent drafts, NOT accepted)

## Canonical location
Repository: x647368-maker/NeXtep.
Base Vivo motion checkpoint: 0a56cd99e1cadc2a7034ad4ebedd37927f96a324.
Combined review-only branch: atomic-workbench/v89-vivo-merged-mirror-checks-20261117.
PR #130: primary-axis direction and whole-box seam continuity; base is the Vivo checkpoint branch.
PR #131: UI-lane move ACK and fresh producer baseline; independent from #130.
PR #132: combined source candidate from #130 + #131; use as the **next test target**, not the latest accepted build.
Unmodified main, original package/signing, 4 slots and device-specific UX are mandatory.

## What was genuinely changed
1. StageSmoothPairedRailCandidate: analytical monotone primary-axis guard for paired quadratic curves, continuous full box geometry across segment joints, both endpoints verified.
2. StageAdaptiveCoveredRailRoute / StageCertifiedRailRoute: fallback candidates must satisfy primary-direction invariant; invalid/nonfinite protected neighbors cannot pass direct-path admission.
3. StageAdaptiveCoveredRailRouteTest: tests for invalid neighbors, intentional reversal and off-axis secondary jump.
4. NeXtepWindowController: native and snapshot move ACK can arrive from workers; now their View/producer-output capture and completion of on-frame main-thread bookkeeping use a scoped once gate and existing main Looper. No task migration moved earlier.
5. Exact reversible before/after for the five source/test files is in scripts/reviewed-v88-animation-first.json with updated Git content blob SHA1. The new safety guard intentionally rejects some legacy paths; this does not magically make old fallback trajectories match Vivo.

## OEM evidence (NOT new validation of these patches)
- User reference: 4454.mp4, Library file_00000000251c8230bb1e9fe4e54b3dc2.
- Vivo component archive: vivo压缩.zip, Library file_00000000d3848209838f02f719cbec2b (62MB, 21 files). Includes services.jar, vivo-services.jar, framework.jar, wmshellapp.apk; archive does not include the complete full SystemUI and launcher animation implementations. Do NOT claim exact OEM easing values extracted.
- OnePlus failure video: 4448.mp4 (Library file_000000002b4c81faa82728ccb0a70925).
- Original device report: 台前调度-故障报告-1794900086604.txt, Library file_000000003f2882118fadad621d797fcf. Device OnePlus PMS110 Android17/API37, v0.9.74-dev(89).
- Recent swap IDs #25-29 report total 645, 752, 773, 603, 713ms. GPU terminal at 366,380,379,364,363ms; handoff target ready at 582,694,711,544,638ms, respectively. This yields terminal-to-target waits 216,314,332,180,275ms. An additional adaptive zero-gap endpoint reveal lasts about 59-62ms. No source-backed grounds to call the waiting stage solved.
- Those five flights recorded paired_covered_adaptive and first GPU receipt 2-8ms; GPU submit max under 8.33ms. Timing data does NOT prove actual visible pixels, and SystemUI CPU/battery data is not attributable to the module in isolation.

## Test and build status — BLOCKING
Old CI run #1494 (run 44042576015) passed development, API35 visual regression and API37 native mirror tests with 649 JVM tests for earlier commit 32cb2eba; it is not acceptance of PR #132.
No successful Kotlin/Gradle, JVM/API35/API37 exact-commit evidence has been obtained for PR #130, PR #131 or PR #132. The connector cannot dispatch an arbitrary workflow; do not substitute a prior unsigned build or a prior signed test APK.
Before any package: exact SHA & full suite, check no effect on notification shade/menu/TextureView, rotation/fold/sleep/abort and four-window geometry. Verify package name, versionCode/signature (original cert SHA256 3703b59c48b1c30df872fbcd8fa01905b9a6c684f151ddbb0d05e5b5726468fe), then privately sign and validate a matching binary. No main merge, public release or update-feed write until user accepts on OEM device.

## Next implementation order
1. Obtain exact-head CI for PR #132 and fix compile/tests, preserving reversal manifest. Do not silently remove failing assertions.
2. Reproduce old #25-29 wait phases using current tracing, identify which main/slot fresh-buffer predicate dominates (not simply timer), improve safely without reparenting task while both mirrored producers still fly.
3. Further isolate legacy fallback route reversal and missing corner/black-shadow invariants; compare actual physical paths to 4454.mp4, not just AOSP emulator.
4. Source missing Vivo SystemUI/launcher code if legally available in user's own dumped resources; avoid copying OEM binaries, private apps or fragile hooks into ColorOS.
5. Device acceptance on OnePlus Ace 7 Pro, OPPO N5 folded/unfolded, and Y700, including low power and repeated exchange.

This note records current evidence and constraints, not a claim of finished Vivo 1:1 replication.
