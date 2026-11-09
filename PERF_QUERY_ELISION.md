# Rejected P0 candidate: duplicate pre-mutation query elision

Baseline: ebe9de646d7d800fb1d3d6bf295e4ef12e68aade.
Tested candidate: 01eae45462ff8f4bda6beed5ffdb41e5e19db233.
Actions run: https://github.com/x647368-maker/NeXtep/actions/runs/42640008362

The production candidate reused the immediate pre-mutation task snapshot for move_begin logging, removing one complete task-list query per move. All post-mutation queries, task migration threads, polling, Surface operations, geometry, failure cleanup and rollback remained unchanged. A broader draft was narrowed before the evaluated run because the post-move logging read also refreshes global Surface observations.

Two warmup pairs per cohort, then ten alternating measured two-leg pairs used the actual baseline/candidate private migration helpers on the main looper, five real application tasks, four consuming virtual displays and a real bring-to-front operation. This measured a framework harness pair, not the complete production SystemUI exchange, Surface fitting, overlay, ColorOS or Find N5 behavior.

| Metric | Baseline | Candidate |
|---|---:|---:|
| Main critical section median ms | 55.645033 | 45.675647 |
| Main critical section maximum ms | 80.590778 | 62.005260 |
| Task-list queries per pair | 8 | 6 |
| Choreographer maximum gap ms | 116 | 116 |
| FrameMetrics P95 ms | 136.025125 | 149.269408 |
| Frame samples | 67 | 53 |
| Frame samples over 17ms | 67 | 53 |
| Activity creation callbacks | 20 | 20 |

The critical-section median decreased 17.9%, but FrameMetrics P95 worsened 9.7% and the maximum Choreographer gap did not improve. The unequal render samples and framework activity recreations prevent a reliable continuity or no-restart claim. These figures do not measure the user's 274–422ms full ColorOS exchange and cannot establish a phone performance fix.

Decision: reject and restore every production and test source file to the baseline. Keep only this evidence note on the independent branch. No version increment, new signed APK, power/thermal work, or public Release. No assertions were removed to obtain a pass; the evaluated candidate passed the existing automated guards and behavior assertions but did not meet the conservative performance acceptance decision.

Task IDs, fresh destination buffers, raw foreground input, idempotence and actual invalid-display failure cleanup passed. Frozen geometry checks and API 101/102 builds/unit tests passed. Existing AOSP menu, native shade and Surface clipping regressions passed. Actual ColorOS/LSP coexistence, scaled production exchange continuity and Find N5 were not verified.

The remaining blocker is evidence for the vendor production task/Surface path and its loaded build identity. Moving the whole exchange to a worker, delaying animations, suppressing necessary readiness checks or guessing vendor first-buffer readiness would exceed the safe evidence available. Preserve the installed stable build rather than shipping another diagnostics-only or unproven performance version.
