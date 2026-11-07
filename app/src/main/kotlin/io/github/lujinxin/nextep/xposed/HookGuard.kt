package io.github.x647368maker.atomicworkbench.xposed

import io.github.x647368maker.atomicworkbench.logging.NeXtepLog

object HookGuard {
    inline fun run(feature: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (error: Throwable) {
        NeXtepLog.error(feature, "Hook initialization failed open", error)
        false
    }
}
