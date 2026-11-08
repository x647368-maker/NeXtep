# Stage Manager 0.5.0 — reference interactions

This Android derivative retains NeXtep's native task/virtual-display engine and Apache-2.0 attribution. Apple and vivo references guide interaction only; this is not an official Apple/vivo app.

Implemented for verification on PMS110 / ColorOS 17:
- Persisted top horizontal / side vertical layout, shared task fitting and input geometry, four rotations.
- Main grip menu: native fullscreen (exit stage retaining live task records), layout switch, replace main, park main in background, exit.
- Small-window replace/close still uses real tasks. Outward swipe closes side windows; top row uses left swipe. Main close gesture is scoped to the grip.
- Only occupied windows are shown (0–4). Empty capacity is not rendered.
- Icon grid app chooser with search and actual profile-specific launcher identities; picker requests no IME resize. Explicit light/dark palette.
- Side cards no longer overlap or tilt aggressively. Each has its own 24dp menu header above the fitted app buffer. Zero-window mode uses a narrow 52dp add gutter, with input and main geometry agreeing even if the custom input-region hook is unavailable.
- Native GLOBAL content drag bridge: dwell over a side card raises its real task to main, then move into main and drop. Android and the target app own payload/URI permission handling. Scoped DragState coordinate correction accounts for task surface scaling. No simulated payload delivery.

Limits: content drag requires source and target support and remains experimental pending physical-device validation. This is not a claim of universal OriginOS cross-window sharing. Closing parks tasks; it does not force-stop apps. Layout is a workspace preference, not a separate per-group preference.

Validation: build/unit/signature checks will be recorded with the delivered APK. No attached device is available; physical rendering, ROM behavior, input and drag must be checked on the phone.
