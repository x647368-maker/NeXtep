package io.github.x647368maker.atomicworkbench.xposed

import io.github.x647368maker.atomicworkbench.logging.NeXtepLog

object HookGuard {
    inline fun run(feature: String, block: () -> Unit): Boolean = try {
        block()
        InjectionDiagnostics.stage(feature,true)
        true
    } catch (error: Throwable) {
        InjectionDiagnostics.stage(feature,false,error)
        NeXtepLog.error(feature, "Hook initialization failed open type=${error.javaClass.simpleName}")
        false
    }
}
