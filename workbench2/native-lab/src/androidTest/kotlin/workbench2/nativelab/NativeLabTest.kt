package workbench2.nativelab

import android.app.ActivityManager
import android.car.Car
import android.car.app.CarActivityManager
import android.car.app.CarTaskViewController
import android.car.app.CarTaskViewControllerCallback
import android.car.app.ControlledRemoteCarTaskView
import android.car.app.ControlledRemoteCarTaskViewCallback
import android.car.app.ControlledRemoteCarTaskViewConfig
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import workbench2.engine.AndroidFrameDriver
import workbench2.motion.Box
import workbench2.motion.Canvas
import workbench2.motion.Owner
import workbench2.motion.Pose
import workbench2.motion.Scene
import workbench2.nativebridge.NativePort
import workbench2.nativebridge.NativeSnapshot
import workbench2.nativelab.identity.TaskIdentity
import workbench2.unified.*
import org.junit.Test

/** Runs in instrumentation thread; all Android/engine calls on main.
 * No recurring worker, production permissions, fake receipts or hidden API exemption.
 * Unknown evidence fails the isolated test, not a fabricated settlement.
 */
class NativeLabTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val ui get()=inst.uiAutomation
    private fun <T> main(f:()->T):T {
        var result:Result<T>?=null
        inst.runOnMainSync { result=runCatching(f) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(label:String,condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+8000
        while(SystemClock.uptimeMillis()<deadline) {
            if(condition())return
            SystemClock.sleep(50)
        }
        error("Unverified: "+label)
    }
    private fun shell(command:String):String =
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use{it.readText()}
    private fun rect(view:View):Rect {
        val p=IntArray(2);view.getLocationOnScreen(p)
        return Rect(p[0],p[1],p[0]+view.width,p[1]+view.height)
    }
    private fun box(r:Rect)=Box(r.left.toDouble(),r.top.toDouble(),r.width().toDouble(),r.height().toDouble())
    private fun tap(r:Rect) {
        val t=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,r.exactCenterX(),r.exactCenterY(),0)
            e.source=InputDevice.SOURCE_TOUCHSCREEN
            try { check(ui.injectInputEvent(e,true)){"Test touch injection rejected"} } finally { e.recycle() }
        }
    }
    private fun mark(s:String){ Log.i("WB2NativeLab",s) }
    private data class Work(val effect:Effect,val done:(NativeSnapshot)->Unit)
    private inner class LabPort(val host:HostActivity,val car:Car,val controller:CarTaskViewController,
                                val firmware:String,val version:String,val instance:String):NativePort {
        val queue=ArrayBlockingQueue<Work>(1)
        private val user=TaskIdentity.userId(Process.myUserHandle())
        private val target=ComponentName(host,TargetActivity::class.java)
        private var view:ControlledRemoteCarTaskView?=null
        private var presentation:ControlledRemoteCarTaskView?=null
        private var gate:View?=null
        private var listener:View.OnLayoutChangeListener?=null
        @Volatile private var appeared:ActivityManager.RunningTaskInfo?=null
        @Volatile private var released=false
        @Volatile private var vanished=false
        @Volatile private var localStopped=false
        @Volatile private var failure:Throwable?=null
        private var token:Any?=null
        private var slotId:String?=null
        private var owner:Owner?=null
        private var request:LaunchRequest?=null
        private var observedChanges=0
        override fun dispatch(effect:Effect,poses:Map<Owner,Pose>?,complete:(NativeSnapshot)->Unit) {
            check(queue.offer(Work(effect,complete))){"Concurrent native effect"}
        }
        override fun stopLocalCallbacks() { localStopped=true }
        private fun snapshot(obs:Observation=Observation.UNKNOWN,blocked:Boolean?=null,
                             scene:Scene?=null,focus:Owner?=null,ready:Boolean=false,
                             proof:AttachProof?=null,quiet:Boolean=false)=
            NativeSnapshot(firmware,version,instance,scene,focus,blocked,obs,true,true,ready,quiet,proof)
        private fun region()=main { rect(host.slot) }
        private fun createGate()=main {
            if(gate==null) {
                gate=View(host).also { v ->
                    v.setOnTouchListener { _,event ->
                        if(event.actionMasked==MotionEvent.ACTION_UP)host.hostTouches++
                        true
                    }
                    host.slot.addView(v,FrameLayout.LayoutParams(-1,-1))
                }
            }
            gate!!.bringToFront()
            view?.setObscuredTouchRect(rect(host.slot))
        }
        private fun openGate()=main {
            gate?.let{host.slot.removeView(it)};gate=null
            view?.setObscuredTouchRect(Rect())
        }
        private fun blockedTouch() {
            val oldHost=host.hostTouches;val oldTarget=Fixture.touches
            inst.waitForIdleSync();tap(region())
            await("host receives blocked test tap"){host.hostTouches==oldHost+1}
            inst.waitForIdleSync()
            check(Fixture.touches==oldTarget){"Blocked input reached embedded task"}
        }
        private fun verifyPixels() {
            val r=region()
            await("independent rendered target pixel"){
                val image=ui.takeScreenshot() ?: return@await false
                try {
                    if(r.centerX() !in 0 until image.width || r.centerY() !in 0 until image.height) return@await false
                    val c=image.getPixel(r.centerX(),r.centerY())
                    kotlin.math.abs(Color.red(c)-Fixture.RED)<=6 &&
                    kotlin.math.abs(Color.green(c)-Fixture.GREEN)<=6 &&
                    kotlin.math.abs(Color.blue(c)-Fixture.BLUE)<=6
                } finally { image.recycle() }
            }
            mark("PIXEL_OK")
        }
        private fun taskRecord(id:Int):String? {
            val lines=shell("dumpsys activity activities").lineSequence().toList()
            val index=lines.indexOfFirst { Regex("^\\s*\\* Task\\{[^\\n]* #"+id+"(?: |\\})").containsMatchIn(it) }
            if(index<0)return null
            return lines.drop(index).take(4).joinToString("\n")
        }
        private fun verifyBounds(id:Int) {
            val rec=checkNotNull(taskRecord(id)){"No independent WM task record"}
            val r=region()
            check(rec.contains("U="+user+" ")){"WM task user mismatch"}
            check(rec.contains("mBounds=Rect("+r.left+", "+r.top+" - "+r.right+", "+r.bottom+")")){"WM/view bounds mismatch: "+rec}
        }
        private fun observe():Owner {
            val info=main{checkNotNull(view?.taskInfo)}
            check(info.taskId==Fixture.task && info.baseActivity==target && info.isVisible)
            val actual=checkNotNull(TaskIdentity.running(info))
            check(actual==token){"Native token changed"}
            verifyBounds(info.taskId)
            val old=checkNotNull(owner)
            check(old.canvas==Canvas(Fixture.width,Fixture.height,Fixture.dpi,Fixture.sizeEpoch)){"Unexpected content geometry change"}
            return old
        }
        private fun scene():Scene {
            val observed=observe()
            check(main{rect(checkNotNull(view))}==region())
            return Scene(listOf(observed),listOf(box(region())),0)
        }
        private fun acquireTask(req:LaunchRequest):AttachProof {
            check(req.existing==null && req.component==target.flattenToString() && req.user==user && request==null)
            request=req
            main {
                controller.createControlledRemoteCarTaskView(
                    ControlledRemoteCarTaskViewConfig.Builder()
                        .setActivityIntent(Intent(host,TargetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
                        .setShouldAutoRestartOnTaskRemoval(false).build(),host.mainExecutor,
                    object:ControlledRemoteCarTaskViewCallback {
                        override fun onTaskViewCreated(v:ControlledRemoteCarTaskView) {
                            if(view!=null){failure=IllegalStateException("Duplicate native view");return}
                            view=v;host.slot.addView(v,0,FrameLayout.LayoutParams(-1,-1))
                            gate?.bringToFront();v.setObscuredTouchRect(rect(host.slot))
                        }
                        override fun onTaskAppeared(info:ActivityManager.RunningTaskInfo) {
                            if(appeared!=null){failure=IllegalStateException("Duplicate task appearance");return}
                            appeared=info
                        }
                        override fun onTaskVanished(info:ActivityManager.RunningTaskInfo) { vanished=true }
                        override fun onTaskViewReleased(){ released=true }
                    })
            }
            await("native callback and owned draw"){failure?.let{throw it};appeared!=null && Fixture.drawn && Fixture.width>1 && Fixture.height>1}
            val info=checkNotNull(appeared)
            check(info.taskId==Fixture.task && info.taskId!=host.taskId && info.baseActivity==target)
            token=checkNotNull(TaskIdentity.running(info)){"No real WM token"}
            // Single session-scoped opaque handle allocated only after a genuine token.
            // Every subsequent observation compares the actual token object, not a request ID.
            slotId="observed-slot-"+UUID.randomUUID().toString()
            owner=Owner(info.taskId,user,1L,1L,Canvas(Fixture.width,Fixture.height,Fixture.dpi,Fixture.sizeEpoch))
            observe()
            blockedTouch()
            mark("NATIVE_TASK_OK task="+info.taskId+" user="+user)
            return AttachProof(req.id,slotId,owner,target.flattenToString(),true,true)
        }
        fun handle(w:Work) {
            val e=w.effect
            val s=when(e.op) {
                Op.ACQUIRE -> when(checkNotNull(e.resource).kind) {
                    Kind.HOST -> {
                        check(!host.destroyed && main{car.isConnected})
                        snapshot(Observation.PRESENT)
                    }
                    Kind.INPUT_GATE -> {createGate();blockedTouch();snapshot(Observation.PRESENT,true)}
                    Kind.TASK_LEASE -> snapshot(Observation.PRESENT,true,proof=acquireTask(checkNotNull(e.resource!!.request)))
                    Kind.BORROWED_PRESENTATION -> {
                        observe();presentation=main{checkNotNull(view)}
                        snapshot(Observation.PRESENT,true)
                    }
                    Kind.OBSERVER -> {
                        main { listener=View.OnLayoutChangeListener{_,_,_,_,_,_,_,_,_->
                            if(!localStopped)observedChanges++
                        }.also{checkNotNull(view).addOnLayoutChangeListener(it)} }
                        snapshot(Observation.PRESENT,true)
                    }
                }
                Op.VERIFY_VISUAL -> {
                    verifyPixels();blockedTouch()
                    snapshot(blocked=true,scene=scene(),ready=true)
                }
                Op.VERIFY_FOCUS -> {
                    openGate();inst.waitForIdleSync()
                    val old=Fixture.touches;val hostOld=host.hostTouches
                    tap(region());await("native task receives open tap and focus"){Fixture.touches==old+1 && Fixture.focused}
                    inst.waitForIdleSync();check(host.hostTouches==hostOld)
                    val o=observe()
                    // Independently require the WM focus to identify our test activity.
                    check(shell("dumpsys window").lineSequence().any{it.contains("mCurrentFocus") && it.contains("workbench2.nativelab") && it.contains("TargetActivity")}){"No independent WM focus"}
                    verifyPixels()
                    mark("INPUT_GATE_AND_FOCUS_OK")
                    snapshot(blocked=false,scene=scene(),focus=o,ready=true)
                }
                Op.QUIESCE -> {
                    check(localStopped);createGate();blockedTouch()
                    snapshot(blocked=true,quiet=true)
                }
                Op.RELEASE -> when(checkNotNull(e.resource).kind) {
                    Kind.OBSERVER -> {
                        main{listener?.let{checkNotNull(view).removeOnLayoutChangeListener(it)};listener=null}
                        snapshot(Observation.ABSENT,true)
                    }
                    Kind.BORROWED_PRESENTATION -> {presentation=null;snapshot(Observation.ABSENT,true)}
                    Kind.TASK_LEASE -> {
                        val original=checkNotNull(owner);val req=checkNotNull(request)
                        observe()
                        main{checkNotNull(view).release()}
                        await("native release callback and target destruction"){released && Fixture.destroyed}
                        await("independent WM task absent"){taskRecord(original.task)==null}
                        check(main{view?.taskInfo==null}){"Released native view retains task"}
                        main{view?.let{host.slot.removeView(it)};view=null}
                        mark("NATIVE_RELEASE_OK task="+original.task+" vanishedCallback="+vanished)
                        snapshot(Observation.ABSENT,true,proof=AttachProof(req.id,slotId,null,null,true,true))
                    }
                    Kind.INPUT_GATE -> {openGate();snapshot(Observation.ABSENT,false)}
                    Kind.HOST -> {
                        main{controller.release();car.disconnect();host.finishAndRemoveTask()}
                        await("host activity destroyed"){host.destroyed}
                        check(!main{car.isConnected})
                        snapshot(Observation.ABSENT,false)
                    }
                }
                else -> error("Unexpected operation for single new task: "+e.op)
            }
            mark("EFFECT_OK "+e.op+" "+e.resource?.kind)
            main{w.done(s)}
        }
        fun cleanup() {
            main {
                runCatching{view?.release()}
                runCatching{controller.release()}
                runCatching{car.disconnect()}
                runCatching{host.finishAndRemoveTask()}
            }
        }
    }

    @Test fun selfOwnedNativeTaskTraversesRealCoreOnce() {
        check(Build.VERSION.SDK_INT==35 && Build.FINGERPRINT=="google/sdk_gcar_x86_64/emulator_car64_x86_64:15/AAI5.260516.001.A6/16618942:userdebug/test-keys")
        val context=inst.targetContext
        val host=inst.startActivitySync(Intent(context,HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HostActivity
        var port:LabPort?=null
        var car:Car?=null
        var ctl:CarTaskViewController?=null
        try {
            await("measured slot"){main{host.slot.width>0 && host.slot.height>0}}
            // Test SDK token access must actually work on the host's own task before any target is created.
            val hostTask=main{context.getSystemService(ActivityManager::class.java).appTasks.map{it.taskInfo}.single{it.taskId==host.taskId}}
            checkNotNull(TaskIdentity.recent(hostTask))
            ui.adoptShellPermissionIdentity("android.permission.INTERACT_ACROSS_USERS","android.car.permission.MANAGE_CAR_SYSTEM_UI")
            val c=main{Car.createCar(host)}; car=c
            val manager=main{c.getCarManager(Car.CAR_ACTIVITY_SERVICE) as CarActivityManager}
            check(main{manager.isCarSystemUIProxyRegistered})
            val connections=ArrayBlockingQueue<CarTaskViewController>(1)
            main{manager.getCarTaskViewController(host,host.mainExecutor,object:CarTaskViewControllerCallback {
                override fun onConnected(controller:CarTaskViewController){check(connections.offer(controller))}
                override fun onDisconnected(controller:CarTaskViewController){mark("SYSTEM_HOST_DISCONNECTED")}
            })}
            val controller=checkNotNull(connections.poll(8,TimeUnit.SECONDS)){"No authorized controller callback"};ctl=controller
            mark("AUTHORIZED_HOST_OK")
            val firmware=Build.FINGERPRINT
            val version=context.packageManager.getPackageInfo("com.android.car",0).longVersionCode.toString()
            val instance=UUID.randomUUID().toString()
            val p=LabPort(host,c,controller,firmware,version,instance);port=p
            val driver=main{AndroidFrameDriver(p,firmware,version,instance,240)}
            val target=ComponentName(host,TargetActivity::class.java).flattenToString()
            val plan=StartPlan(listOf(LaunchRequest(1,TaskIdentity.userId(Process.myUserHandle()),target,null)),
                listOf(box(main{rect(host.slot)})),0)
            // Isolated one-shot admission only after actual controller authorization and token access
            // on the exact independently validated AAOS build; all effect receipts remain gated.
            val cap=Capability(firmware,version,true,true,true,true,true,true,true)
            check(main{driver.start(cap,plan)})
            fun advanceTo(expected:Phase) {
                val deadline=SystemClock.uptimeMillis()+60000
                while(main{driver.phase()}!=expected) {
                    check(SystemClock.uptimeMillis()<deadline){"Driver did not reach "+expected}
                    check(main{driver.phase()}!=Phase.RECOVERY){"Driver rejected native evidence"}
                    val w=checkNotNull(p.queue.poll(8,TimeUnit.SECONDS)){"No next effect"}
                    try {p.handle(w)} catch(t:Throwable){main{driver.timeout(w.effect)};throw t}
                }
            }
            advanceTo(Phase.ACTIVE)
            mark("CORE_ACTIVE task="+Fixture.task)
            main{driver.stop()}
            advanceTo(Phase.CLOSED)
            check(Fixture.destroyed && host.destroyed)
            mark("CORE_CLOSED")
        } finally {
            try {
                if(port!=null)port.cleanup() else main{runCatching{ctl?.release()};runCatching{car?.disconnect()};host.finishAndRemoveTask()}
            } finally {ui.dropShellPermissionIdentity()}
        }
    }
}
