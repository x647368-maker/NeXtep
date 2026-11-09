# 0.9.7-dev slot availability / hidden producer cold-start

Base b8e6132 (0.9.6), versionCode 21, runtime55, derived geometry revision17.

- Empty hidden cards must still draw their TextureView producer under an empty clip. A zero-alpha parent with skipped dispatchDraw prevents the SurfaceTexture callback and therefore VirtualDisplay creation. Four hidden 1x1 hosts initialize without occupying card space; real occupied cards use actual live canvas geometry. No producer detachment or app restart added.
- All three add paths share SlotTaskCoordinator.addWindow. Capacity requires four distinct real tasks on their bound displays, valid surfaces AND visible cards. Empty/Failed/stale/hidden are recoverable or accurately diagnosed, never capacity. One pending explicit add, one repair pass, Surface-ready callback completion and 2500ms deadline. No infinite retry, task deletion or group reset.
- Window parent bounds use the same requested geometry as main layout; remove stale shownWindowCount geometry override. Inactive bootstrap hosts cannot inherit offscreen four-slot placement. Actual runtime Display/WindowMetrics, density, status/cutout/gesture-safe insets are sampled; v2 derived cache keys do not clear layout choice, groups or other preferences.
- Runtime diagnostics show state/task/display/texture/visible/bounds/failure, no private page content. Actual phone logs not connected; native emulator startup logs and unit tests must be distinguished from PMS110 acceptance.
- No PLAY/PAUSE, display resize, launch in passive exchange/recovery, new injection framework or static runtime preview.
