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
    val evidence=StageHandoffEvidenceBuffer(48)
    private val layerGetter by lazy {
        runCatching {
            SurfaceControl::class.java.getDeclaredMethod("getLayerId").apply { isAccessible=true }
        }.getOrNull()
    }

    fun layerId(leash:Any):String = runCatching {
        (layerGetter?.invoke(leash) as? Number)?.toLong()
            ?.takeIf { it>=0 }?.toString() ?: "unknown"
    }.getOrDefault("unknown")

    private inline fun captured(block:(StageHandoffEvidenceBuffer.Capture,Long,Long)->Unit) {
        if(!evidence.hasOpenCapture())return
        runCatching {
            val now=SystemClock.elapsedRealtime()
            val c=evidence.ticket(now) ?: return
            block(c,now,SystemClock.elapsedRealtimeNanos())
        }
    }
    fun beginAttempt(id:Long,op:String)=captured { c,n,ns ->
        evidence.beginAttempt(c,n,id,ns,op)
    }
    fun endAttempt(id:Long,result:String)=captured { c,n,ns ->
        evidence.endAttempt(c,n,id,ns,result)
    }
    private val phases=listOf("mirror_root=","main_transform:","live_gpu_skip:",
        "snapshot_capture_result:","snapshot_motion_backend=","workspace_preview_selected:",
        "native_armed:","first_gpu_transaction_committed","native_terminal_transaction_committed",
        "move_tasks ","real_move_requested","real_move_ack","handoff_target_ready",
        "handoff_timeout:","endpoint_reveal_start","endpoint_reveal_commit","cover_hide_submit:",
        "native_finished:","snapshot_real_move_requested","snapshot_real_move_ack",
        "snapshot_terminal_commit","snapshot_main_reveal_begin","snapshot_main_reveal_complete",
        "snapshot_end:","endpoint_rebase_receipts")
    fun attemptStage(id:Long,stage:String) {
        if(!evidence.hasOpenCapture())return
        val key=phases.firstOrNull { stage.startsWith(it) } ?: return
        captured { c,n,ns -> evidence.attemptStage(c,n,id,ns,key,stage) }
    }
    fun detail(id:Long?,key:String,message:()->String) {
        if(id==null)return
        captured { c,n,ns -> evidence.attemptStage(c,n,id,ns,key,message()) }
    }
    fun bindPair(id:Long?,up:Int,down:Int) {
        if(id==null)return
        captured { c,n,ns -> evidence.bindPair(c,n,id,ns,up,down) }
    }
    fun writer(id:Long?,role:String,task:Int,leash:Any,sx:Float,sy:Float,x:Float,y:Float,
               l:Int,t:Int,r:Int,b:Int) {
        if(id==null)return
        captured { c,n,ns ->
            evidence.attemptWriter(c,n,id,role,task,System.identityHashCode(leash),
                StageAttemptEvidence.Sample(ns,sx,sy,x,y,l,t,r,b)) { layerId(leash) }
        }
    }
    fun taskWriter(task:Int,leash:Any,sx:Float,sy:Float,x:Float,y:Float,crop:android.graphics.Rect?) {
        if(evidence.hasOpenCapture())writer(StageExchangeTelemetry.currentNativeTapId(),"task_write_temporal",
            task,leash,sx,sy,x,y,crop?.left ?: -1,crop?.top ?: -1,crop?.right ?: -1,crop?.bottom ?: -1)
        // Keep external OS tracing independent; no per-frame FIFO in the TXT.
        if(Trace.isEnabled())runCatching {
            Trace.beginSection(("AW:ui_write task=$task layer="+layerId(leash)+
                " s=$sx,$sy xy=$x,$y c=${crop?.flattenToString()}").take(127))
            Trace.endSection()
        }
    }

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
