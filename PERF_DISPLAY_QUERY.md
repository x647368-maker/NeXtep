# Fresh destination query experiment

Base c4965dd. No cached task state, no query elision, no polling interval change. Only migration destination observation uses the framework display filter and decodes the required task. Pre-mutation reads and final global observation remain unchanged (including global Surface observation side effects). Missing/failed/unsupported filtered reads fall back to the exact original full lookup. Task order, main looper, timeout, rollback, geometry and Surface writes stay unchanged.

Source API: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityTaskManager.java (getTasks(maxNum,false,false,displayId)).

Baseline coordinator is an exact source copy except class name. Candidate invokes the actual two-leg helper on the same main looper/tasks/displays, two warmup pairs then ten alternating measured pairs per cohort. Foreground move, ownership, input, fresh buffers and failure cleanup assertions remain. Filter exclusion, snapshot field equality, wrong-display/missing-task fallback are asserted. Entire production SystemUI Surface/overlay exchange and ColorOS are not emulated; no claim of no-restart or no-flicker follows from retained task IDs. Instrumentation is bounded to 720 seconds, no unlimited retries.

Acceptance: useful critical-section improvement (at least 10% and 2ms), no credible blocking/frame regression, safety assertions pass. Reject if no demonstrable benefit. No new version/APK until evidence. No power/thermal/UI work.
