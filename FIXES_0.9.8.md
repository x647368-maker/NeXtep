# 0.9.8-dev: full source presentation and control windows

Base 98d280f. versionCode22, runtime56, profile revision18, derived viewport keys v3.

- Insets constrain the destination only. Full screen task source (0,0,W,H) is used by Shell SurfaceControl fitting, existing system_server promotion fitting, Launcher/decor fitting, shade and swap-preview crops. Inverse input uses that same matrix. No display/DPI resizing or new injection targets.
- mainInset is an explicit destination margin. Complete policy12dp, priority0dp with56dp control band. Geometry codec carries all13 fields across processes. App full source never changes between policies. Top four-card footer may be recovered only with ready narrow input region and no visible add button; cards stay the same size. Fewer-card footer remains usable.
- Window-local touch regions do not subtract the screen/window origin twice. Main and sidebar controls live in an independent48dp toolbar lane. Quickbar reserves both lanes and keeps cached entries across hide/show. Popups replace the previous popup and provide scrollable native rows.
- Capacity/hidden producer fixes from0.9.7 remain; no clear data, forced stop, auto media controls or new task engine.
- Native tests use real SurfaceControl transactions through production writeFrame, coloured source edges and inverse touch,20 policy changes, offset-window InputDispatcher quickbar taps and20 main menu cycles. These are isolated Android fixtures, not tests of actual third-party apps or ColorOS injection.
