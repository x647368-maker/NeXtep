# 0.9.6-dev — live-preview clipping and compact presentation repair

Base ed1c122, APK versionCode 20, runtime protocol 54, recorded geometry revision 16.

## Confirmed mismatch
The v0.9.4/v0.9.5 preview and sizing code was identical. 0.9.5 added display-local rail selection/restoration, exposing an existing mismatch: producers retain their original canvas to preserve activity state, while card bounds were computed from the newly selected rail's requested canvas. A 720×1120 frozen producer inside a portrait side card is letterboxed. The parent-only outline clips the card, not the inner live-image corners. Menu dots sit above the inner image. Therefore old successful geometry-only tests missed this real rendering defect. This is not a removed-radius line in 0.9.5.

## Repair
- StageLiveCardLayout fits each card to its actual retained producer, then densely positions only occupied cards. Independent side/top strategies; no second scale/reserved empty slots. Menu remains on the app; empty cards draw nothing without detaching their TextureViews.
- Both the actual TextureView render node and the parent draw pass clip the live-image rectangle. Nonopaque texture composition and 16dp visible preview radius (bounded on tiny buffers). Alpha-only entry keeps the clip and live image aligned during transition.
- Existing main SurfaceControl corner policy and task/input engines remain unchanged. No display resize, activity launch or media transport calls added.
- Revision16 rejects derived revision15 records. Display-local choices, groups and user data are not cleared.
- Native instrumentation reproduces old inner-square corner and tests the real changing TextureView producer through Android HWUI. Test pattern is only in androidTest, never runtime app previews. Emulator signing uses an isolated disposable test-only key; delivered APK uses the original private certificate.

## Limits
Full portrait content constrained by remaining height cannot simultaneously fill 95% width without crop, reflow or display resizing. These unsafe options are not introduced. Top main area cannot universally exceed side main area under those constraints. Source geometry data and real Android render results are reported separately; no connected PMS110/Find N5, third-party app/input/fold acceptance remains unverified.
