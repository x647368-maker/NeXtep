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
