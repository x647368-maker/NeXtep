package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import io.github.x647368maker.atomicworkbench.framework.ActivityTaskManagerCompat
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.x647368maker.atomicworkbench.display.StageExchangeBinderLane
import io.github.x647368maker.atomicworkbench.task.TaskRepository
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat

/** Real AOSP task reparenting, not coloured Surface producers or a ColorOS claim.
 * No production task-engine changes are evaluated as a performance fix here.
 */
internal class TaskMigrationRegression(private val i: Instrumentation) {
    fun run(original: Activity, out: File): List<String> {
        val dm = original.getSystemService(DisplayManager::class.java)
        val am = original.getSystemService(ActivityManager::class.java)
        val consumer = HandlerThread("MigrationProbeBuffers").apply { start() }
        val readers = mutableListOf<ImageReader>()
        val displays = mutableListOf<android.hardware.display.VirtualDisplay>()
        val activities = mutableListOf<Activity>()
        val taskIds = mutableListOf<Int>()
        val frames = List(4) { AtomicInteger() }
        val lines = mutableListOf("AOSP real display migration baseline; no ColorOS/Xposed/smoothness claim")
        var sampling = true
        var lastFrame = 0L
        val gaps = mutableListOf<Long>()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (!sampling) return
                if (lastFrame != 0L) gaps.add((time-lastFrame)/1_000_000)
                lastFrame=time
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        fun taskDisplay(id: Int): Int? = am.getRunningTasks(100).firstOrNull { it.taskId==id }?.let {
            // TaskInfo.displayId is a public runtime field hidden from the SDK stubs.
            it.javaClass.getField("displayId").getInt(it)
        }
        fun awaitFrame(slot: Int, previous: Int) {
            val end=SystemClock.uptimeMillis()+5000
            while(frames[slot].get()<=previous && SystemClock.uptimeMillis()<end) Thread.sleep(20)
            check(frames[slot].get()>previous) { "Real task did not produce a fresh display buffer slot=$slot" }
        }
        i.uiAutomation.adoptShellPermissionIdentity()
        try {
            for(slot in 0..3) {
                val reader=ImageReader.newInstance(720,1120,PixelFormat.RGBA_8888,3)
                readers.add(reader)
                reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { frames[slot].incrementAndGet() } },Handler(consumer.looper))
                val vd=dm.createVirtualDisplay("AW-Migration-$slot",720,1120,240,reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
                checkNotNull(vd) { "Unable to create real task display" };displays.add(vd)
                val intent=Intent().setClassName(original.packageName,original.packageName+".config.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val a=i.startActivitySync(intent,ActivityOptions.makeBasic().setLaunchDisplayId(vd.display.displayId).toBundle())
                activities.add(a);taskIds.add(a.taskId)
                check(taskDisplay(a.taskId)==vd.display.displayId) { "Probe activity not launched on its virtual display" }
                awaitFrame(slot,0)
            }
            val ids=taskIds.toList()
            check(ids.distinct().size==4) { "Probe did not create four separate real tasks" }
            val raw=am.getRunningTasks(64).filter{it.taskId in ids}
            check(raw.size==4) { "Task decoder comparison needs the same four real tasks" }
            fun values(x:io.github.x647368maker.atomicworkbench.framework.CandidateTaskInfoCompat.WindowState?) =
                x?.let{listOf(it.displayId,it.bounds,it.windowingMode,it.resizeMode,it.densityDpi,it.token,it.vendorWindowed)}
            for(info in raw) {
                val old=io.github.x647368maker.atomicworkbench.framework.BaselineTaskInfoCompat.readWindowState(info)
                val new=io.github.x647368maker.atomicworkbench.framework.CandidateTaskInfoCompat.readWindowState(info)
                check(old!=null && new!=null && listOf(old.displayId,old.bounds,old.windowingMode,old.resizeMode,old.densityDpi,old.token,old.vendorWindowed)==values(new))
                val production=checkNotNull(io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat.readWindowState(info))
                check(listOf(production.displayId,production.bounds,production.windowingMode,production.resizeMode,production.densityDpi,production.token,production.vendorWindowed)==values(new))
            }
            val baseline=mutableListOf<Long>();val cached=mutableListOf<Long>()
            // Alternate order, same immutable snapshot batch, same UI thread and four displays.
            i.runOnMainSync {
                repeat(8){round->
                    fun sample(old:Boolean):Long {
                        val start=SystemClock.elapsedRealtimeNanos()
                        repeat(128){n->val info=raw[n%raw.size]
                            if(old)checkNotNull(io.github.x647368maker.atomicworkbench.framework.BaselineTaskInfoCompat.readWindowState(info))
                            else checkNotNull(io.github.x647368maker.atomicworkbench.framework.CandidateTaskInfoCompat.readWindowState(info))
                        }
                        return SystemClock.elapsedRealtimeNanos()-start
                    }
                    if(round%2==0){baseline.add(sample(true));cached.add(sample(false))}
                    else{cached.add(sample(false));baseline.add(sample(true))}
                }
            }
            val comparison="rejected candidate metadata comparison (production unchanged): 128 decodes/batch, 8 alternating UI-thread batches, same four real task snapshots; baseline_median_ms=${baseline.sorted()[4]/1_000_000.0} cached_median_ms=${cached.sorted()[4]/1_000_000.0}; state_fields_equal=true; not full exchange or ColorOS FPS"
            lines.add(comparison);File(out,"task-decoder-comparison.txt").writeText(comparison)
            // Matched probes: same real tasks, same query/decode/publication,
            // same destination verification and 30ms observation interval.
            // Alternate round-trip order to reduce warm-up and time-order bias.
            val repository=TaskRepository(original)
            val owner=Handler(Looper.getMainLooper())
            lateinit var lane:StageExchangeBinderLane
            lateinit var barrierLane:StageExchangeBinderLane
            lateinit var originalStates:Map<Int,TaskRepository.TaskSnapshot>
            i.runOnMainSync {
                lane=StageExchangeBinderLane { action->owner.post(action) }
                // Only the completion message changes: no front-of-queue
                // priority, no new production code, same main Looper.
                val barrierHandler=Handler.createAsync(Looper.getMainLooper())
                barrierLane=StageExchangeBinderLane { action->barrierHandler.post(action) }
                originalStates=repository.publishExchangeTasks(repository.readExchangeTasks())
                    .filter{it.taskId in ids}.associateBy{it.taskId}
            }
            check(originalStates.size==4)
            val syncGaps=mutableListOf<Long>()
            val laneGaps=mutableListOf<Long>()
            val syncElapsed=mutableListOf<Double>()
            val asyncElapsed=mutableListOf<Double>()
            val syncOwner=mutableListOf<Double>()
            val asyncOwner=mutableListOf<Double>()
            fun now()=SystemClock.elapsedRealtimeNanos()
            fun ms(ns:Long)=ns/1_000_000.0
            fun verify(id:Int,state:TaskRepository.TaskSnapshot?){
                if(state==null)return
                val origin=checkNotNull(originalStates[id])
                check(state.taskId==id && state.token==origin.token && state.userId==origin.userId){
                    "Real task identity changed during matched display move"
                }
            }
            val barrierGaps=mutableListOf<Long>()
            val barrierElapsed=mutableListOf<Double>()
            val barrierOwner=mutableListOf<Double>()
            val ordinaryCallback=mutableListOf<Double>()
            val barrierCallback=mutableListOf<Double>()
            val batchGaps=mutableListOf<Long>()
            val batchElapsed=mutableListOf<Double>()
            val batchOwner=mutableListOf<Double>()
            val batchCallback=mutableListOf<Double>()
            fun matchedMove(id:Int,destination:Int,mode:String){
                val async=mode!="sync"
                val selectedLane=if(mode=="async_barrier")barrierLane else lane
                val finished=CountDownLatch(1)
                val startTime=now()
                var outcome:Result<Unit>?=null
                var ownerNs=0L
                var workerNs=0L
                var dispatchNs=0L
                var callbackNs=0L
                var firstOwnerNs=0L
                var endedNs=0L
                var queries=0
                var callbacks=0
                var deadline=0L
                fun ownerStep(action:()->Unit){
                    val entered=now()
                    try{
                        check(Looper.myLooper()==Looper.getMainLooper())
                        action()
                    }catch(error:Throwable){
                        outcome=Result.failure(error)
                    }finally{
                        ownerNs+=now()-entered
                        if(outcome!=null){
                            endedNs=now()
                            finished.countDown()
                        }
                    }
                }
                fun <T> onLane(work:()->T,accept:(T)->Unit){
                    val queued=now()
                    var workerEnd=0L
                    var value:Result<T>?=null
                    check(selectedLane.submit({
                        val began=now()
                        dispatchNs+=began-queued
                        try{
                            check(Looper.myLooper()!=Looper.getMainLooper())
                            value=runCatching(work)
                            checkNotNull(value).getOrThrow()
                            Unit
                        }finally{
                            workerEnd=now()
                            workerNs+=workerEnd-began
                        }
                    }){ack->
                        val entered=now()
                        ownerStep {
                            callbacks++
                            callbackNs+=entered-workerEnd
                            ack.getOrThrow()
                            accept(checkNotNull(value).getOrThrow())
                        }
                    }) {"Production exchange lane unexpectedly busy"}
                }
                // Publish only on the owner. Batched first observation is
                // read-only and may not bypass identity or destination proof.
                lateinit var observeAsync:()->Unit
                fun acceptBatch(batch:TaskRepository.ExchangeTaskBatch){
                    val state=repository.publishExchangeTasks(batch).firstOrNull{it.taskId==id}
                    verify(id,state)
                    if(state?.displayId==destination)outcome=Result.success(Unit)
                    else{
                        check(SystemClock.uptimeMillis()<deadline){"Async display observation timed out"}
                        check(owner.postDelayed({ownerStep{observeAsync()}},30L))
                    }
                }
                observeAsync={
                    queries++
                    onLane({repository.readExchangeTasks()},::acceptBatch)
                }
                check(owner.post {
                    firstOwnerNs=now()-startTime
                    ownerStep {
                        if(mode=="async_batch"){
                            // Candidate only: combine an actual successful move
                            // and its first uncommitted read in one worker call.
                            // The ordinary main Handler is retained.
                            queries++
                            onLane({
                                ActivityTaskManagerCompat.moveTaskToDisplay(id,destination).getOrThrow()
                                val expiry=SystemClock.uptimeMillis()+700L
                                repository.readExchangeTasks() to expiry
                            }){(batch,expiry)->
                                deadline=expiry
                                acceptBatch(batch)
                            }
                        }else if(async){
                            onLane({ActivityTaskManagerCompat.moveTaskToDisplay(id,destination).getOrThrow()}){
                                deadline=SystemClock.uptimeMillis()+700
                                observeAsync()
                            }
                        }else{
                            StageExchangeTelemetry.measure("exchange_move"){
                                StageExchangeTelemetry.measure("probe_display_binder"){
                                    ActivityTaskManagerCompat.moveTaskToDisplay(id,destination).getOrThrow()
                                }
                                StageExchangeTelemetry.measure("probe_display_observation"){
                                    deadline=SystemClock.uptimeMillis()+700
                                    while(true){
                                        queries++
                                        val batch=repository.readExchangeTasks()
                                        val state=repository.publishExchangeTasks(batch).firstOrNull{it.taskId==id}
                                        verify(id,state)
                                        if(state?.displayId==destination)break
                                        check(SystemClock.uptimeMillis()<deadline){"Sync display observation timed out"}
                                        SystemClock.sleep(30)
                                    }
                                }
                            }
                            outcome=Result.success(Unit)
                        }
                    }
                })
                check(finished.await(8,TimeUnit.SECONDS)){"Matched move did not complete"}
                checkNotNull(outcome).getOrThrow()
                val elapsed=ms(endedNs-startTime)
                val busy=ms(ownerNs)
                when(mode){
                    "sync"->{syncElapsed.add(elapsed);syncOwner.add(busy)}
                    "async"->{asyncElapsed.add(elapsed);asyncOwner.add(busy);ordinaryCallback.add(ms(callbackNs))}
                    "async_barrier"->{barrierElapsed.add(elapsed);barrierOwner.add(busy);barrierCallback.add(ms(callbackNs))}
                    "async_batch"->{batchElapsed.add(elapsed);batchOwner.add(busy);batchCallback.add(ms(callbackNs))}
                    else->error("Unknown probe mode")
                }
                lines.add("matched_move mode=$mode task=$id display=$destination elapsed_ms=$elapsed "+
                    "owner_busy_ms=$busy worker_ms=${ms(workerNs)} dispatch_ms=${ms(dispatchNs)} "+
                    "callback_wait_ms=${ms(callbackNs)} initial_owner_wait_ms=${ms(firstOwnerNs)} queries=$queries callbacks=$callbacks")
            }
            for(round in 0..1)for(slot in 0..3){
                // Balanced alternating order over the same four live tasks.
                val orders=listOf(
                    listOf("sync","async","async_batch","async_barrier"),
                    listOf("async","async_barrier","sync","async_batch"),
                    listOf("async_barrier","async_batch","async","sync"),
                    listOf("async_batch","sync","async_barrier","async"),
                    listOf("async_barrier","async_batch","async","sync").reversed(),
                    listOf("async_batch","sync","async_barrier","async").reversed(),
                    listOf("sync","async","async_batch","async_barrier").reversed(),
                    listOf("async","async_barrier","sync","async_batch").reversed())
                val order=orders[(round*4+slot)%orders.size]
                for(mode in order){
                    i.runOnMainSync {
                        gaps.clear();lastFrame=0L; sampling=true
                        Choreographer.getInstance().postFrameCallback(callback)
                    }
                    Thread.sleep(80)
                    try{
                        matchedMove(ids[slot],0,mode)
                        val before=frames[slot].get()
                        matchedMove(ids[slot],displays[slot].display.displayId,mode)
                        awaitFrame(slot,before)
                        lines.add("matched_round=$round slot=$slot mode=$mode task_identity_retained=true fresh_buffers=${frames[slot].get()-before}")
                        Thread.sleep(80)
                    }finally{
                        i.runOnMainSync {
                            sampling=false; Choreographer.getInstance().removeFrameCallback(callback)
                            when(mode){
                                "sync"->syncGaps.addAll(gaps)
                                "async"->laneGaps.addAll(gaps)
                                "async_barrier"->barrierGaps.addAll(gaps)
                                "async_batch"->batchGaps.addAll(gaps)
                                else->error("Unknown probe mode")
                            }
                        }
                    }
                }
            }
            fun median(values:List<Double>):Double {
                val sorted=values.sorted()
                check(sorted.size==16)
                return (sorted[7]+sorted[8])/2.0
            }
            val baselineGap=syncGaps.maxOrNull()
            val baselineSamples=syncGaps.size
            fun frameMetrics(values:List<Long>):String{
                if(values.isEmpty())return "frame_gap_samples=0"
                val sorted=values.sorted()
                val p95=sorted[((sorted.size-1)*.95).toInt()]
                return "max_frame_gap_ms=${sorted.last()} frame_gap_p95_ms=$p95 samples=${sorted.size} over33ms=${sorted.count{it>33}}"
            }
            lines.add("matched_summary mode=sync n=16 elapsed_median_ms=${median(syncElapsed)} owner_busy_median_ms=${median(syncOwner)} "+frameMetrics(syncGaps))
            lines.add("matched_summary mode=async n=16 elapsed_median_ms=${median(asyncElapsed)} owner_busy_median_ms=${median(asyncOwner)} callback_wait_median_ms=${median(ordinaryCallback)} "+frameMetrics(laneGaps))
            lines.add("matched_summary mode=async_barrier n=16 elapsed_median_ms=${median(barrierElapsed)} owner_busy_median_ms=${median(barrierOwner)} callback_wait_median_ms=${median(barrierCallback)} "+frameMetrics(barrierGaps))
            lines.add("matched_summary mode=async_batch n=16 elapsed_median_ms=${median(batchElapsed)} owner_busy_median_ms=${median(batchOwner)} callback_wait_median_ms=${median(batchCallback)} "+frameMetrics(batchGaps))
            lines.add("Scope: async_barrier and async_batch are test-only candidates; batch merges successful move and first read, never worker publication; no production scheduling modification and no assumed improvement.")
            // Guard test is outside measured intervals.
            val guardFinished=CountDownLatch(1)
            var rejected=false
            i.runOnMainSync {
                check(lane.submit({
                    val batch=repository.readExchangeTasks()
                    rejected=runCatching{repository.publishExchangeTasks(batch)}.isFailure
                }){ack->
                    ack.getOrThrow()
                    guardFinished.countDown()
                })
            }
            check(guardFinished.await(8,TimeUnit.SECONDS) && rejected){"Worker must never publish task/Surface observations"}
            lines.add("PASS matched alternating real-task moves: same production query and verification; owner-only publication guard")
            val report=StageExchangeTelemetry.describe()
            check(report.contains(io.github.x647368maker.atomicworkbench.systemui.StageRuntimeIdentity.BUILD) && report.contains("probe_display_binder") && report.contains("main=true"))
            File(out,"exchange-profile-probe.txt").writeText(report)
            lines.add("main_frame_gap_max_ms=$baselineGap samples=$baselineSamples")
            lines.add("Scope: real framework moves only; production crop, input, overlay and vendor task engine are not emulated")
            File(out,"real-task-migration.txt").writeText(lines.joinToString("\n"))
            return listOf("PASS four real AOSP tasks retained identities and new buffers across 64 matched display moves; four modes, no phone performance claim",
                "PASS matched production Binder lane and task repository; owner-only publication guard; separate elapsed and owner-busy timings")
        } finally {
            i.runOnMainSync { sampling=false;Choreographer.getInstance().removeFrameCallback(callback) }
            i.runOnMainSync { taskIds.forEach{TaskSurfaceCompat.forgetTaskSurface(it)} }
            taskIds.forEach { id -> runCatching { ActivityTaskManagerCompat.removeTask(id).getOrThrow() } }
            displays.forEach { it.release() };readers.forEach { it.close() }
            consumer.quitSafely()
            runCatching { am.moveTaskToFront(original.taskId,0) }
            i.uiAutomation.dropShellPermissionIdentity()
            // Always retain partial evidence if an actual move or fresh-buffer assertion fails.
            File(out,"real-task-migration.txt").writeText(lines.joinToString("\n"))
        }
    }
}
