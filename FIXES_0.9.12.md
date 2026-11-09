# v0.9.12 / 27 — conservative follow-up to 3918715

## P0 status: unresolved on ColorOS, measurement added, engine unchanged

The v0.9.11 screenshot-worker benchmark did not measure real task migration and is not evidence that phone app exchanges are smooth. Source inspection finds the animated handoff invokes the two real display migrations synchronously on the UI thread. Display observation can poll at 30ms up to 700ms; layout observation can poll at 30ms up to 900ms. These are maximum deadlines, not measured device durations. Binder calls, vendor configuration changes and buffer production can add latency. No claim is made that this source finding establishes the complete vendor flicker cause.

Read-only `AW:` trace sections and bounded, non-identifying timings now separate Binder, display observation, layout observation and the full exchange. The running SystemUI's versioned identity response includes the latest 16 timings in app diagnostics, so installed APK version is not mistaken for loaded hook code. No migration order, timeout, frame wait, Surface/crop/input writer or animation duration changes. No second attempt at v0.9.11 async capture/layout batching.

Instrumentation adds four real AOSP tasks on four consuming VirtualDisplays and 16 real display moves. It checks retained IDs and fresh destination buffers, and records UI frame gaps and elapsed calls. This is framework baseline evidence, not production SystemUI integration or ColorOS profiling. There is intentionally no claimed before/after speedup because the production engine was not altered. A ColorOS device trace is still required before a safe task-engine optimization can be justified.

## P1: opt-in idle rail hiding

Default off. The original manual collapse and original menu commands remain. On left/right layouts only, after 3.5s idle the optional host becomes transparent and non-touchable while its TextureViews, VD buffers and tasks remain attached. A small inset edge handle restores it; pinning prevents idle hiding. Top layout, geometry and main-window size are unchanged. The mode intentionally does not reclaim the old rail's space: expanding the main would cross the approved geometry boundary. No global input listener, gesture exclusion or native-panel transform is added. Native-panel/unknown state blocks hiding/restoring; menu/group/transition/lock state is respected.

## P2: reuse existing verified updater

Existing checker, installer, integrity checks and preferences remain. Opt-in immediately checks; subsequent app entries check every 12h after success or 1h after failure. Clock rollback cannot suppress checking forever. Cached updates show an in-app prompt; notification taps reach the update page even if the activity already exists. Stable channel excludes GitHub prereleases. Notification denial leaves app prompts available. No polling service or silent installer. Existing default-off versions do not receive proactive notifications until users enable their existing option.

No public Release is created. A signed APK and schema-v1 update bundle can be prepared privately. Real public old-version discovery/download/native installer upgrade cannot be called verified until a signed release exists, phone acceptance is obtained and the user explicitly authorizes publication.

## Stability and validation boundaries

Exact reviewed fragments normalize back through v0.9.11 to all original frozen v0.9.8 hashes. Existing frozen manifests and native notification tests remain enforced. Main/slot dimensions, full-source crops, display canvases, task operations and input coordinates remain original. New tests add real migration observations, idle hide/pin/restore with four live attached TextureViews, all old plus new menu callbacks, and retry/clock/opt-in policy cases. Actual automated results belong to the commit's CI artifacts. AOSP results are never ColorOS acceptance.

Original certificate required: b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd. GitHub Secrets remain optional for compile/test only; unsigned cloud artifacts are never delivered as an installable update. Private signing stays outside git.
