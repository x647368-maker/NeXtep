# Assistant 4 — native-landscape Lenovo Y700 compatibility (isolated)

## Branch / ownership

- Branch: `atomic-workbench/assistant4-y700-native-landscape-0.9.17`
- Created from: `atomic-workbench/fast-tap-0.9.17`
- **Do not merge automatically**. The primary `接管安卓项目` developer retains priority.
- No app ID, versionCode, signing material, update feed, task identities or existing group storage is changed.
- Future releases should carry this set of changes forward *after* mainline regression review.

## Evidence and problem

User video and screenshots (Nov 10, 2026) show a Lenovo Legion Y700
(TB324ZC, 2560 x 1600 pixels) running a rooted ColorOS 16 port.
The ROM branding / spoofable model props do **not** indicate an actual OPPO
device. On the native-landscape tablet, the existing phone-only
`WorkspaceGeometry.panelRotationDegrees` rotates the bottom/top preview
rail 90 degrees; several live cards are sideways and controls are difficult
to use with a status bar in the same area.

## Isolated implementation

1. Only screen coordinates, rotation and wide high-resolution aspect class
   select the native horizontal-rail policy. Do not check `Build.BRAND`,
   `Build.MANUFACTURER` or `Build.MODEL`; these can be spoofed by the port.
2. Trigger only when the screen is physically landscape (rotation 0/2),
   aspect ratio >= 1.45 and short side >= 1300 pixels; ordinary phone
   portrait and reverse landscape, and near-square foldable displays,
   remain on the current tested geometry.
3. Native-landscape LEFT/RIGHT correspond to a physically upright
   **top/bottom** rail. The control strip is horizontal across the safe
   top; its visible buttons are below reported status/cutout insets.
4. Live card X coordinates are computed across a physical horizontal row,
   not the portrait Y stack. TextureView and virtual-display source
   aspect are preserved; there is no per-switch resize, destructive
   display recreation or blanket Android orientation override.
5. Status bar is **not** globally hidden. Inset-aware safe areas and
   non-overlapping control/rail/main rectangles are verified by tests.

## Regression matrix

- Lenovo Y700: 2560x1600 at rotation 0 and 2; top and bottom rails;
  1, 2, 3 and 4 occupied slots; 1600x2560 portrait rotation.
- OnePlus Ace 7 Pro (phone): 1140x2616 portrait, 2616x1140 landscape.
- OPPO Find N5 expanded (near-square fold): 2248x2480 portrait,
  2480x2248 landscape. Existing phone/fold rotation policy unchanged.
- Report status insets, nav-button mode, touchable control areas,
  app input projection, main/slot exchanges and repeated rotations.

## Must check on actual device before claiming perfect compatibility

- Confirm `displayRotation` and pixel size in `slotStatesDescription()`
  and distinguish 0/2 naturally-landscape tablet from a 1/3 phone rotation.
- Try LEFT and RIGHT configurations in Y700 landscape and in portrait.
  Ensure all previews are upright, card order remains correct, and tap,
  drag, expand/collapse/add remain accessible.
- Confirm status shade can be swiped down and back, no unsafe overlap.
- Switch main and mini tasks repeatedly without shape-changing
  transitions, lost contents, black frames or broken touch coordinates.
- Verify Ace 7 Pro and Find N5 folding/rotation and video fullscreen.
- A vendor ROM may override WindowMetrics or rotation reporting. If
  reported rotation differs from the standard mapping, collect real
  non-sensitive geometry/rotation logs and refine the isolated policy,
  rather than blindly broadening its trigger to every ColorOS device.

## Merge/release guard

A draft PR should target the `fast-tap-0.9.17` base (not `main`).
If main developer advances to a newer release, **rebase/cherry-pick**
these exact changes only after resolving the upstream differences and
running unit + actual-device regression. GitHub Actions artifacts are
not automatically a signed/installable release; never create a new
certificate or publish an unsigned APK as a user-facing build.
