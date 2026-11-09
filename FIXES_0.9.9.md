# Native shade / bounded glass menu, 0.9.9-dev

Base 273c4d6, versionCode23. Stable 0.9.8 backup retained. Runtime protocol and derived viewport revision remain unchanged. No geometry/config migration.

182 stable runtime Kotlin files are byte-frozen, including WorkspaceGeometry, WorkspaceViewport, all adaptive/top/live/main metrics, TaskSurfaceCompat, controllers/presenters, four slots, quickbar, input and existing injection installation. Existing tests and new frozen-source guard enforce this boundary.

Only notification-shade-specific rendering/input adapter is retired: no root scale/translation/crop, no custom InternalInsets listener, no FLAG_NOT_TOUCH_MODAL override and no MotionEvent inverse/restoration. Native notification/control-centre appearance and input remain owned by ColorOS. Main and slot transforms are untouched. Existing normal status/corner gesture detectors remain unchanged.

Read-only native window observation uses existing SystemUI window hooks, lifecycle/layout/focus/pre-draw callbacks and a scoped same-process root inventory. Only native notification/control-centre title/class matches are observed. Focusable expanded windows block/dismiss menus; ambiguous visible full-screen OEM windows block safely. Native visibility can change without geometry/layout changes: pre-draw/focus callbacks dismiss before the visible panel frame. No periodic idle polling. Diagnostics record only state/flags/visibility, never notification content. A user menu request may ask StatusBarManager to collapse panels, then waits at most1200ms for visibly closed state stable for80ms. Failed/unknown state never forces an overlay. Generation/attachment checks prevent stale delayed menus after exit or replacement.

Menu:20dp corners, one82–86% opaque neutral gradient, transparent rows with standard ripple, max300dp width /58% safe height and scrolling,48dp minimum rows,4dp soft elevation. Popup owns no Window for a dependable public scoped background blur API, so the permitted translucent fallback is used. No full-screen dim/blur, screenshots or global compositor changes. Outside touch is not modal. Existing actions are unchanged.

P2 native notification glass is intentionally not implemented: no new experimental flag, theme resource overlays or extra hooks. Native appearance is the safe fallback.

Tests distinguish production adapter and real popup fixtures, unmodified AOSP native notification/QS gestures, and unverified ColorOS injection. No actual PMS110/Find N5 is connected; those device results must not be claimed.
