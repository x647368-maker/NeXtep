# 0.9.10 diagnostic and compact-menu candidate

Phone feedback rejects 0.9.9. Screenshots show a fitted native shade and a menu taller than the delivered 58% limit. The delivered code-24 APK is SHA256 7d8335fb69a505b4889d2bf8e466ef9ff3ebec5f61d907928ef06f2248381f20 and contains no shade transform. This is evidence consistent with stale SystemUI injection, not proof of which module is running. Protocol 56 is shared with 0.9.8, so the prior connected indicator cannot verify loaded code.

Add an independent signature-permission protected read-only loaded-code stamp receiver. Register from existing shade adapter activation/root/menu paths; never modify the frozen startup, geometry or task files. The app reports independently whether compiled code 25 responded; missing response is explicitly unverified, not asserted to be old. It does not stop, restart or disable SystemUI or any module.

Recognize native notification-panel window types 2040/2014 in addition to names, and use incoming LayoutParams before add/update rather than only the old view LayoutParams. No window flags, touch events, surfaces, input mapping or native panel geometry are changed. Compact menu max width 236dp, height min(300dp,40% safe height); actions remain scrollable. No forced panel collapse or global touch interception added.

The shade-native manifest hash is refreshed only for the approved shade adapter identity-registration addition. Its no-transform assertions and every one of the 182 stable baseline hashes remain enforced. ColorOS native gestures remain UNVERIFIED until the phone reports actual loaded identity and is tested. The emulator tests are fixtures plus unmodified AOSP, not LSPosed/ColorOS injection.
