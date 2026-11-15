# Final checkpoint: P0 remains open

Test/source commit: b6c818b6d3640236211c8866d81b29935b861deb, Draft PR96. Direct parent: verified v83 0e0733fe88efe9ceae82990873f960ab279c05c0. No production code, version, signing configuration, main, release or update-feed changes. No APK delivered.

CI 43695033386 completed success: development 132753569569; Android15 visual-regression 132754096208; Android17 API37 mirror 132754096151. Both unit test configurations (default API102 and compatibility API101) contain 544 tests, zero failures/errors/skips, 119 suites each. API101 artifact 14153363339, default unit artifact 14153679141, Android15 artifact 14153229705, API37 artifact 14153644846. Upstream-baseline is intentionally skipped on push, not claimed as executed.

PR94 unchanged-head retry 43691098410 attempt 2 passed. Its Android15 job 132752922271 and artifact 14154340979 include the previously failing menu and all 48 three-mode task moves. This establishes intermittent failure; source inspection identifies fixed-delay versus posted-action timing mismatch. PR96 now waits on actual callbacks with bounded latches and exact ID checks. It does not claim a production menu fix.

## Second four-mode run (PR96)

All values are milliseconds, eight observations per direction. The endpoint is task display confirmation, NOT visual readiness or full exchange.

| Mode | Main median / max | Side median / max | Owner busy median main / side | Callback wait median main / side |
|---|---:|---:|---:|---:|
| sync | 25.621 / 75.513 | 70.811 / 142.601 | 25.561 / 12.379 | 0 / 0 |
| async | 182.948 / 243.082 | 203.775 / 382.422 | 0.237 / 0.132 | 131.844 / 158.767 |
| async_barrier | 123.017 / 171.452 | 208.613 / 331.431 | 0.260 / 0.141 | 103.965 / 155.807 |
| async_batch | 103.375 / 171.411 | 174.184 / 296.770 | 0.191 / 0.087 | 87.684 / 81.730 |

Each move again needed one query. Ordinary async/async Handler use two callbacks; batch one; sync zero. Batch vs ordinary async improved query-confirmation median by 43.5% main and 14.5% side in this run, versus 26.0% and 43.3% in PR95. This reproducible direction is promising, but effect size is not stable.

Frame gap p95/max: sync 83/116; ordinary async 99/133; async Handler 66/133; batch 83/116. Over33ms samples: 31/116, 41/118, 36/108, 39/104 respectively. In PR95 batch p95 was worse than ordinary async (99 vs83), here better (83 vs99). No robust frame-pacing claim follows. These frame samples span both directions plus padding and output waits, not per-direction animation frames.

PR94 retry Handler overall elapsed median 188.195 vs ordinary192.816 (~2.4% lower); PR95 Handler181.445 vs215.751 (~15.9%). PR96 Handler return-to-side208.613 is slower than ordinary203.775. Do not treat async Handler as an unconditionally faster completion route.

## Decision and next work

Retain production v83. Synchronous migration blocks the owner; async Handler has mixed results; batching is promising but lacks matched production cancellation/deadline/rollback and full-output evidence. The probe shares its main process with all four task Activities, does not independently verify main-display fresh pixels, and finishes timing before buffer handoff. Copying batch into production now would remove the owner cancellation gate between move completion and first read. It must be designed and tested explicitly, not silently bypassed.

Next: separate-process fixtures; per-direction task-confirmation AND actual visual-readiness endpoints; matched deadline semantics; per-direction frame timestamps; cancellation while move/query is running, expiry, task/token replacement and rollback tests before an independent production candidate. Device geometry model/HWUI tests do not establish ColorOS Ace7Pro/N5/N3/Y700 compatibility, third-party paused/playback/content persistence, temperature or power. Those remain open. No high-risk lower-level change was made to chase progress.

Original PR95 raw evidence and audit are in README.md and pr95-real-task-migration.txt. PR94 retry and PR96 raw reports are adjacent. This evidence-only branch adds documentation to the already fully tested source commit; no further executable changes or CI bypass is involved.
