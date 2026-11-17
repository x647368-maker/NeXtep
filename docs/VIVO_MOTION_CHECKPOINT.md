# Vivo motion repair checkpoint

## Baseline verified 2026-11-17

Repository x647368-maker/NeXtep.
Development branch atomic-workbench/v89-vivo-packed-rail-crossing.
Frozen baseline 32cb2eba0f50c5f66f589ad59553053d66a4b69a.
CI 44042576015 / #1494 completed successfully for development, API35 visual regression and API37 native mirrors.
649 JVM tests passed. Source integrity check passed.
User has not accepted this candidate on OnePlus PMS110.

## Evidence

User reference 4454.mp4, Library libfile_e65bb6adbe5081919cf94a92329be8cf.
Vivo component archive vivo压缩.zip, Library libfile_28a100a264c081919eeebcad9462938a.
OnePlus failure recording 4448.mp4, Library libfile_49006a43f9b48191acd1a7e716d65ae8.
Device report 台前调度-故障报告-1794900086604.txt, Library libfile_365432b957fc8191a59fb434f9284e15.
Video observations support opposite-direction promotion/demotion with incoming card in front. Exact easing has not been extracted.
OnePlus #25–29 showed 603–773ms total, flight finishing about 360–380ms followed by 180–330ms waiting before destination reveal.

## Completed source changes

373fbc9 added direct crossing with constant incoming-front stacking and protected-neighbor rejection.
32cb2eb added a reversible rounded rail path for packed slots. 3/4 slot fixtures verify matching reverse trajectory, forward rail progress and protected neighbors.
Both native GPU and snapshot planners call the new selectors.
Fallback still retains older planners. This can reintroduce different trajectories when new admission fails.
Do not claim universal direction consistency or Vivo parity.

## Work item 1 — GPU test coverage

Discovered API37 preparePairedRailRuntimeTest still selected the older adaptive planner.
Current change makes covered fixtures choose direct crossing, then reversible rail crossing, matching the new production routes.
Status: source changed, full Android test still required.
Existing pixel assertions must be assessed against the intentional overlap of incoming/outgoing cards. Do not remove a failing assertion merely to obtain green CI.
Add coverage that proves both new route types, protected slots, cleanup and rejected stale task migration.

## Remaining priority work

1. Finish actual new-route GPU coverage and inspect raw compositor evidence.
2. Audit every fallback for direction consistency.
3. Investigate post-flight migration wait without starting unsafe task reparenting while mirrored producers remain moving.
4. Measure Vivo easing/scale timing and inspect relevant vendor transition bytecode.
5. Fix picker/keyboard cropping visible in supplied screenshots.
6. Original-signature private APK, exact source/content verification, then OnePlus acceptance.

## Signing

#1494 unsigned artifact 14310575840. Original installed certificate SHA256 3703b59c48b1c30df872fbcd8fa01905b9a6c684f151ddbb0d05e5b5726468fe.
Mac signer #1494 saved as libfile_cfbc04bcd68c8191a083ca623dbcbf54.
A previous window produced original-signed #1490 (libfile_c2786209dbe08191809acba6af47f122). Re-check that authorized signing route before asking the user to perform signing.
Never deliver #1490 as a new repair.

## Resume protocol

Read this file, latest branch HEAD and exact-commit CI. Continue pending work.
Preserve source-integrity reverse manifests. Record each completed fix and validation separately.
No main merge, public release or update-feed publication before device acceptance.
