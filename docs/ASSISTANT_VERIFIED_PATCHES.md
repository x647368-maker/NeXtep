# Atomic Workbench — Independently Verified Session Hardening

**Scope:** safe, reviewable fragments to hand to a later Android integration/packaging pass. This is **not** an installed build or verified speed fix.

## Repo inputs

- Upstream in-progress Android integration branch: `atomic-workbench/android-exchange-session`, base SHA `ff041691ab32a546ba293a0ae3c4fc89dbb2fb5c`.
- This independent branch: `atomic-workbench/assistant-verified-patches`.
- Existing stable v0.9.8 layout and all later accepted features remain protected; changes are not merged to stable/main.
- No work-mode use required to produce the changes in this branch.

## Patch A — prevent premature release on pre-mutation abort

Before: `ExchangeSession` immediately set `RESTORED` if cancel/deadline fired in phase `PREPARED`, without re-reading owner tasks.

After: invoke `Driver.restoreAndVerify()` before marking settled. Android driver, when `mutationStarted == false`, now checks both exact task identities, their original Display IDs, the slot's live display ID, and the top task ownership. If these conditions are not met (or task querying fails), transition to `RECOVERY_REQUIRED` and **do not** release display reservations or report restored.

Tests: cancellation with original owners, cancellation after external main task move, deadline after external slot task move, retry after original owners return.

## Patch B — final callback exception containment

Before: exception thrown by the async preview completion callback could prevent the coordinator's final clean-up path from running and propagate out of the session's main-Looper notification.

After: session captures listener failure instead of rethrowing on SystemUI Looper; coordinator logs the callback error and runs `finish()` in a `finally` block. This does not change task move order or layout.

Tests: completion listener throws, verifies exactly one notification and no repeated migration; established tests still pass.

## Validation carried out without Android SDK / Work mode

- Local host: JDK 21 + Kotlin CLI 1.9; **12 original JUnit-style test methods passed, 0 failed**, using a deliberately minimal local JUnit assertion/annotation shim (not a replacement for full Gradle/JUnit).
- Before adding patch B, an additional standalone state machine harness passed 10/10 scenarios.
- Both protected `SlotTaskCoordinator.kt` edits are registered as exact reversible fragments in `scripts/reviewed-session-hardening.json`. Verified that reversing the two fragments reproduces the stopped Work branch file byte-for-byte.
- `scripts/verify-state-continuity.py` loads the new exact review manifest **ahead of** prior review manifests; it still checks the entire frozen baseline.
- Offline repeatable quick check: `bash scripts/fast-check-exchange-session.sh` (requires JDK + local `kotlinc`). Does not download SDK/emulator or require GitHub Actions.

## Still unverified — do not claim success

- Full Android Gradle build for this new branch; API 101/102 runtime, startup, notification shade, LSP multiple modules, lock/unlock and ColorOS/Samsung/OPPO behavior.
- AOSP visual integration and ColorOS real-device frame P95, actual swap duration/flicker.
- Original-key signed candidate APK and verified install-in-place signature.
- No CI run was available for the Work integration branch when these patches were prepared; Work's earlier emulator boot timed out after 180 seconds.
- Kotlin/JVM tests verify the **pure session state machine**, not Android lifecycle or performance.

## Future assembly gate (only if another tool is necessary)

1. Read the head commit of this independent branch; do **not** copy only the old `ff041` file and overwrite these fixes. Keep PR draft until integrated.
2. Run quick check first. Then run Gradle API 101/102, frozen verifier, Android integration tests with bounded startup. If GitHub Actions are disabled, restore the workflow through authorized repository controls instead of endless emulator retries.
3. Ensure compiled applicationId `io.github.x647368maker.atomicworkbench`, versionCode > 29, original certificate SHA-256 `b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd`, and preserve install data.
4. Build a **candidate** APK only if safety gates pass. Label ColorOS performance unverified until installed on OnePlus Ace 7 Pro; test main↔slot swap, repeated taps, screen-off/lock, exit, producer destruction/reconnect, gestures, four real windows, and recovery failure.

**Non-negotiable:** do not weaken the frozen-source manifests or silently label emulator metrics as ColorOS proof.
