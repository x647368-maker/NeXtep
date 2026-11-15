# P0 evidence checkpoint — 2026-11-15

Production decision: retain v83 (0e0733fe88efe9ceae82990873f960ab279c05c0). No production optimization is adopted, no version increment, no APK delivery or release. This branch contains tests and evidence only.

## Source and reproduction

- PR95 head: e93d1e39618b64d02be95f6d703175cd5310d746; workflow 43691295038; artifact 14152248765 (atomic-android-render-results). Raw report is adjacent.
- PR94 head: 885325a5cfb5ed5910e0a9a43a8e6093ce359bd3; workflow 43691098410; original failing job 132742348494. Retry requested without source changes.
- Medians below use the arithmetic mean of the two middle values, grouped by mode and destination (0 = main). Maximum is reported rather than claiming a robust p95 from only eight observations.

## PR95 observed timings (milliseconds)

| Mode | Direction | n | Query-confirmation elapsed median | Max | Owner busy median | Callback wait median | Initial owner wait median |
|---|---|---:|---:|---:|---:|---:|---:|
| sync | to-main | 8 | 24.837 | 80.921 | 24.337 | 0.000 | 0.075 |
| sync | to-side | 8 | 79.533 | 155.759 | 13.960 | 0.000 | 64.779 |
| async | to-main | 8 | 169.877 | 242.660 | 0.179 | 126.431 | 0.061 |
| async | to-side | 8 | 263.293 | 367.084 | 0.129 | 175.765 | 11.577 |
| async_barrier | to-main | 8 | 134.475 | 177.754 | 0.232 | 114.334 | 0.063 |
| async_barrier | to-side | 8 | 211.275 | 291.790 | 0.113 | 150.680 | 7.422 |
| async_batch | to-main | 8 | 125.734 | 158.327 | 0.123 | 100.741 | 0.096 |
| async_batch | to-side | 8 | 149.197 | 236.372 | 0.097 | 103.543 | 32.538 |

Every recorded move used one task query. Ordinary async and async Handler used two callbacks; batch used one. Sync used zero callbacks. Batch reduces the owner round trip, not query count.

| Mode | Frame gap p95 | Max gap | Gaps >33ms / all samples |
|---|---:|---:|---:|
| sync | 83 | 149 | 31/117 |
| async | 83 | 149 | 46/124 |
| async_barrier | 99 | 133 | 35/112 |
| async_batch | 99 | 116 | 37/101 |

Batch query-confirmation medians are 26.0% lower to-main and 43.3% lower to-side than ordinary async in this single run. This does NOT establish production exchange, frame pacing, phone latency, temperature or power improvement. Frame p95 worsened from 83 to 99ms for both candidates; sample durations differ, so raw >33ms counts cannot be compared as dropped-frame counts.

## Why no production adoption

1. All four MainActivity tasks run in the same process as the owner callback; their lifecycle/render work competes with the measurement thread. SystemUI plus third-party apps is a different arrangement.
2. elapsed_ms ends at matching task display observation. It excludes fresh-buffer wait and the production 300ms animation, crop/input, layout and overlay handoff. It is not full swap duration.
3. Only the return-to-side path awaits ImageReader output; there is no independent main destination visual-readiness measurement. Frame gaps combine both directions and 80ms padding.
4. Only eight moves per direction per mode, on a shared software-rendered CI host. Handler results do not isolate synchronization barriers from runnable owner work.
5. Batch probe starts observation deadline on the worker; ordinary async starts after the first owner callback. Cancellation, session expiry, rollback and late read publication are not exercised by this performance probe. Production currently checks stillCurrent before and after every query and after the actual move response. Never copy the probe into production without retaining these gates.
6. Metadata cache candidate was slower again: 16.632ms baseline vs 33.010ms cached per 128 decodes in the reported statistic. Do not adopt.

## PR94 failure and test repair

Original failure: RefinementRegression.kt:133, Last scrolled command was not dispatched. This occurs before TaskMigrationRegression runs; PR94 therefore has no completed Android15 migration report from attempt 1. Emulator boot and APK installation succeeded: this is a real test assertion failure, not a boot/network failure.

AtomicPopupMenu performs a 70ms HWUI fade then anchor.post(action). The grouped-action test already allowed 2 seconds for actual callback delivery, but the last scrolled action asserted immediately after a fixed 180ms settle. This is inconsistent and exposes a timing race under load. Both action paths now await a CountDownLatch with a 2 second bound and verify the exact action ID via AtomicInteger. Missing/wrong callbacks still fail; no repeated tap, skipped assertion or production change. Owner-owned popup state is checked on main. A passing retry alone would indicate intermittency, not prove all menu bugs absent.

## Remaining work / handoff

- Obtain repeated separate-process real-task measurements with independent readiness evidence for BOTH destinations, per-direction frame timestamps and full exchange timing; match deadline semantics.
- Only then consider a separate production Draft PR on v83 for batching, with cancel/timeout/missing-task/identity-change/late-result/rollback coverage and all existing device-layout/gesture/render tests.
- ColorOS Ace 7 Pro, Find N5 folded/unfolded, Find N3 and Y700 orientation tests remain unverified on physical devices. AOSP geometry/HWUI/API37 tests are not OEM validation.
- Content lifecycle continuity (paused remains paused, no reload), lock-screen power, polling and leak profiling remain open. No power or temperature claims.
- No main changes, PR merges, Release publication, public feed changes or signing changes are authorized by this checkpoint.
