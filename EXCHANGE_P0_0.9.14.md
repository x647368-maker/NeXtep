# Exchange P0 follow-up / v0.9.14 (29)

Base: d71d1e57e2b2b7d00666ac64e6d9ba14087ac17a, including all LSP coexistence work. Independent branch atomic-workbench/exchange-p0. Window/task/gesture design is not rewritten.

## Device evidence and version boundary

The supplied device evidence is from **0.9.12/27-exchange-diagnostics**, not v0.9.13 or this candidate: exchange_move 274.4 / 421.6 / 367.1ms, main=true; display_binder 9.7–24.7ms; display_observation 0ms; layout_observation 5.9–32.2ms. It confirms a long synchronous exchange and supports UI-blocking risk, not the entire flicker cause. No repeat of those same v0.9.12 tests is needed. The installed settings APK and running SystemUI generation are separate; the existing v0.9.13 mismatch probe is retained. Updating an APK cannot safely replace a stateful running task engine: normal device restart is needed to load the new generation. Every new profile names its actual compiled build, process-local exchange number and thread, so old data cannot be claimed as new.

## Further attribution, no hidden double-counting

One monotonic nested tree now records a complete exchange, with inclusive time, exclusive/self time and call counts. Exclusive durations sum to root total; inclusive durations must not be added together. It distinguishes source/main lookup, outgoing task lookup, resize mode, incoming display promotion, promotion preparation, incoming layout restoration, WCT submission, bring-to-front, outgoing display demotion, outgoing Surface restoration, slot layout/fitting, incoming presentation, task query Binder/decode, leash lookup, live Surface query, Surface transaction submission, state publication, recovery scheduling and rollback. Residual root self-time remains explicitly visible. This measures synchronous submissions, **not SurfaceFlinger present fences or app lifecycle callbacks**. The latest complete profile replaces interleaved/truncated nested log lines, and logging is deferred until the operation ends. No task names, contents or screenshots are in the timing report.

## Small independent performance comparison

The metadata-cache candidate was **rejected** after the first independent AOSP comparison: median 7.299089ms baseline vs 8.530738ms candidate per 128 decodes, eight alternating batches, same four real task snapshots. State fields matched, but the candidate was slower. Production TaskInfoCompat is restored byte-for-byte to the LSP base. Cache and candidate decoder exist only in test source sets, never in the delivered target APK. The unmodified baseline decoder, candidate decoder, mutable-value/class-separation tests and benchmark remain for audit; no failed assertion or performance threshold was removed. Subsequent numbers may vary but do not reactivate this rejected production change. There is no claimed before/after full-exchange improvement. The deployed changes are read-only phase attribution and accurate handoff timeout classification only.

## Synchronous waiting investigation

Display/layout polling uses SystemClock.sleep on the main thread and can block. However the supplied display observation time is zero; it does not explain the missing hundreds of milliseconds. Task/Shell callbacks currently also reach the main looper. Replacing a synchronous helper with a callback without a resumable transaction would continue later steps early, break the move()->Boolean contract, or bypass rollback. A safe change needs explicit phase continuations, cancellation/ownership tokens, deadlines and rollback handling on the same original looper. That larger change is **not** made without phase evidence. Migration order, thread, timeouts, preconditions, recovery and rollback remain original.

## Preview handoff investigation

The existing handoff checks a fresh slot frame plus the incoming main task's pose/identity for two consecutive observations. It still lacks proof of a freshly presented main-task buffer. The old 600ms deadline reveals the live content even if readiness is false; this is a possible flicker path, not a confirmed device cause. Increasing that deadline or retaining a static screenshot would mask the issue. Therefore timing and reveal behavior are unchanged. The candidate fixes diagnostic classification: one ready sample at the deadline is correctly reported as timeout, not stable. No claim is made that timeout fallback has become safe or that flicker is fixed. A real main-buffer/fence observation would be needed before changing that boundary safely.

## Validation and delivery limits

Existing API101/API102 compilation/JVM tests, frozen boundaries, real-task/Surface/input/menu and native-panel emulator tests remain mandatory. New profile/caching/handoff tests add evidence, without replacing any existing assertions. Actual timings belong to this commit's artifacts. Device smoothness, task lifecycle continuity and vendor buffer handoff remain pending ColorOS validation. Version 29, original application ID and b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd certificate required. No public Release is authorized.
