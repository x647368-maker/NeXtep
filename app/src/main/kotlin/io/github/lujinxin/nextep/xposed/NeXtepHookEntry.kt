package io.github.x647368maker.atomicworkbench.xposed

import android.util.Log
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.launcher.ColorOsLauncherHook
import io.github.x647368maker.atomicworkbench.multiapp.OplusMultiAppHook
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.safety.EmergencyDisable
import io.github.x647368maker.atomicworkbench.safety.FeatureGate
import io.github.x647368maker.atomicworkbench.systemui.SystemUiHook
import io.github.x647368maker.atomicworkbench.systemserver.SystemServerHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class NeXtepHookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        NeXtepLog.info("module", "NeXtepHookEntry loaded; systemServer=${param.isSystemServer}")
        if (param.isSystemServer) {
            log(Log.INFO, "NeXtep", "system_server module generation loaded")
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, "NeXtep", "system_server starting callback received")
        if (EmergencyDisable.isRequested() ||
            !FeatureGate.SYSTEM_SERVER_PATCHES.defaultEnabled
        ) {
            log(Log.WARN, "NeXtep", "system_server hooks disabled by safety gate")
            return
        }
        SystemServerHook.install(this, param.classLoader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return
        val packageName = param.packageName
        NeXtepLog.info(
            "dispatcher",
            "Observed package=$packageName",
        )

        if (packageName == "com.android.systemui") {
            if (!EmergencyDisable.isRequested() && FeatureGate.SYSTEM_UI.defaultEnabled) {
                TaskSurfaceCompat.initialize(param.defaultClassLoader)
                SystemUiHook.install(this, param.defaultClassLoader)
            }
            return
        }

        if (packageName == "io.github.x647368maker.atomicworkbench") {
            return
        }

        if (packageName == "com.oplus.multiapp") {
            if (!EmergencyDisable.isRequested() &&
                FeatureGate.LAUNCHER_TRANSFORM.defaultEnabled
            ) {
                OplusMultiAppHook.install(this, param.defaultClassLoader)
            }
            return
        }

        if (EmergencyDisable.isRequested() || !FeatureGate.LAUNCHER_TRANSFORM.defaultEnabled) return

        ColorOsLauncherHook.install(this, packageName, param.defaultClassLoader)
    }
}
