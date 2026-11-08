# 0.6.0 implementation and physical verification limits

Fixes prompted by 0.5.0 device screenshots:

- The chooser is now opened before any catalogue I/O, shows a loading state on a cold cache, warms and reuses a single asynchronous catalogue, invalidates on package changes/unlock, and uses GridView cell recycling. Search is fixed above the scrolling icon grid. Workspace controls are hidden while the chooser is visible.
- Top windows use a real 720x1120 / 320dpi virtual display (360x560dp) rather than inheriting the full physical phone layout. Native task configuration and rendering are resized; no clipped screenshot is substituted. Compact windows use their own insets, with the physical-aspect hook scoped to their compact aspect. Promotion restores the task's saved default-display layout.
- Each card has an app icon/name and a menu. Its body is proportioned to the compact canvas. The row uses full available card widths and no extra presentation shrink.
- A shared geometry policy reserves a footer separate from card bodies. Collapse (left), main menu (center), add (right) have disjoint input rectangles, including zero windows. Top layout reconfigures on physical rotation.
- Optional '在小窗中操作' sends real native touchscreen MotionEvents only to the chosen non-default display, with inverse aspect-fit coordinate projection and original pointer streams. Busy/task changes cancel active gestures. Rejection falls back with an explicit message; a name click promotes the app to main. Direct touch, IME and app compatibility are not yet confirmed on the phone.

Limitations: there is no attached physical device. Unit tests prove geometric relationships and touch projection, not frame pacing, ROM permissions or all applications' layout behavior. The main task continues to use the existing uniform real-task fitting engine; this is not a claim of full vivo/OriginOS parity or a universal application adaptation layer. First catalogue loading can still take time; the sheet itself no longer waits for it before opening.

The build is local. Previous automatic approval rejected public source submission; no further public writes were attempted for this version.
