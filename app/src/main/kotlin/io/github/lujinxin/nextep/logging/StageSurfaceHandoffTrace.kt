package io.github.x647368maker.atomicworkbench.logging

import android.os.Trace
import android.view.SurfaceControl

/**
 * Optional cross-process trace breadcrumbs. Only when the OS trace tag is
 * enabled; never starts tracing, creates a transaction, polls or queries Binder.
 * A write/submit marker is not a commit, and layer IDs may be inaccessible.
 */
internal object StageSurfaceHandoffTrace {
    private val layerGetter by lazy {
        runCatching {
            SurfaceControl::class.java.getDeclaredMethod("getLayerId").apply { isAccessible=true }
        }.getOrNull()
    }

    fun layerId(leash:Any):String = runCatching {
        (layerGetter?.invoke(leash) as? Number)?.toLong()
            ?.takeIf { it>=0 }?.toString() ?: "unknown"
    }.getOrDefault("unknown")

    inline fun mark(message:()->String) {
        // Never let optional diagnostics change task/transaction handling.
        if(!Trace.isEnabled()) return
        runCatching {
            Trace.beginSection(("AW:"+message()).take(127))
            Trace.endSection()
        }
    }
}
