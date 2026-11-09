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
        fun taskDisplay(id: Int) = am.getRunningTasks(100).firstOrNull { it.taskId==id }?.displayId
        fun awaitFrame(slot: Int, previous: Int) {
            val end=SystemClock.uptimeMillis()+5000
            while(frames[slot].get()<=previous && SystemClock.uptimeMillis()<end) Thread.sleep(20)
            check(frames[slot].get()>previous) { "Real task did not produce a fresh display buffer slot=$slot" }
        }
        fun move(id: Int, destination: Int) {
            val start=SystemClock.elapsedRealtimeNanos()
            i.runOnMainSync {
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
