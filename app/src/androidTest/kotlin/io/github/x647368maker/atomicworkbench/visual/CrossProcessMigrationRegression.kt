package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import io.github.x647368maker.atomicworkbench.display.StageExchangeBinderLane
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import io.github.x647368maker.atomicworkbench.framework.ActivityTaskManagerCompat
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.task.TaskRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Independent, shell-launched remote test activity, not OEM/SystemUI performance evidence. */
internal class CrossProcessMigrationRegression(private val i:Instrumentation) {
    fun run(original:Activity,out:File):String {
        val testPackage=i.context.packageName
        val className=RemoteMigrationFixtureActivity::class.java.name
        val targetPackage=original.packageName
        check(testPackage!=targetPackage){"Fixture must reside in test APK"}
        val am=original.getSystemService(ActivityManager::class.java)
        val dm=original.getSystemService(DisplayManager::class.java)
        val repository=TaskRepository(original)
        val log=mutableListOf<String>()
        val report=File(out,"cross-process-migration.txt")
        fun record(s:String){log.add(s);report.writeText(log.joinToString("\n")+"\n")}
        fun shell(cmd:String):String=ParcelFileDescriptor.AutoCloseInputStream(
            i.uiAutomation.executeShellCommand(cmd)).bufferedReader().use{it.readText()}
        fun tasks()=am.getRunningTasks(100).filter{
            it.topActivity?.let{c->c.packageName==testPackage && c.className==className}==true ||
            it.baseActivity?.let{c->c.packageName==testPackage && c.className==className}==true
        }
        fun waitFor(label:String,condition:()->Boolean) {
            val end=SystemClock.uptimeMillis()+7000L
            while(SystemClock.uptimeMillis()<end){
                if(condition())return
                Thread.sleep(30L)
            }
            check(condition()){"Timed out: $label"}
        }
        fun similar(r:Int,g:Int,b:Int,color:Int)=
            kotlin.math.abs(r-Color.red(color))<=12 &&
            kotlin.math.abs(g-Color.green(color))<=12 &&
            kotlin.math.abs(b-Color.blue(color))<=12
        fun colors(bitmap:Bitmap,color:Int)=listOf(4,5,6).all{x->listOf(4,5,6).all{y->
            val p=bitmap.getPixel(bitmap.width*x/10,bitmap.height*y/10)
            similar(Color.red(p),Color.green(p),Color.blue(p),color)
        }}
        val markers=AtomicInteger(0)
        val consumer=HandlerThread("AWRemoteFixtureBuffers").apply{start()}
        var reader:ImageReader?=null
        var virtual:android.hardware.display.VirtualDisplay?=null
        var adopted=false
        var fixtureId:Int?=null
        var cleanFixtureTasks=false
        try {
            i.uiAutomation.adoptShellPermissionIdentity()
            adopted=true
            check(tasks().isEmpty()){"An earlier remote fixture task is still present"}
            cleanFixtureTasks=true
            val source=ImageReader.newInstance(720,1120,PixelFormat.RGBA_8888,3)
            reader=source
            source.setOnImageAvailableListener({r->
                r.acquireLatestImage()?.use{image->
                    val p=image.planes.firstOrNull()
                    if(p!=null && p.pixelStride>=4){
                        val data=p.buffer
                        val base=data.position()
                        fun matches(color:Int)=listOf(4,5,6).all{x->listOf(4,5,6).all{y->
                            val off=base+(image.height*y/10)*p.rowStride+
                                (image.width*x/10)*p.pixelStride
                            off>=0 && off+2<data.limit() &&
                                similar(data.get(off).toInt() and 255,
                                    data.get(off+1).toInt() and 255,
                                    data.get(off+2).toInt() and 255,color)
                        }}
                        if(matches(RemoteMigrationFixtureActivity.initialMarker))
                            markers.getAndUpdate{it or 1}
                        if(matches(RemoteMigrationFixtureActivity.returnMarker))
                            markers.getAndUpdate{it or 2}
                    }
                }
            },Handler(consumer.looper))
            val vd=checkNotNull(dm.createVirtualDisplay("AW-RemoteMigration",720,1120,240,
                source.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
            virtual=vd
            val side=vd.display.displayId
            val launch=shell("am start -W --display $side -f 0x18000000 -n $testPackage/$className")
            record("shell_launch="+launch.trim().replace('\n',' '))
            check(!launch.contains("Error") && !launch.contains("Exception")){"Remote fixture launch failed"}
            waitFor("remote task exists"){tasks().size==1}
            val id=tasks().single().taskId
            fixtureId=id
            val remoteProcess="$testPackage:aw_migration_fixture"
            fun realPid():Int=shell("pidof $remoteProcess").trim()
                .split(Regex("\\s+")).mapNotNull{it.toIntOrNull()}.singleOrNull() ?: -1
            waitFor("different process"){realPid()>0 && realPid()!=Process.myPid()}
            val firstPid=realPid()
            check(firstPid>0 && firstPid!=Process.myPid())
            record("owner_pid=${Process.myPid()} fixture_pid=$firstPid task=$id")
            fun displayOf():Int?=tasks().singleOrNull{it.taskId==id}?.let{
                it.javaClass.getField("displayId").getInt(it)
            }
            waitFor("initial side destination"){displayOf()==side}
            waitFor("initial side marker"){markers.get() and 1 != 0}
            check(markers.get() and 2 == 0){"Return marker appeared before migration"}
            fun snapshot():TaskRepository.TaskSnapshot {
                var result:TaskRepository.TaskSnapshot?=null
                val batch=repository.readExchangeTasks()
                i.runOnMainSync {
                    result=repository.publishExchangeTasks(batch).firstOrNull{it.taskId==id}
                }
                return checkNotNull(result)
            }
            val before=snapshot()
            check(before.displayId==side && before.token!=null)
            fun move(target:Int){
                i.runOnMainSync {
                    ActivityTaskManagerCompat.moveTaskToDisplay(id,target).getOrThrow()
                }
                waitFor("task destination $target"){displayOf()==target}
                val after=snapshot()
                check(after.taskId==before.taskId && after.userId==before.userId &&
                    after.token==before.token && after.displayId==target){"Task identity changed"}
                check(realPid()==firstPid){"Remote fixture process was replaced"}
                record("confirmed task=$id target=$target pid=$firstPid")
            }
            move(0)
            waitFor("main display marker") {
                val shot=i.uiAutomation.takeScreenshot() ?: return@waitFor false
                val readable=if(shot.config==Bitmap.Config.HARDWARE)
                    shot.copy(Bitmap.Config.ARGB_8888,false) else shot
                try { readable!=null && colors(readable,RemoteMigrationFixtureActivity.mainMarker) }
                finally { if(readable!==shot)readable?.recycle();shot.recycle() }
            }
            record("main_marker_verified=true")
            move(side)
            waitFor("new returned side marker"){markers.get() and 2 != 0}
            check(realPid()==firstPid)
            record("return_marker_verified=true")
            // After the original independent pixel/identity smoke, compare the
            // same remote process and same task, two callbacks vs guarded first read.
            // No screenshot, shell PID query or output waiting inside a timed interval.
            val owner=Handler(Looper.getMainLooper())
            lateinit var lane:StageExchangeBinderLane
            i.runOnMainSync{lane=StageExchangeBinderLane{action->owner.post(action)}}
            fun now()=SystemClock.elapsedRealtimeNanos()
            fun ms(ns:Long)=ns/1_000_000.0
            data class Measurement(val elapsed:Double,val callback:Double,val ownerBusy:Double)
            val measurements=mutableMapOf<Pair<String,Int>,MutableList<Measurement>>()
            fun matchedMove(round:Int,target:Int,batchMode:Boolean){
                val mode=if(batchMode)"guarded_batch" else "ordinary"
                val started=now()
                val sessionDeadline=SystemClock.uptimeMillis()+4000L
                val finish=CountDownLatch(1)
                val ticket=AtomicReference("READY")
                var terminal:Result<Unit>?=null
                var ended=0L
                var moveReturnedAt=0L
                var ownerBusy=0L
                var callbackWait=0L
                var callbacks=0
                var queries=0
                fun active()=ticket.get()!="REVOKED" && SystemClock.uptimeMillis()<sessionDeadline
                fun observationActive()=active() && SystemClock.uptimeMillis()<moveReturnedAt+700L
                fun bridge(action:()->Unit){
                    val t=now()
                    try{
                        check(Looper.myLooper()==Looper.getMainLooper())
                        action()
                    }catch(error:Throwable){terminal=Result.failure(error)}
                    finally{
                        ownerBusy+=now()-t
                        if(terminal!=null){ended=now();finish.countDown()}
                    }
                }
                fun <T> submit(work:()->T,accept:(T)->Unit){
                    var result:Result<T>?=null
                    var returned=0L
                    check(lane.submit({
                        try{result=runCatching(work);checkNotNull(result).getOrThrow();Unit}
                        finally{returned=now()}
                    }){ack->
                        val entered=now()
                        bridge{
                            callbacks++;callbackWait+=entered-returned
                            ack.getOrThrow()
                            check(active()){"Session invalid"}
                            accept(checkNotNull(result).getOrThrow())
                        }
                    }){"Serialized lane unavailable"}
                }
                lateinit var observe:()->Unit
                fun accept(b:TaskRepository.ExchangeTaskBatch){
                    check(observationActive()){"Matched post-move observation expired"}
                    val raw=b.snapshots.firstOrNull{it.taskId==id}
                    if(raw!=null)check(raw.taskId==before.taskId && raw.userId==before.userId &&
                        raw.token==before.token){"Remote task identity changed"}
                    // No speculative result may update Surface state before owner checks.
                    val state=repository.publishExchangeTasks(b).firstOrNull{it.taskId==id}
                    if(state?.displayId==target)terminal=Result.success(Unit)
                    else check(owner.postDelayed({bridge{observe()}},30L))
                }
                observe={
                    check(observationActive())
                    queries++
                    submit({repository.readExchangeTasks()},::accept)
                }
                check(owner.post{
                    bridge{
                        check(active())
                        if(batchMode){
                            queries++
                            submit({
                                ActivityTaskManagerCompat.moveTaskToDisplay(id,target).getOrThrow()
                                moveReturnedAt=SystemClock.uptimeMillis()
                                check(observationActive() && ticket.compareAndSet("READY","READING") &&
                                    observationActive()){"Prefetch no longer authorized"}
                                repository.readExchangeTasks()
                            }){value->
                                check(ticket.get()=="READING")
                                accept(value)
                            }
                        }else{
                            submit({
                                ActivityTaskManagerCompat.moveTaskToDisplay(id,target).getOrThrow()
                                moveReturnedAt=SystemClock.uptimeMillis()
                            }){observe()}
                        }
                    }
                })
                if(!finish.await(8,TimeUnit.SECONDS)){
                    ticket.set("REVOKED")
                    error("Remote matched move did not return; no next mutation permitted")
                }
                checkNotNull(terminal).getOrThrow()
                ticket.set("REVOKED")
                val m=Measurement(ms(ended-started),ms(callbackWait),ms(ownerBusy))
                val direction=if(target==android.view.Display.DEFAULT_DISPLAY)"to_main" else "to_side"
                measurements.getOrPut(mode to target){mutableListOf()}.add(m)
                record("remote_matched round=$round mode=$mode direction=$direction "+
                    "elapsed_ms=${m.elapsed} callback_wait_ms=${m.callback} owner_busy_ms=${m.ownerBusy} "+
                    "queries=$queries callbacks=$callbacks")
                // Strict independent identity and process evidence, outside timing.
                check(displayOf()==target && realPid()==firstPid)
                Thread.sleep(150L)
            }
            repeat(8){round->
                for(batchMode in if(round%2==0)listOf(false,true) else listOf(true,false)){
                    matchedMove(round,android.view.Display.DEFAULT_DISPLAY,batchMode)
                    matchedMove(round,side,batchMode)
                }
            }
            fun median(xs:List<Double>):Double{
                check(xs.size==8)
                val sorted=xs.sorted()
                return (sorted[3]+sorted[4])/2
            }
            for(mode in listOf("ordinary","guarded_batch"))for(target in listOf(0,side)){
                val v=checkNotNull(measurements[mode to target])
                val direction=if(target==0)"to_main" else "to_side"
                record("remote_summary mode=$mode direction=$direction n=${v.size} "+
                    "elapsed_median_ms=${median(v.map{it.elapsed})} elapsed_max_ms=${v.maxOf{it.elapsed}} "+
                    "callback_median_ms=${median(v.map{it.callback})} owner_busy_median_ms=${median(v.map{it.ownerBusy})}")
            }
            record("remote matched scope: 32 moves, one independent fixture task, 700ms post-IPC budget, owner-only publication; valid path only, no per-move fresh-pixel timing or full owner geometry/cancellation proof")
            val result="PASS remote test-APK process retained task identity and actual main/side markers; smoke only, no OEM FPS or third-party content claim"
            record(result)
            return result
        } finally {
            if(adopted){
                // Only the dedicated fixture component is eligible for removal.
                if(cleanFixtureTasks){
                    val ids=runCatching{tasks().map{it.taskId}}.getOrDefault(emptyList())
                    (ids+listOfNotNull(fixtureId)).distinct().forEach{id->
                        runCatching{ActivityTaskManagerCompat.removeTask(id).getOrThrow()}
                        i.runOnMainSync{TaskSurfaceCompat.forgetTaskSurface(id)}
                    }
                }
                runCatching{am.moveTaskToFront(original.taskId,0)}
            }
            reader?.setOnImageAvailableListener(null,null)
            virtual?.release()
            consumer.quitSafely()
            consumer.join(2000L)
            reader?.close()
            if(adopted)i.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
