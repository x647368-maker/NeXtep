# Native shade / bounded glass menu, 0.9.9

Base 273c4d6, versionCode24. Stable 0.9.8 backup retained. Runtime protocol and derived viewport revision remain unchanged. No geometry/config migration.

182 stable runtime Kotlin files are byte-frozen, including WorkspaceGeometry, WorkspaceViewport, all adaptive/top/live/main metrics, TaskSurfaceCompat, controllers/presenters, four slots, quickbar, input and existing injection installation. Existing tests and new frozen-source guard enforce this boundary.

Only notification-shade-specific rendering/input adapter is retired: no root scale/translation/crop, no custom InternalInsets listener, no FLAG_NOT_TOUCH_MODAL override and no MotionEvent inverse/restoration. Native notification/control-centre appearance and input remain owned by ColorOS. Main and slot transforms are untouched. Existing normal status/corner gesture detectors remain unchanged.

Read-only native window observation uses existing SystemUI window hooks, lifecycle/layout/focus/pre-draw callbacks and a scoped same-process root inventory. Only native notification/control-centre title/class matches are observed. Focusable expanded windows block/dismiss menus; ambiguous visible full-screen OEM windows block safely. Native visibility can change without geometry/layout changes: pre-draw/focus callbacks dismiss before the visible panel frame. No periodic idle polling. Diagnostics record only state/flags/visibility, never notification content. A user menu request may ask StatusBarManager to collapse panels, then waits at most1200ms for visibly closed state stable for80ms. Failed/unknown state never forces an overlay. Generation/attachment checks prevent stale delayed menus after exit or replacement.

Menu:20dp corners, one82–86% opaque neutral gradient, transparent rows with standard ripple, max300dp width /58% safe height and scrolling,48dp minimum rows,4dp soft elevation. Popup owns no Window for a dependable public scoped background blur API, so the permitted translucent fallback is used. No full-screen dim/blur, screenshots or global compositor changes. Outside touch is not modal. Existing actions are unchanged.

P2 native notification glass is intentionally not implemented: no new experimental flag, theme resource overlays or extra hooks. Native appearance is the safe fallback.

Tests distinguish production adapter and real popup fixtures, unmodified AOSP native notification/QS gestures, and unverified ColorOS injection. No actual PMS110/Find N5 is connected; those device results must not be claimed.

Native AOSP quick settings may remain in SystemUI after the first upward gesture. The separate emulator baseline test records whether a second native swipe is needed (maximum two); no workspace or SystemUI production code is changed for this emulator-only observation. ColorOS single-swipe behaviour still requires PMS110 validation.

Takeover audit: the existing signed versionCode23 APK and original private PKCS12 key were recovered. Both match the fixed SHA-256 certificate b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd and signed 0.9.8 versionCode22. CI signing Secrets remain unset; deliverables are signed privately with this original key, never an emulator key. No private signing material is committed.

Remaining menu-state fix: a readable root inventory with zero recognized native panel roots is UNKNOWN in SystemUI, not CLOSED. Failed inventory is also UNKNOWN. This prevents an unrecognized/detached OEM panel from allowing a menu overlay; all frozen geometry/task/input files remain unchanged. The real popup test verifies detached/missing-root behavior.

The native-shade test now verifies WindowManager input focus independently of the accessibility active-window cache, retains window/statusbar dumps and screenshots on failures, and adds raw pull-down/up gestures. Failure assertions are retained. Emulator AOSP results do not validate ColorOS module injection.

Diagnostic run 42578032996: actual notification shade visibly expanded to 1272x2772, WindowManager reported shown=true/isVisible=true, and accessibility eventually reported com.android.systemui. The initial focus probe read the windows-only dump, which omits display focus on this emulator; the final probe reads the full dump. Native open/close assertions remain mandatory. Native swipe events explicitly use SOURCE_TOUCHSCREEN.
