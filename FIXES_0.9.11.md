# 0.9.11 refinement, versionCode 26

Base: verified source ed7fdb7d9512c015e08f1e1a9cefe72c9725efc7 (0.9.10/code25). Stable v0.9.8 and v0.9.10 refs retained; development isolated on atomic-workbench/v0.9.11.

## Evidence and scope

The supplied 6.2-second GIF shows exchanges under the top rail, not a recorded rail-layout transition. Source inspection finds main-task ScreenCaptureInternal.captureLayers + software Bitmap copy on the SystemUI thread before any exchange frame, an ID-only main readiness test at preview handoff, and a top-to-side command that first applies the previous side then applies the requested side. These are concrete blocking/early-handoff/intermediate-layout risks. They do not establish a measured PMS110 frame-time root cause without instrumentation on that phone.

Main task preview capture now runs on a single worker, with generation/geometry/task/slot-state validation and a 180ms capture deadline using the existing no-preview fallback. Only pixel capture is moved; task migration and TextureView work retain their existing UI-thread owners. Cancelled/late bitmaps are recycled. No animation duration is increased.

Main handoff now checks the existing owner's read-only settled surface pose and waits for two consecutive ready frames. Existing final SurfaceControl crop, matrix, placement and input inverse are byte-unchanged. The 600ms safety release remains bounded. A layout menu command records final top/side state before one viewport reconfiguration, eliminating the old intermediate-side update. No cross-window snapshot animation or new task/display resize is introduced.

## Glass menu

Four categories preserve all 13 main commands. All other command lists remain native popup menus. Primary width208dp, secondary220dp, maximum min(300dp,38% safe height), 20dp corners, light/dark translucent gradients, fine edge highlight and 4dp elevation. No full-screen dim, blur, screenshot backdrop or notification styling. Commands/back navigation are tested through InputDispatcher; action callbacks retain the existing ownership. Page/open fading is100ms; action close70ms. Native-panel dismissal is immediate and cancels pending fade actions.

Placement minimizes rectangle overlap with the existing main/four window bounds, then distance from the menu anchor. It never resizes or rearranges real windows and cannot guarantee zero overlap on a crowded screen.

## Protection and validation

The original 182-file frozen manifest is retained. 179 files remain byte-identical. Three files have explicit reviewed fragments recorded in scripts/reviewed-refinements-0.9.11.json: NeXtepWindowController (capture scheduling/layout command/menu avoidance), WorkspaceSwapAnimator (two-frame readiness), TaskSurfaceCompat (read-only readiness API). The protection check verifies updated hashes, reverses only those exact fragments and checks every original baseline hash. No entire controller/engine is exempted. All task movement, geometry formulas, display producers, crop/input writers and corner policy remain original.

The emulator tests retain all existing assertions and add grouped menus under top/left/right four-producer fixtures, command/back dispatch, unchanged TextureView producer identities, capture cancellation, and real HWUI FrameMetrics. A before/after comparison uses the same four live producers and the real UiAutomation screenshot API on UI versus worker; it measures screenshot scheduling, not actual ColorOS task migration or phone FPS. Its raw results are emitted to refinement-results.txt. It does not assert that timing improvement on a fixture proves phone improvement.

ColorOS17 native gestures, task lifecycle/performance and FindN5 folding remain device acceptance items. Loaded SystemUI identity is 0.9.11/26-glass-transition; the app checks this independent process response, not just the installed package version. Original certificate required; no private signing materials or public Release are committed.
