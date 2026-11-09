# Candidate: eliminate migration logging-only task queries

Base verified: ebe9de646d7d800fb1d3d6bf295e4ef12e68aade. No version increment or deliverable is authorized before evidence of benefit. Independent branch atomic-workbench/perf-query-elision. Existing diagnostics are reused, not expanded.

## Actual production modification

Only moveTaskToDisplayAndWait changes. Previously it queried the entire task list once for shouldMove, again for move_begin logging, once for the required post-move check, and again solely for move_end logging. The candidate reuses only the immediate precheck snapshot for move_begin. The post-move logging query also refreshes the global task-state observations; it remains unchanged for safety. Every poll still performs a fresh task query. There is no cross-operation cache, no reuse across a task mutation, and no omitted destination, layout, ownership, Surface, cancellation or rollback check. The Binder task call, preparation, main-thread execution, timeout, sleeps, polling predicates and subsequent mandatory layout/Surface queries stay original. A complete two-leg swap removes two unnecessary pre-mutation full-list queries/decodes; all post-mutation observation passes remain. Logging is not a valid readiness barrier; explicit task/Surface checks remain mandatory.

## Independent verification

The unmodified full baseline coordinator is copied into androidTest with only its class name changed. The comparison invokes the actual private migration helper in baseline and candidate classes on the main looper. Five real tasks use four real VDs. After two warm-up pairs for each cohort, twenty alternating measured two-leg framework pairs include a real bring-to-front call. It records complete harness-pair critical-section median/max, query counts, Choreographer gaps, actual Window.FrameMetrics, and activity creation counts. It checks retained task IDs, fresh destination buffers, raw input reaching the promoted live application, idempotence and real invalid-display failure cleanup. Existing tests, including rollback policy and all original assertions, remain mandatory.

The harness does NOT recreate the production SystemUI Shell leash, workspace WCT fitting, preview overlay or ColorOS compositor. Its pair timings are not complete production exchange timings. It cannot prove no intermediate vendor flicker, app-process restart, LSP runtime compatibility or ColorOS fluidity. Existing production recovery and ownership code is byte-preserved outside the exact reviewed helper edits.

Pre-declared decision: require fewer queries and a useful lower critical-section median (at least 10% and 2ms), with no failed behavior/safety assertion. Inspect max blocking and real frame metrics for regressions; a credible regression rejects the candidate. Do not cherry-pick an emulator pass as ColorOS proof. Without useful benefit, revert the production change, retain stable APKs and stop. Do not start low-power work or increment an APK version for a failed candidate.

## Stage gate

Phase 2 power/thermal work remains blocked until phase 1 demonstrates meaningful safe improvement; vendor behavior remains subject to normal phone acceptance. No new power percentages, temperature or battery claims. No resolution/refresh-rate changes, screenshots as task substitutes, task termination, added waits, background migration, new polling or public Release.
