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
import android.os.SystemClock
import android.view.Choreographer
import io.github.x647368maker.atomicworkbench.framework.ActivityTaskManagerCompat
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
        fun move(id: Int, destination: Int) {
            val start=SystemClock.elapsedRealtimeNanos()
            i.runOnMainSync {
                StageExchangeTelemetry.measure("exchange_move") {
                StageExchangeTelemetry.measure("probe_display_binder") {
                    ActivityTaskManagerCompat.moveTaskToDisplay(id,destination).getOrThrow()
                }
                // Preserve production readiness deadlines and polling for this baseline only.
                StageExchangeTelemetry.measure("probe_display_observation") {
                    val end=SystemClock.uptimeMillis()+700
                    while(taskDisplay(id)!=destination && SystemClock.uptimeMillis()<end) SystemClock.sleep(30)
                    check(taskDisplay(id)==destination) { "Display migration not observed task=$id destination=$destination" }
                }
                }
            }
            lines.add("move task=$id display=$destination main_thread_ms=${(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0}")
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
            fun values(x:io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat.WindowState?) =
                x?.let{listOf(it.displayId,it.bounds,it.windowingMode,it.resizeMode,it.densityDpi,it.token,it.vendorWindowed)}
            for(info in raw) {
                val old=io.github.x647368maker.atomicworkbench.framework.BaselineTaskInfoCompat.readWindowState(info)
                val new=io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat.readWindowState(info)
                check(old!=null && new!=null && listOf(old.displayId,old.bounds,old.windowingMode,old.resizeMode,old.densityDpi,old.token,old.vendorWindowed)==values(new))
            }
            val baseline=mutableListOf<Long>();val cached=mutableListOf<Long>()
            // Alternate order, same immutable snapshot batch, same UI thread and four displays.
            i.runOnMainSync {
                repeat(8){round->
                    fun sample(old:Boolean):Long {
                        val start=SystemClock.elapsedRealtimeNanos()
                        repeat(128){n->val info=raw[n%raw.size]
                            if(old)checkNotNull(io.github.x647368maker.atomicworkbench.framework.BaselineTaskInfoCompat.readWindowState(info))
                            else checkNotNull(io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat.readWindowState(info))
                        }
                        return SystemClock.elapsedRealtimeNanos()-start
                    }
                    if(round%2==0){baseline.add(sample(true));cached.add(sample(false))}
                    else{cached.add(sample(false));baseline.add(sample(true))}
                }
            }
            val comparison="metadata-only comparison: 128 decodes/batch, 8 alternating UI-thread batches, same four real task snapshots; baseline_median_ms=${baseline.sorted()[4]/1_000_000.0} cached_median_ms=${cached.sorted()[4]/1_000_000.0}; state_fields_equal=true; not full exchange or ColorOS FPS"
            lines.add(comparison);File(out,"task-decoder-comparison.txt").writeText(comparison)
            i.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
            Thread.sleep(80)
            // The same four tasks and VD dimensions are used for every round trip.
            for(round in 0..1) for(slot in 0..3) {
                move(ids[slot],0)
                check(taskDisplay(ids[slot])==0)
                val before=frames[slot].get()
                move(ids[slot],displays[slot].display.displayId)
                awaitFrame(slot,before)
                lines.add("round=$round slot=$slot task_id_retained=true fresh_buffers=${frames[slot].get()-before}")
            }
            i.runOnMainSync { sampling=false;Choreographer.getInstance().removeFrameCallback(callback) }
            val report=StageExchangeTelemetry.describe()
            check(report.contains(io.github.x647368maker.atomicworkbench.systemui.StageRuntimeIdentity.BUILD) && report.contains("probe_display_binder") && report.contains("main=true"))
            File(out,"exchange-profile-probe.txt").writeText(report)
            lines.add("main_frame_gap_max_ms=${gaps.maxOrNull()} samples=${gaps.size}")
            lines.add("Scope: real framework moves only; production crop, input, overlay and vendor task engine are not emulated")
            File(out,"real-task-migration.txt").writeText(lines.joinToString("\n"))
            return listOf("PASS four real AOSP tasks retained IDs and resumed fresh buffers across 16 display moves; timings recorded, no phone performance claim")
        } finally {
            i.runOnMainSync { sampling=false;Choreographer.getInstance().removeFrameCallback(callback) }
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
