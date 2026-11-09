package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.libxposed.api.XposedInterface

class SystemUiStartupHooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        val target = chain.thisObject
        val result = chain.proceed()
        try {
            val context = SystemUiContextResolver.fromHookTarget(target)
            if (context == null) {
                NeXtepLog.warn("systemui_startup", "Startup target did not expose a Context")
            } else {
                StageRuntimeIdentity.install(context)
                SystemUiRuntime.initialize(context)
            }
        } catch (error: Throwable) {
            NeXtepLog.error("systemui_startup", "Initialization failed open", error)
        }
        return result
    }
}
