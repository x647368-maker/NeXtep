package io.github.x647368maker.atomicworkbench.logging

import android.os.Trace
import android.os.SystemClock
import android.view.SurfaceControl

/**
 * Optional cross-process breadcrumbs for an explicitly armed user diagnosis
 * or OS trace; never starts tracing, creates a transaction, polls or queries Binder.
 * A write/submit marker is not a commit, and layer IDs may be inaccessible.
 */
internal object StageSurfaceHandoffTrace {
    val evidence=StageHandoffEvidenceBuffer()
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
        runCatching {
            val enabled=Trace.isEnabled()
            if(!enabled && !evidence.hasOpenCapture())return
            val now=SystemClock.elapsedRealtime()
            val ticket=evidence.ticket(now)
            if(!enabled && ticket==null)return
            val nanos=SystemClock.elapsedRealtimeNanos()
            val text=message()
            if(ticket!=null)evidence.record(ticket,now,nanos,text)
            if(enabled) {
                Trace.beginSection(("AW:"+text).take(127))
                Trace.endSection()
            }
        }
    }
}
