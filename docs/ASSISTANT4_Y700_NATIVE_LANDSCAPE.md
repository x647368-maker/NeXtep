# 备用4：Lenovo Y700 (TB324ZC) ColorOS 16 port — layout correction

## Scope and non-regression contract

Branch: `atomic-workbench/assistant4-y700-native-landscape-0.9.17`.
Base: `atomic-workbench/fast-tap-0.9.17`.
Review: draft PR #9. **Do not merge or release as stable without physical device tests.**

The owner supplied real screenshots after installing the signed Y700 test build:
- Portrait is generally usable, **but overlay buttons overlap the status bar and cannot be clicked**.
- Landscape menu **顶部横排** did not produce a distinct working top-row layout.
- Landscape left/right must be **physical left and right VERTICAL rails**, not top/bottom.
These observations supersede the earlier assistant's mistaken left=top/right=bottom design.

The tablet is physically Lenovo Y700, model TB324ZC, resolution 2560x1600, with a rooted ColorOS 16 phone-ROM port that identifies itself as OPPO OPD2515 in software. **Never classify by `Build.MODEL`, manufacturer, or ColorOS name.**

## Three truly independent, user-selected layouts

| Screen | Selected control | Expected |
|---|---|---|
| 1600x2560 portrait | Left or right | Existing usable vertical rail/main layout, with status-safe accessible buttons |
| 2560x1600 landscape | 顶部横排 | Upright 1–4 cards in one horizontal row at the top; main content below, with >=~50% of usable display height |
| 2560x1600 landscape | 左侧排列 | Upright 1–4 cards vertically stacked on the LEFT; main content to the right |
| 2560x1600 landscape | 右侧排列 | Upright 1–4 cards vertically stacked on the RIGHT; main content to the left |

The wide-tablet side-rail policy never rotates whole app preview panes 90°. `railAtTop` remains the **separate** explicit top mode. The physical display dimensions choose the wide-tablet behavior irrespective of rotation value 0/1/2/3 from the ported ROM.

The display class requires short side >= 1300 physical px and aspect ratio >= 1.45, and the tablet-only status safe inset is at least 48 physical dp. This safely excludes the OnePlus Ace 7 Pro (1140x2616) and the OPPO Find N5 expanded near-square screen (2248x2480).

## Changes and checks

- `WorkspaceGeometry`: physically left/right unrotated side rails, correct safe main bounds and separate top layout.
- `StageLiveCardLayout`: uses the existing portrait-independent VERTICAL stacking for side rails and existing horizontal fit for top rails.
- `NeXtepWindowController`: left/right menu options, centered side cards and side-specific add/smart-rail handles.
- `StageTopLayoutMetrics`: cap wide-tablet top-row height to 32% of usable height to prevent an overlarge row crushing the main task.
- `WorkspaceViewport` + `StageDisplayPolicy`: repair undersized/zero status-bar top inset for wide-tablet layouts on a ported ROM, including cached profiles, without editing the recorded original.
- Tests cover real physical left/right positions, portrait 48dp status safety, distinct top-row geometry, 1–4 cards, spoofed rotations, and exclusion of primary phone/fold devices.
- Frozen source integrity is retained; changes are whitelisted only as exact reversible differences from the v0.9.17 base, never by disabling general checks.
- No changes to task move operations, virtual display recreation, signing identity, update feed, APK package ID, or mainline branches.

## Important limitations

These are source-level adjustments informed by **failed** real Y700 screenshots. The new changes still require another physical Y700 installation and interaction test to establish that they solve the three reports. Device-resolved system insets, vendor input interception, top-menu event dispatch, and actual multiwindow task behavior may differ from JVM tests.

A separate pre-existing v0.9.17 Android emulator visual test fails with `Rollback preview did not settle within 3 seconds`. This is not solved here and is not evidence that every visual scenario passes.

### Device acceptance (ask user for only one new 20–40s recording)

1. Portrait: main handle and quick/accessibility buttons no longer overlap status bar and are all clickable; notification shade still expands/collapses normally.
2. Landscape: switch **顶部横排 → 左侧排列 → 右侧排列 → 顶部横排**, verifying visibly distinct placement of all live apps.
3. Move/swipe/click each live card, add fourth app, main↔mini swaps, collapse and reopen the side rail; no sideways view or click-through to status bar.
4. Physical OnePlus Ace 7 Pro portrait/landscape + OPPO Find N5 folded/unfolded checks prior to release.
5. Maintain original signing certificate (SHA-256 `b25ba1046d53510a1378b3404160d27a222a3ec9993b0a722b8f61304ac4abdd`) and version identity. CI unsigned APK only a test candidate, not a public stable release.

Do not use this branch as the shared stable baseline for backup 1/2/3 until main branch integration and verified regression tests.
