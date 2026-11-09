# Rejected fresh destination query experiment

Base: c4965dd1495d7f23a141d88f06d2727e8b11e36b
Tested candidate: bdc20ff3588d59cae69518c54b7f2ea774907d6f
Run: https://github.com/x647368-maker/NeXtep/actions/runs/42661342046

The phone reported v0.9.14/29-exchange-phases, connected, exchange_move 252.9ms main=true ok=true; query_binder 147.2ms/14 and query_decode 54.4ms/14, display_binder 37.3ms/2, display_observation 0, incoming_layout 11.7ms, slot_layout_and_fit 32.4ms, incoming_presentation 24.7ms. Inclusive nested phases must not be added. The 98ms handoff includes the unchanged 80ms minimum and consecutive-ready check and is not solely Surface blockage or proof of a new buffered frame.

The ordinary two-task exchange's 14 repository queries map to source/main/outgoing selection (3), two migration helpers (4 each: precheck, begin-log snapshot, destination observation, final full observation), incoming layout (1), outgoing slot layout (1), and incoming presentation (1). Conditional fallback/polling paths may add calls. This identifies purpose without asserting all reads are redundant.

Only migration destination observation was changed: fresh framework getTasks(64,false,false,displayId), decoding only the target task. Pre-mutation calls and final global observation (including all Surface state refresh side effects) remained unchanged. Missing/failed/unsupported destination queries fell back to the original full lookup. No cached state, removed confirmation, thread move, changed deadline or polling interval. No Surface/geometry/input writer changed.

AOSP API source: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityTaskManager.java

First compilation failed because SuppressLint was placed on a delegated property; corrected to the getter annotation without changing candidate logic. One subsequent complete build/test run was performed, not an unlimited retry. Existing 720-second instrumentation timeout remained.

Same five real tasks/four VDs, main looper, two warmup pairs per cohort then ten alternating measured pairs. Actual baseline/candidate coordinator migration helpers plus foreground move ran. This is a framework-pair harness, NOT the complete production SystemUI exchange, overlay or vendor Surface path.

| Metric | Baseline | Candidate |
|---|---:|---:|
| Main critical-section median ms | 79.784413 | 81.624579 |
| Main critical-section maximum ms | 157.253807 | 112.836180 |
| Binder calls per pair, every sample | 8 | 8 |
| Choreographer maximum gap ms | 149 | 333 |
| Render FrameMetrics P95 ms | 218.281909 | 292.144213 |
| Render samples / samples over 17ms | 57 / 57 | 60 / 60 |
| Activity creation callbacks | 20 | 20 |

Filter exclusion and target snapshot equality passed; wrong-display and missing-task fallback passed. Task IDs, raw foreground input, fresh destination buffers, idempotence and real invalid-display cleanup passed. API101/102 compile/unit tests, frozen-source guard, AOSP Surface/input/menu/native-shade regressions passed. No claim of no-restart/no-flicker follows from task IDs or fresh buffers. Actual ColorOS complete exchange continuity, LSP coexistence and Find N5 are unverified.

Decision: reject. Median worsened 2.3%, render P95 worsened 33.8%, and the maximum main frame gap worsened; the lower critical-section maximum does not establish overall benefit. Reducing the payload of two reads without reducing the number of synchronous reads did not meet the acceptance criteria. No claim that this single emulator run proves the exact vendor performance impact.

All production/test/guard changes restored to base; only this evidence note remains on the branch. Test code and candidate remain in Git history. No new version, signed APK, Release or power/UI changes. Broad caching across task mutations could corrupt readiness/rollback/Surface ownership and has not been justified by these results; no speculative engine rewrite is shipped.
