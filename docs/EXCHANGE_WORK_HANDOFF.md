# Atomic Workbench — Work Mode Implementation Handoff

Updated: 2026-11-10. This is the single source of truth for the *next implementation task*, not a claim that the lag is fixed.

## Goal and acceptance

Deliver an **original-certificate signed, in-place updatable candidate APK** that safely reduces main↔slot swap freezes/flicker on OnePlus Ace 7 Pro (PMS110), ColorOS 17 / Android 17. Preserve all existing 0.9.8+ accepted features (four true live mini-windows in top/left/right layouts, geometry, input mapping, status shade/gestures, LSP coexistence, recovery, menus). Also preserve OPPO Find N5 fold/unfold behavior. Power/thermal improvements come **only after** the core swap is safe and verified.

This is an incremental Android integration task. DO NOT rebuild the app or repeat the rejected AOSP micro-optimizations.

## Current references and checked facts

- Repository: https://github.com/x647368-maker/NeXtep
- This branch: `atomic-workbench/retain-virtual-display-output`. Read its *current* HEAD before changing code.
- Stable v0.9.8 baseline: `273c4d61d3a70b16ec7bdc5a266d19e416a7be41` (do not reset current features to it).
- Perf investigation: `atomic-workbench/exchange-p0`, `ebe9de6`, v0.9.14 detailed telemetry.
- Rejected experiments: `d871e1cd` (query elision), `c4965dd` (layout poll), `7cb91d` (display-filtered query). Never resubmit them as performance fixes.
- Exchange-session prototype: `experiments/exchange-session/ExchangeSession.java` and `ExchangeSessionTest.java` (original `e42049a`). These are **not integrated into the running app**. Adapt them to the main-thread-coordinated Android lifecycle rather than simply copying synchronously.
- Actual production change already on this branch: `VirtualDisplaySlot.kt` keeps a live VirtualDisplay on TextureView surface destruction, detaches its output, rebinds a new SurfaceTexture, and supports manual reconnect. This is **resource-retention groundwork**, NOT a swap-speed fix.
- CI on source HEAD `e0de6e11205322f4bfd2745655baaf60f6aced3f` succeeded across development, API 101/102, frozen workspace checks and visual regression (run `42668567981`). Recheck if HEAD has moved. Passing AOSP tests does NOT establish ColorOS performance.
- Approved exact patch fragments recorded in `scripts/reviewed-output-retention.json`, enforced by `scripts/verify-state-continuity.py`. **Do not remove, disable, or weaken** frozen checks. Add exact reversible approvals only when unavoidable.

True ColorOS v0.9.14/29 exchange #35:
- `exchange_move 252.9ms main=true ok=true`
- `query_binder 147.2ms x14`; `query_decode 54.4ms x14` (nested/overlapping totals; do not sum naïvely)
- `promote_display 59.4ms`; `demote_display 103.5ms`; `display_binder 37.3ms x2`; `display_observation 0ms`
- `incoming_layout 11.7ms`; `slot_layout_and_fit 32.4ms`; `incoming_presentation 24.7ms`.
- Handoff 98ms includes an existing minimum 80ms delay; this alone does not prove Surface buffer stalls.

## The precise next Android work (P0 only)

1. **Resource/identity safety audit first** (fast, source-targeted; no new diagnostic system). Review:
   - `VirtualDisplaySlot.onSurfaceTextureDestroyed()`, `releaseDisplay()`, `reconnectRetainedOutput()`, `deactivate()`, `setSuspended()`.
   - `SlotTaskCoordinator.onDisplayReleased()`, `setSuspended()`, `deactivate()`, `parkTasksBeforeExit()`, `cancelPendingExchange()`, `runTransition()`, `swapWithMain()`, `moveTaskToDisplayAndWait()`.
   - `WorkspaceController.suspendForLock()` and exit/resume sequence.
   - Animation handoff callback contract (`onMainSlotAnimation`, its caller and overlay cleanup).
   Assess display loss while a task still owns it, producer replacement failure, stale callback, display cleanup on terminal completion. Ensure there is no **permanent VirtualDisplay leak** introduced by the retained-output patch. If current patch has a bug, fix with an isolated, frozen-reviewable diff.
2. **Wire one per-exchange session** through `SlotTaskCoordinator` for main↔occupied-slot swap. Freeze original task IDs, user IDs, tokens, Display IDs, original layouts, generation, deadline, current phase and rollback state. Do not run general slot↔slot swaps through a partially implemented main↔slot protocol. Ensure single-flight operations and generation checks prevent stale/duplicate callbacks from migrating tasks again.
3. **Safe phased execution.** Allow at least one main Looper turn between the task promotion and demotion/verification when safe, without moving Binder/Surface operations illegally off their owning thread. During this gap both tasks may temporarily be on Display.DEFAULT_DISPLAY: the selected slot's reservation must not be cleared or assigned to another task. Important: each individual Binder call can still block; do not falsely claim total exchange time is shortened.
4. **Cancellation & lifecycle correctness.** During the non-atomic gap, coordinate task disappearance, lock, unlock, workspace exit, backgrounding, repeat taps and display surface destruction. Never destroy a task-owning VirtualDisplay until committed/verified or rollback/verified. Never indefinitely delay keyguard or SystemUI; lock/exit may mark cancellation and schedule bounded rollback while native system interaction proceeds. If rollback cannot finish, leave ownership/records recoverable, do not announce success or free its display. Avoid queue-wide `Handler.removeCallbacksAndMessages(null)` dropping essential session continuations.
5. **Animation callback integration.** Existing `runTransition()` assumes synchronous `move: () -> Boolean`; implement an explicit pending/completed/cancelled result path so the overlay and busy state persist until transaction commit/rollback. Do not release a screenshot merely due to callback scheduling or partial promotion. A preview may not be equivalent to a truly presented Surface buffer.
6. **Automated tests & proof.** Cover happy path, cancellation after promotion, timeout, missing task, already-moved Binder-then-throw, texture producer destruction and reattach, display removal, exit/lock interleavings, repeated tap, and rollback failure without task destruction. Reuse existing JVM prototypes and Android tests; run frozen checks, API 101/102, build and AOSP visual/interaction regression. Use hard CI timeouts and do not loop indefinitely. Label emulator evidence as emulator evidence.
7. **Only if a safe candidate survives:** retain all prior features, increment versionCode/versionName, build the **original signed** APK (package `io.github.x647368maker.atomicworkbench`; cert SHA-256 `b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd`), verify signature and install-in-place identity, produce a retrievable artifact for ColorOS owner testing. Do not upload private signing keys or automatically publish a public release. Candidate must clearly say **ColorOS unverified** until the owner tests on-device.

## Decision and reporting rules

- Keep production changes in a distinct branch, original stable artifacts unchanged. Preserve existing signed baseline package for rollback.
- Never weaken the freeze manifest/scripts to make CI green. Follow the exact reversible review pattern if updating protected paths.
- Never repeat the three rejected micro-optimizations or merely adjust timeout/animation duration to hide stutter.
- If unsafe to integrate, stop at the precise code-path blocker with commit and tests, **without** claiming a repaired APK. Do not burn time on more generic root-cause research or unbounded test loops.
- At end report, concisely: branch + commit; production code altered; automated tests (pass/fail); whether signed APK actually exists; Android/ColorOS limitations and the **one** next action, if needed.

Owner is nontechnical: no terminal, Gradle, keystore work or git commands required from owner. Owner only installs the valid signed candidate and judges true ColorOS smoothness.
