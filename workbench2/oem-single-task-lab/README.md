# PMS110 OEM single-task lab — NOT deployable yet

This is an isolated **compile-only** Android activity and a fail-closed permission probe.
It is NOT a working native task engine and MUST NOT be installed on the owner's daily-use phone.

The code compiles only against public Android APIs and resolves the OEM
`com.oplus.flexiblewindow.FlexibleTaskView` at runtime by reflection. The exact
`init(Bundle)` and `release()` method names and the two detach flags are based on
PMS110 framework-1.jar DEX. Their accessibility, correct Bundle shape, launch
container ownership, hidden-API enforcement and safety remain unverified.

Safety gates:
- Only enabled for a real system app with a **granted** `com.oplus.permission.safe.WINDOW`.
- No root injection, no permission bypass, no TaskOrganizer registration, no background loops.
- The experiment has no verified no-delete `release()` or abnormal-death recovery.
- Do not install this on a production device, grant privilege, or attempt cross-app task IDs
  until the task-return lifecycle is proven on an isolated disposable test environment.
- The original app's package/signature/data and frozen features are untouched.

Next: check OEM `init(Bundle)` full required options, `resetBeforeRelease()` and
`FlexibleWindowObserverMaps.onCallbackDied()` for safe task restoration; only then
enable an owned-task end-to-end runtime experiment and measure Activity instance,
Task ID, buffer/input and exact return geometry. No APK release before real verification.

## Independent bridge checkpoint

`OemBridgeService` runs in a private `:bridge` process, is not exported,
performs read-only OEM permission and class checks, and exposes only a local
diagnostic binder. It does not register an OEM observer, start/reparent tasks,
modify the system, install as a privileged app, or implement recovery.
`BridgeAdmission` has explicit LOCKED, PERMISSION_VERIFIED,
RETURN_UNPROVEN and READY_FOR_ISOLATED_TEST states. **All states deny task
mutation** until a separately reviewed real implementation proves return.
Unit tests cover missing privilege, partial return, simulated positive evidence,
restart invalidation, and revocation. A simulated READY state is NOT evidence
of OEM task restoration or of permission being granted on PMS110.

The service is intentionally not a functioning native task host yet.
Normal return and crash recovery remain blocked on OEM service behavior,
legitimate privilege grant, and disposable-device evidence. The user's
authorization is limited to cloud development and isolated tests, not
modifying any phone system partition or installing Root modules.
