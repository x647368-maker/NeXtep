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
        // A watchdog may revoke future work, but cannot acknowledge an IPC
        // or release a fixture display while a submitted operation is outstanding.
        var outstanding:Pair<AtomicReference<String>,CountDownLatch>?=null
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
            // Exact original v83 state machine versus the production candidate.
            // A single remote task performs a round trip; not a two-app workspace swap.
            // Both variants use the same real Binder lane, owner Looper, query,
            // task identity, destination checks and observation timeout.
            val owner=Handler(Looper.getMainLooper())
            lateinit var lane:StageExchangeBinderLane
            i.runOnMainSync{lane=StageExchangeBinderLane{action->owner.post(action)}}
            fun now()=SystemClock.elapsedRealtimeNanos()
            fun ms(ns:Long)=ns/1_000_000.0
            data class Measurement(val elapsed:Double,val gap:Double,val queries:Int)
            val measurements=mutableMapOf<String,MutableList<Measurement>>()
            fun matchedSession(round:Int,candidate:Boolean){
                check(displayOf()==side && realPid()==firstPid)
                val mode=if(candidate)"candidate" else "v83_reference"
                val ticket=AtomicReference("READY")
                val finish=CountDownLatch(1)
                check(outstanding==null)
                outstanding=ticket to finish
                var started=0L
                var ended=0L
                var promotionReply=0L
                var demotionStart=0L
                var queries=0
                var completed=false
                var success=false
                check(owner.post{
                    started=now()
                    val deadline=SystemClock.uptimeMillis()+4000L
                    var forward:()->Boolean={false}
                    fun active()=ticket.get()=="READY" && forward()
                    fun migrate(target:Int,done:(Throwable?)->Unit){
                        fun fail(e:Throwable){done(e)}
                        fun <T> submit(work:()->T,accept:(T)->Unit){
                            var result:Result<T>?=null
                            val accepted=lane.submit({
                                result=runCatching(work)
                                checkNotNull(result).getOrThrow()
                                Unit
                            }){ack->
                                val error=runCatching{
                                    ack.getOrThrow()
                                    check(active()){"Owner cancelled or deadline elapsed"}
                                    accept(checkNotNull(result).getOrThrow())
                                }.exceptionOrNull()
                                if(error!=null)fail(error)
                            }
                            if(!accepted)fail(IllegalStateException("Binder lane busy"))
                        }
                        var observeDeadline=0L
                        fun observe(){
                            if(!active() || SystemClock.uptimeMillis()>=observeDeadline){
                                fail(IllegalStateException("Observation expired"));return
                            }
                            queries++
                            submit({repository.readExchangeTasks()}){batch->
                                val raw=batch.snapshots.firstOrNull{it.taskId==id}
                                if(raw!=null)check(raw.userId==before.userId && raw.token==before.token)
                                check(SystemClock.uptimeMillis()<observeDeadline)
                                val state=repository.publishExchangeTasks(batch).firstOrNull{it.taskId==id}
                                if(state?.displayId==target)done(null)
                                else check(owner.postDelayed({observe()},30L))
                            }
                        }
                        if(!active()){fail(IllegalStateException("Session no longer active"));return}
                        submit({ActivityTaskManagerCompat.moveTaskToDisplay(id,target).getOrThrow()}){
                            observeDeadline=SystemClock.uptimeMillis()+700L
                            observe()
                        }
                    }
                    // Failure deliberately does NOT fabricate restored state.
                    // This is an isolated emulator test; abort and let emulator
                    // teardown own resources if actual completion is unverified.
                    val driver=object:io.github.x647368maker.atomicworkbench.display.ExchangeSession.Driver,
                        io.github.x647368maker.atomicworkbench.display.V83ReferenceExchangeSession.Driver{
                        override val nonBlocking=true
                        override fun promote()=error("unexpected synchronous path")
                        override fun demoteAndVerify()=error("unexpected synchronous path")
                        override fun restoreAndVerify()=error("Fixture failure; no fabricated rollback")
                        override fun promoteAsync(done:(Throwable?)->Unit){
                            migrate(0){err->promotionReply=now();done(err)}
                        }
                        override fun demoteAndVerifyAsync(done:(Throwable?)->Unit){
                            demotionStart=now()
                            migrate(side,done)
                        }
                    }
                    val post: (()->Unit)->Unit={action->check(owner.post(action))}
                    val complete:(Boolean)->Unit={ok->
                        success=ok;completed=true;ended=now();finish.countDown()
                    }
                    if(candidate){
                        val s=io.github.x647368maker.atomicworkbench.display.ExchangeSession(
                            round,deadline,
                            io.github.x647368maker.atomicworkbench.display.ExchangeSession.Origin(original.taskId,null,null,0,"host"),
                            io.github.x647368maker.atomicworkbench.display.ExchangeSession.Origin(id,before.userId,before.token,side,"fixture"),
                            SystemClock::uptimeMillis,post,driver,complete)
                        forward={s.acceptsForwardWork}
                        s.start()
                    }else{
                        val s=io.github.x647368maker.atomicworkbench.display.V83ReferenceExchangeSession(
                            round,deadline,
                            io.github.x647368maker.atomicworkbench.display.V83ReferenceExchangeSession.Origin(original.taskId,null,null,0,"host"),
                            io.github.x647368maker.atomicworkbench.display.V83ReferenceExchangeSession.Origin(id,before.userId,before.token,side,"fixture"),
                            SystemClock::uptimeMillis,post,driver,complete)
                        forward={s.acceptsForwardWork}
                        s.start()
                    }
                })
                if(!finish.await(8,TimeUnit.SECONDS)){
                    ticket.set("REVOKED")
                    record("session_failure round=$round mode=$mode reason=no_verified_terminal_callback")
                    error("Real session did not complete; no next mutation or cleanup permitted")
                }
                ticket.set("REVOKED")
                outstanding=null
                check(completed && success && promotionReply>0 && demotionStart>=promotionReply)
                val m=Measurement(ms(ended-started),ms(demotionStart-promotionReply),queries)
                // Untimed independent identity checks for both variants.
                check(displayOf()==side && realPid()==firstPid)
                val state=snapshot()
                check(state.taskId==id && state.userId==before.userId && state.token==before.token && state.displayId==side)
                if(round>=0){
                    measurements.getOrPut(mode){mutableListOf()}.add(m)
                    record("session_pair round=$round mode=$mode elapsed_ms=${m.elapsed} promotion_to_demotion_ms=${m.gap} queries=${m.queries}")
                }
                Thread.sleep(100L)
            }
            matchedSession(-1,false);matchedSession(-1,true)
            repeat(30){round->
                for(candidate in if(round%2==0)listOf(false,true) else listOf(true,false)){
                    matchedSession(round,candidate)
                }
            }
            fun stats(values:List<Double>):String{
                check(values.size==30)
                val s=values.sorted()
                return "p50=${(s[14]+s[15])/2} p95=${s[28]} max=${s.last()}"
            }
            for(mode in listOf("v83_reference","candidate")){
                val v=checkNotNull(measurements[mode])
                record("session_summary mode=$mode n=${v.size} elapsed_ms ${stats(v.map{it.elapsed})} gap_ms ${stats(v.map{it.gap})} total_queries=${v.sumOf{it.queries}}")
            }
            record("SESSION_AB_SCOPE: 30 alternating pairs; real AOSP single remote task round trip, actual v83 and candidate state machines; no full two-task swap, per-attempt pixel presentation, touch latency or OEM FPS evidence")
            val result="PASS remote test-APK process retained task identity and completed 30 matched state-machine round-trip pairs; pixel smoke separate, no OEM FPS or third-party content claim"
            record(result)
            return result
        } finally {
            val pending=outstanding
            pending?.first?.set("REVOKED")
            // Give the real terminal owner callback a bounded drain interval.
            // A hard hang remains a failed instrumentation run; deliberately
            // leave the isolated test resources to emulator/process teardown.
            // Never issue a competing task removal or display release on timeout.
            val safeToClean=pending==null || runCatching{
                pending.second.await(4,TimeUnit.SECONDS)
            }.getOrDefault(false)
            if(!safeToClean){
                runCatching{record("FAIL cleanup_deferred: actual owner completion still outstanding; isolated process/emulator teardown required")}
            }else{
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
}
