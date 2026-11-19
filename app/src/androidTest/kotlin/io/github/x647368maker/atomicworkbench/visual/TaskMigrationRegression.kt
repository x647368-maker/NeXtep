package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import io.github.x647368maker.atomicworkbench.framework.ActivityTaskManagerCompat
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real AOSP task reparenting, not coloured Surface producers or a ColorOS claim.
 * No production task-engine changes are evaluated as a performance fix here.
 */
internal class TaskMigrationRegression(private val i: Instrumentation) {
    private data class Marker(val epoch:Int,val display:Int,val base:Int,val stripe:Int)
    private data class Draw(val epoch:Int,val display:Int,val width:Int,val height:Int,val density:Int)
    private data class Pixels(val sequence:Int,val timestamp:Long,val width:Int,val height:Int,val colors:List<Int>)
    private fun matches(actual:Int,expected:Int)=
        abs(Color.red(actual)-Color.red(expected))<24 &&
        abs(Color.green(actual)-Color.green(expected))<24 &&
        abs(Color.blue(actual)-Color.blue(expected))<24
    // Only sample well inside the content: status bars, inset rounding and
    // corner radii cannot accidentally satisfy a central producer marker.
    private fun grid(width:Int,height:Int,read:(Int,Int)->Int):List<Int> =
        listOf(.35f,.5f,.65f).flatMap { y ->
            listOf(.35f,.5f,.65f).map { x -> read((width*x).toInt(),(height*y).toInt()) }
        }
    private fun matchesMarker(colors:List<Int>,marker:Marker):Boolean =
        colors.size==9 && colors.withIndex().all { (index,color) ->
            matches(color,if(index/3==1)marker.stripe else marker.base)
        }

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
        val markerLines=mutableListOf("Controlled app producer; real AOSP reparent only; not a ColorOS or arbitrary-app continuity proof")
        val expectedMarker=AtomicReference<Marker?>(null)
        val actualDraw=AtomicReference<Draw?>(null)
        val slotPixels=AtomicReference<Pixels?>(null)
        val markerExtra="atomic_ci_owned_producer_marker"
        val markerTag="atomic_ci_producer_marker_view"
        val app=original.application
        val lifecycle=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity:Activity,state:Bundle?)=Unit
            override fun onActivityStarted(activity:Activity)=Unit
            override fun onActivityResumed(activity:Activity) {
                if(!activity.intent.getBooleanExtra(markerExtra,false))return
                if(activity.window.decorView.findViewWithTag<View>(markerTag)!=null)return
                // This view exists only in the instrumented, test-owned task.
                // Recreated Activity instances get the same fixture, but no
                // draw callback or buffer is fabricated by the test.
                activity.setContentView(object:View(activity) {
                    private val paint=Paint()
                    init {tag=markerTag}
                    override fun onDraw(canvas:Canvas) {
                        val spec=expectedMarker.get()
                        val id=activity.display?.displayId ?: -1
                        if(spec==null || id!=spec.display) {
                            canvas.drawColor(Color.DKGRAY)
                            return
                        }
                        canvas.drawColor(spec.base)
                        paint.color=spec.stripe
                        canvas.drawRect(0f,height*.40f,width.toFloat(),height*.60f,paint)
                        actualDraw.set(Draw(spec.epoch,id,width,height,resources.displayMetrics.densityDpi))
                    }
                })
            }
            override fun onActivityPaused(activity:Activity)=Unit
            override fun onActivityStopped(activity:Activity)=Unit
            override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
            override fun onActivityDestroyed(activity:Activity)=Unit
        }
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
        fun awaitSlotMarker(spec:Marker,previous:Int):Draw {
            val deadline=SystemClock.uptimeMillis()+5000
            while(SystemClock.uptimeMillis()<deadline) {
                val pixels=slotPixels.get()
                val draw=actualDraw.get()
                if(pixels!=null && pixels.sequence>previous && pixels.timestamp>0L &&
                    pixels.width==720 && pixels.height==1120 &&
                    draw!=null && draw.epoch==spec.epoch && draw.display==spec.display &&
                    draw.width>0 && draw.height>0 &&
                    matchesMarker(pixels.colors,spec)) {
                    markerLines.add("slot epoch=${spec.epoch} display=${draw.display} draw=${draw.width}x${draw.height} density=${draw.density} seq=${pixels.sequence} timestamp=${pixels.timestamp} pixels=9/9")
                    return draw
                }
                Thread.sleep(40)
            }
            error("No matching new virtual-display content marker epoch=${spec.epoch}; draw=${actualDraw.get()} pixels=${slotPixels.get()}")
        }
        fun awaitMainMarker(spec:Marker,id:Int):Draw {
            val deadline=SystemClock.uptimeMillis()+5000
            var last:Bitmap?=null
            try {
                while(SystemClock.uptimeMillis()<deadline) {
                    val shot=i.uiAutomation.takeScreenshot()
                    if(shot!=null) {
                        last?.recycle();last=shot
                        val colors=grid(shot.width,shot.height,shot::getPixel)
                        val draw=actualDraw.get()
                        if(draw!=null && draw.epoch==spec.epoch && draw.display==spec.display &&
                            draw.width>0 && draw.height>0 && taskDisplay(id)==0 &&
                            matchesMarker(colors,spec)) {
                            File(out,"real-task-marker-main.png").outputStream().use {
                                shot.compress(Bitmap.CompressFormat.PNG,100,it)
                            }
                            markerLines.add("main epoch=${spec.epoch} display=${draw.display} draw=${draw.width}x${draw.height} density=${draw.density} pixels=9/9")
                            return draw
                        }
                    }
                    Thread.sleep(80)
                }
                last?.let { frame -> File(out,"failed-real-task-marker-main.png").outputStream().use {
                    frame.compress(Bitmap.CompressFormat.PNG,100,it)
                } }
                error("No matching physical main-display content marker epoch=${spec.epoch}; draw=${actualDraw.get()}")
            } finally {last?.recycle()}
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
            i.runOnMainSync { app.registerActivityLifecycleCallbacks(lifecycle) }
            var originalMarkerDraw:Draw?=null
            for(slot in 0..3) {
                val reader=ImageReader.newInstance(720,1120,PixelFormat.RGBA_8888,3)
                readers.add(reader)
                reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { frame ->
                    val seq=frames[slot].incrementAndGet()
                    if(slot==0) {
                        val plane=frame.planes.first()
                        val data=plane.buffer
                        val colors=grid(frame.width,frame.height) { x,y ->
                            val offset=y*plane.rowStride+x*plane.pixelStride
                            if(plane.pixelStride<4 || offset<0 || offset+2>=data.limit()) Color.TRANSPARENT
                            else Color.rgb(data.get(offset).toInt() and 255,
                                data.get(offset+1).toInt() and 255,data.get(offset+2).toInt() and 255)
                        }
                        slotPixels.set(Pixels(seq,frame.timestamp,frame.width,frame.height,colors))
                    }
                } },Handler(consumer.looper))
                val vd=dm.createVirtualDisplay("AW-Migration-$slot",720,1120,240,reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
                checkNotNull(vd) { "Unable to create real task display" };displays.add(vd)
                val intent=Intent().setClassName(original.packageName,original.packageName+".config.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                if(slot==0) {
                    val spec=Marker(1,vd.display.displayId,Color.rgb(32,170,89),Color.rgb(241,208,45))
                    expectedMarker.set(spec)
                    intent.putExtra(markerExtra,true)
                }
                val a=i.startActivitySync(intent,ActivityOptions.makeBasic().setLaunchDisplayId(vd.display.displayId).toBundle())
                activities.add(a);taskIds.add(a.taskId)
                check(taskDisplay(a.taskId)==vd.display.displayId) { "Probe activity not launched on its virtual display" }
                awaitFrame(slot,0)
                if(slot==0)originalMarkerDraw=awaitSlotMarker(checkNotNull(expectedMarker.get()),0)
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
            i.runOnMainSync { Choreographer.getInstance().postFrameCallback(callback) }
            Thread.sleep(80)
            // The same four tasks and VD dimensions are used for every round trip.
            for(round in 0..1) for(slot in 0..3) {
                // Distinct producer colors for every generation. The spec
                // changes before moving; the View only draws it in the
                // corresponding display context. No forced invalidate
                // after a move, no fake frame/ownership acknowledgements.
                // Separate epochs by substantially more than the RGB
                // sampling tolerance; a prior epoch cannot pass as current.
                val markerMain=if(slot==0) {
                    if(round==0) Marker(2,0,Color.rgb(39,105,237),Color.rgb(45,227,244))
                    else Marker(4,0,Color.rgb(117,42,180),Color.rgb(168,244,50))
                } else null
                markerMain?.let {expectedMarker.set(it)}
                move(ids[slot],0)
                check(taskDisplay(ids[slot])==0)
                val promoted=markerMain?.let { spec ->
                    // This is an owned test fixture, not an assertion
                    // about production z-order or migration latency.
                    i.runOnMainSync {am.moveTaskToFront(ids[slot],0)}
                    awaitMainMarker(spec,ids[slot])
                }
                val before=frames[slot].get()
                val markerSlot=if(slot==0) {
                    val display=displays[slot].display.displayId
                    if(round==0) Marker(3,display,Color.rgb(221,98,38),Color.rgb(252,227,52))
                    else Marker(5,display,Color.rgb(200,32,110),Color.rgb(250,158,215))
                } else null
                markerSlot?.let {expectedMarker.set(it)}
                move(ids[slot],displays[slot].display.displayId)
                awaitFrame(slot,before)
                if(markerSlot!=null) {
                    val demoted=awaitSlotMarker(markerSlot,before)
                    val originalDraw=checkNotNull(originalMarkerDraw)
                    // ImageReader checks the retained physical canvas
                    // separately. The view may legitimately have different
                    // inset bounds after a framework configuration change.
                    check(demoted.display==originalDraw.display &&
                        demoted.density==originalDraw.density) {
                        "Controlled slot producer unexpectedly changed its retained display/density"
                    }
                    check(promoted!=null && (promoted.width!=demoted.width ||
                        promoted.height!=demoted.height || promoted.density!=demoted.density)) {
                        "Marker experiment did not cross different producer configuration domains"
                    }
                    markerLines.add("round=$round retained task=${ids[slot]} actual differing producer configuration verified")
                }
                lines.add("round=$round slot=$slot task_id_retained=true fresh_buffers=${frames[slot].get()-before}")
            }
            markerLines.add("PASS real owned-task marker generations on both physical and virtual outputs")
            File(out,"real-task-marker-generations.txt").writeText(markerLines.joinToString("\n"))
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
            i.runOnMainSync {app.unregisterActivityLifecycleCallbacks(lifecycle)}
            taskIds.forEach { id -> runCatching { ActivityTaskManagerCompat.removeTask(id).getOrThrow() } }
            displays.forEach { it.release() };readers.forEach { it.close() }
            consumer.quitSafely()
            runCatching { am.moveTaskToFront(original.taskId,0) }
            i.uiAutomation.dropShellPermissionIdentity()
            // Always retain partial evidence if an actual move or fresh-buffer assertion fails.
            File(out,"real-task-migration.txt").writeText(lines.joinToString("\n"))
            File(out,"real-task-marker-generations.txt").writeText(markerLines.joinToString("\n"))
        }
    }
}
