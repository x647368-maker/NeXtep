# Layout poll candidate, not a released performance fix

Base d871e1cd. Only production change: TASK_LAYOUT_POLL_MS 30 to 8. Deadlines, main-thread task calls, observation conditions, ownership and rollback remain identical. Hypothesis: reduce the artificial extra sleep when WCT layout settles between polls; risk: extra queries negate the benefit.

The benchmark copies the complete baseline coordinator with only its class name changed, calls actual baseline/candidate move and layout wait methods on the main looper, performs actual WCT fullscreen density restoration and task foreground changes, uses the same five tasks/four VDs, two warmup pairs and ten alternating pairs per cohort. It records critical section, actual frame P95, main Choreographer gaps and query counts. All existing regression assertions remain. This is a real framework path, NOT the full production SystemUI exchange/Surface fitting/ColorOS path. No no-restart or flicker claim can be inferred from fresh buffers.

Acceptance: at least 10% and 2ms lower median critical section, no credible regression in maximum blockage or render P95, no failed safety checks. This candidate will be reverted if evidence is insufficient. No version change or APK delivery before evidence. Test process has a 720-second wall-clock timeout; no unlimited retry. At most two attempts without valid data, then stop.
