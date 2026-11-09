# LSP coexistence investigation / v0.9.13 (28)

Base verified: atomic-workbench/v0.9.12 aa1fd0e2319174dcb4172f8b1a5d2e5d63799f41.
Independent branch: atomic-workbench/lsp-coexistence. Existing stability work and stable branches remain untouched.

## Evidence, not a device-conflict diagnosis

No logs from user A's failing LSP/ColorOS process are available. A working user B disproves the claim that all LSP installations are incompatible, but does not establish a specific conflicting module. The following are reproducible source/contract defects, not proof of the vendor incident's root cause:

* The old package dispatcher rejected every non-first callback, including SystemUI in the confirmed SystemUI main process. A pure dispatch test reproduces that rejected path; the exception is now limited to that exact package/process pair. Other package scopes and launcher behavior remain unchanged.
* The old first-package gate also admitted SystemUI auxiliary processes. Known `com.android.systemui:...` auxiliaries are now rejected before installing workspace/control hooks, preventing extra same-package process responders. Unknown first-process names retain old behavior; actual loaded process is reported. This source-level risk is not proof of user A's observed trigger.
* SystemUiHook had no duplicate-install gate. Concurrent/repeated initialization is now claimed once per process/module generation. Existing Hook installation order and every original SurfaceControl, ViewGroup and WindowManagerGlobal interceptor body remain unchanged. No priority overrides, invoke-origin shortcuts or replacement hooks.
* The old diagnostic receiver was registered through later UI/root paths, hiding evidence when initialization failed earlier. The read-only, signature-protected probe now registers at Application bootstrap, before workspace initialization, and exposes failures separately from workspace communication.
* The old UI hid injection identity when workspace communication was disconnected and instructed users to disable other modules. Both misleading behaviors are removed. No response remains unknown, never a declaration of another module's conflict.

## API and scope audit

The app is modern-libxposed-only and never invokes legacy XposedBridge APIs. minApiVersion=101 and targetApiVersion=102 are unchanged. Official Maven API 101.0.0 and 102.0.0 artifacts both contain the no-arg XposedModule constructor, onModuleLoaded/onPackageLoaded/onSystemServerStarting, getProcessName, chain.proceed, and the framework identity getters used here. No fallback reflection accesses framework APIs. CI compiles and runs JVM contracts against the actual API 101 artifact, then builds/tests against API 102; this does not emulate either real framework or prove modern/legacy coexistence.

Official scope documentation distinguishes SystemUI, the `system` virtual package for system_server, and Launcher packages. Existing scope.list is unchanged. The new receiver reports compiled SystemUI code, PID, process start time, framework/API, initialization stages and bounded error class names. It cannot infer that a process not responding was never loaded, nor identify a framework it never executed inside. System_server and Launcher initialization stages remain visible in their own process logs; SystemUI evidence is not presented as proof that those other processes loaded.

API 102 hot reload does not automatically replay normal load/package callbacks. This stateful module does not opt into it; autoHotReload=false is explicit. Normal device restart after APK upgrades remains required. No framework detach/attach or security bypass is attempted.

Primary references:
https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html
https://libxposed.github.io/api/io/github/libxposed/api/XposedModuleInterface.html
https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.0/api-101.0.0.aar
https://repo.maven.apache.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar

## Safety and limits

Initialization failures are isolated and stage/error-type evidence is retained without exception messages or other-module inventories. Optional unavailable ROM candidates do not masquerade as critical failures. Duplicate suppression does not claim to repair a partially initialized process: critical failure remains visible and requires process/device restart, rather than blindly stacking hooks. No task migration, crop, window proportions, input-coordinate mapping, menu glass, native shade or group data code is changed. Exact reviewed source normalization enforces all original frozen hashes.

No real rooted LSP/ColorOS device is connected. Matrix rows requiring real framework injection (alone, API102 companion module, legacy companion module, reboot/toggle, actual SystemUI restart, workspace enter/exit/re-enter) remain NOT VERIFIED. JVM next-interceptor tests preserve result/exception and exactly one proceed; they are not framework coexistence tests. Existing emulator workspace/shade/UI regression remains mandatory. No public Release is authorized.
