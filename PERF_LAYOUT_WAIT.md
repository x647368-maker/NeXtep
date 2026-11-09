# Layout wait experiment: rejected, runtime source restored

Base: d871e1cd04576a0b6ad83ace7da9c6f05b2dc46c
Tested candidate: 87390e310d03ee652cc152c69d0988837d7a4047
Run: https://github.com/x647368-maker/NeXtep/actions/runs/42652306451

Only production candidate change: TASK_LAYOUT_POLL_MS 30ms to 8ms. All task migrations stay on their original main looper, with unchanged conditions, 900ms layout deadline, ownership, mutation order and rollback. Reflection caching was inspected and already exists, so no duplicate cache optimization was implemented. Broad asynchronous conversion was not attempted because it crosses swap/exit/lock/recovery API boundaries and available vendor evidence does not validate that reentrancy.

Same emulator, five real tasks, four real VDs; two warmup pairs per cohort, ten alternating measured pairs each. Actual baseline/candidate migration helpers, WCT fullscreen/density layout restore and foreground moves ran on main. Both helper classes were actual full coordinator source, differing only in class name and the candidate constant. This harness excludes production SystemUI Surface fitting, overlay and vendor task engine.

| Metric | Baseline | Candidate |
|---|---:|---:|
| Main harness-pair median ms | 103.127545 | 88.865430 |
| Main harness-pair maximum ms | 119.662639 | 114.136581 |
| Task queries per pair (every measured sample) | 12 | 12 |
| Choreographer maximum gap ms | 133 | 133 |
| Render FrameMetrics P95 ms | 171.526949 | 166.803803 |
| Frame samples / samples over 17ms | 54 / 54 | 65 / 65 |
| Activity creation callbacks | 20 | 20 |

Critical causality check: each non-idempotent display helper makes four queries; each post-move snapshot makes one; each initial layout observation makes one. Two legs therefore make twelve queries WITHOUT any retry. Both cohorts recorded exactly twelve in all ten measured samples. No layout polling iteration occurred: the modified sleep constant was not exercised. Apparent median improvement (13.8%) and P95 change cannot be attributed to this candidate. They are not evidence of a fix. Destination buffers and task IDs do not establish no-restart or no-flicker (both cohorts had activity creation callbacks).

Decision: reject, restore all production/test/guard files to base, retain this evidence and Git history. No additional compilation/test retry, no version increment, no APK deliverable, no power/thermal work. Keep the new explicit 720-second instrumentation client timeout, 20-second forced-kill grace, and existing failure evidence collection. Existing startup step is bounded separately; original regression assertions remain intact.

API101/102 compilation/unit tests, frozen guards and Android interaction tests passed. Four-task/Surface clipping, actual input, native menu/shade regressions passed in AOSP. ColorOS complete exchange timings, Surface first-buffer continuity, flash/black-frame behavior, LSP multi-module coexistence and Find N5 are NOT VERIFIED.

Remaining blocker: existing phone evidence confirms long main-thread exchange time but the old v0.9.12 aggregate does not attribute the 274–422ms to waits versus synchronous vendor Binder/configuration/Surface work. The AOSP layout retry did not reproduce. Removing waits or changing task/Surface ownership without that evidence would risk accepted functionality. No new diagnostics-only version is delivered and no repeated old phone test is requested.
