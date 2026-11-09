package io.github.x647368maker.atomicworkbench.visual

import android.app.*
import android.content.Intent
import android.graphics.Rect
import android.os.*
import android.view.*
import io.github.x647368maker.atomicworkbench.display.*
import io.github.x647368maker.atomicworkbench.task.*
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.monitoring.WorkspaceEnergyDiagnostics
import io.github.x647368maker.atomicworkbench.workspace.*
import java.io.File

/** Executes the actual private migration helper in both complete coordinator classes.
 * Real five-task/two-leg framework exchange, not the SystemUI Surface/overlay path.
 */
internal class QueryElisionRegression(private val i:Instrumentation) {
    fun run(activity:Activity,out:File,slots:List<Int>,displays:List<Int>,bufferStamp:()->Int,awaitBuffer:(Int)->Unit):String {
        val am=activity.getSystemService(ActivityManager::class.java)
        val probe=i.startActivitySync(Intent().setClassName(activity.packageName,activity.packageName+".config.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        val probeId=probe.taskId
        var main=probeId;var small=slots[0]
        val mode=java.util.concurrent.atomic.AtomicInteger(-1);var last=0L
        val render=Array(2){mutableListOf<Long>()};val created=IntArray(2)
        val metricThread=HandlerThread("QueryElisionFrameMetrics").apply{start()}
        val windows=java.util.WeakHashMap<Window,Boolean>()
        val listener=Window.OnFrameMetricsAvailableListener{_,m,_->
            val cohort=mode.get();if(cohort>=0)synchronized(render[cohort]){render[cohort].add(m.getMetric(FrameMetrics.TOTAL_DURATION))}
        }
        val lifecycle=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:Activity,b:Bundle?){val cohort=mode.get();if(cohort>=0 && a.taskId in (slots+probeId))created[cohort]++}
            override fun onActivityResumed(a:Activity){if(a.taskId in (slots+probeId) && windows.put(a.window,true)==null)a.window.addOnFrameMetricsAvailableListener(listener,Handler(metricThread.looper))}
            override fun onActivityStarted(a:Activity){};override fun onActivityPaused(a:Activity){};override fun onActivityStopped(a:Activity){}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle){};override fun onActivityDestroyed(a:Activity){}
        }
        i.runOnMainSync{activity.application.registerActivityLifecycleCallbacks(lifecycle)}
        val gaps=Array(2){mutableListOf<Long>()};val elapsed=Array(2){mutableListOf<Double>()}
        val queries=Array(2){mutableListOf<Long>()}
        val frame=object:Choreographer.FrameCallback {
            override fun doFrame(t:Long){if(mode.get()>=0){if(last!=0L)gaps[mode.get()].add((t-last)/1_000_000);last=t;Choreographer.getInstance().postFrameCallback(this)}}
        }
        lateinit var baseline:Any;lateinit var candidate:Any
        val log=mutableListOf<String>()
        fun taskDisplay(id:Int)=am.getRunningTasks(100).firstOrNull{it.taskId==id}?.let{it.javaClass.getField("displayId").getInt(it)}
        fun move(owner:Any,id:Int,destination:Int){
            owner.javaClass.getDeclaredMethod("moveTaskToDisplayAndWait",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
                .apply{isAccessible=true}.invoke(owner,id,destination)
        }
        try {
            i.runOnMainSync {
                TaskSurfaceCompat.initialize(activity.classLoader);TaskSurfaceCompat.initializeTaskAccess(activity)
                val m=activity.resources.displayMetrics
                val g=WorkspaceLayoutProfile.create(m.widthPixels,m.heightPixels,m.densityDpi,WorkspaceNavigationInsets()).geometry
                baseline=io.github.x647368maker.atomicworkbench.display.BaselineSlotTaskCoordinator(activity,List(4){VirtualDisplaySlot(activity,it)},MainTaskPresentationCoordinator(activity),{g})
                candidate=SlotTaskCoordinator(activity,List(4){VirtualDisplaySlot(activity,it)},MainTaskPresentationCoordinator(activity),{g})
            }
            check(taskDisplay(main)==0 && taskDisplay(small)==displays[0])
            // Alternating orders, identical five real tasks, same displays, same main looper.
            for(round in -2..9)for(which in if(round%2==0)listOf(0,1)else listOf(1,0)) {
                val measured=round>=0
                val owner=if(which==0)baseline else candidate
                val mainBefore=main;val smallBefore=small
                i.runOnMainSync {mode.set(if(measured)which else -1);last=0;if(measured)Choreographer.getInstance().postFrameCallback(frame)}
                Thread.sleep(40)
                var stamp=0
                i.runOnMainSync {
                    check(Looper.myLooper()==Looper.getMainLooper())
                    val count=WorkspaceEnergyDiagnostics.taskQueries.get()
                    val start=SystemClock.elapsedRealtimeNanos()
                    move(owner,smallBefore,0)
                    am.moveTaskToFront(smallBefore,ActivityManager.MOVE_TASK_WITH_HOME)
                    stamp=bufferStamp()
                    move(owner,mainBefore,displays[0])
                    if(measured) {
                        elapsed[which].add((SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0)
                        queries[which].add(WorkspaceEnergyDiagnostics.taskQueries.get()-count)
                    }
                }
                main=smallBefore;small=mainBefore
                check(taskDisplay(main)==0 && taskDisplay(small)==displays[0]) {"Real task ownership changed unexpectedly"}
                awaitBuffer(stamp);i.waitForIdleSync();Thread.sleep(40)
                i.runOnMainSync {Choreographer.getInstance().removeFrameCallback(frame);mode.set(-1)}
            }
            fun median(a:List<Double>)=a.sorted()[a.size/2]
            check(queries[0].all{it>=8} && queries[1].all{it>=6})
            check(queries[0].sorted()[5]-queries[1].sorted()[5]==2L) {"Expected two eliminated pre-mutation queries per two-leg exchange"}
            // Actual foreground activity receives a raw touch; the task identity stays intact.
            val target=i.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("运行诊断")?.firstOrNull{it.text?.toString()=="运行诊断"}
                ?: error("Promoted real application did not expose its live UI")
            val box=Rect();target.getBoundsInScreen(box);val down=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,box.exactCenterX(),box.exactCenterY(),0)
                event.source=InputDevice.SOURCE_TOUCHSCREEN;check(i.uiAutomation.injectInputEvent(event,true));event.recycle()
            }
            i.waitForIdleSync();Thread.sleep(200)
            check(i.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("刷新连接状态")?.isNotEmpty()==true)
            check(taskDisplay(main)==0)
            // Idempotence, failed real Binder move, cleanup and retained task identity.
            for(owner in listOf(baseline,candidate)) {
                i.runOnMainSync {
                    val count=WorkspaceEnergyDiagnostics.taskQueries.get();move(owner,main,0)
                    check(WorkspaceEnergyDiagnostics.taskQueries.get()-count==1L)
                    var failed=false
                    try{move(owner,main,-98765)}catch(_:java.lang.reflect.InvocationTargetException){failed=true}
                    check(failed && !TaskSurfaceCompat.isDisplayExchanging(main))
                }
                check(taskDisplay(main)==0)
            }
            log.add("Scope: two warmup pairs per cohort then 10 measured pairs per cohort; actual baseline/candidate SlotTaskCoordinator migration helpers + real foreground move; five real tasks/four VDs; Surface fitting, production overlay and ColorOS not exercised")
            for(which in 0..1) {
                val frames=synchronized(render[which]){render[which].sorted()}
                check(frames.isNotEmpty()){ "No actual Window.FrameMetrics in cohort $which" }
                val p95=frames[((frames.size-1)*.95).toInt()]/1_000_000.0
                log.add("${if(which==0)"baseline" else "candidate"}: pairs=${elapsed[which].size} main_critical_median_ms=${median(elapsed[which])} main_critical_max_ms=${elapsed[which].maxOrNull()} task_queries=${queries[which]} choreographer_gap_max_ms=${gaps[which].maxOrNull()} gaps_over_17ms=${gaps[which].count{it>17}} frame_samples=${frames.size} frame_p95_ms=$p95 frame_over_17ms=${frames.count{it>17_000_000}} activity_created=${created[which]}")
            }
            File(out,"query-elision-data.json").writeText(org.json.JSONObject().apply{
                put("scope","framework_pair_not_full_SystemUI_exchange");put("baseline_median_ms",median(elapsed[0]));put("candidate_median_ms",median(elapsed[1]))
                put("baseline_max_ms",elapsed[0].maxOrNull());put("candidate_max_ms",elapsed[1].maxOrNull())
                put("baseline_query_median",queries[0].sorted()[5]);put("candidate_query_median",queries[1].sorted()[5])
                put("baseline_gap_max_ms",gaps[0].maxOrNull());put("candidate_gap_max_ms",gaps[1].maxOrNull())
            }.toString(2))
            log.add("All five task IDs retained; promoted real UI received raw touch; destination fresh buffers observed; idempotence/failure cleanup passed; lifecycle restart/flicker and full production exchange NOT VERIFIED")
            File(out,"query-elision-comparison.txt").writeText(log.joinToString("\n"))
            return "PASS actual migration helper comparison, two pre-mutation queries removed per pair, ownership/fresh buffers/error cleanup retained; no ColorOS claim"
        } finally {
            i.runOnMainSync {mode.set(-1);Choreographer.getInstance().removeFrameCallback(frame)
                activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
                windows.keys.toList().forEach{runCatching{it.removeOnFrameMetricsAvailableListener(listener)}}
            }
            metricThread.quitSafely()
            // Return every original task to its original fixture display before its tests resume.
            i.runOnMainSync {
                for(n in slots.indices)runCatching {move(candidate,slots[n],displays[n])}
                runCatching {io.github.x647368maker.atomicworkbench.framework.ActivityTaskManagerCompat.removeTask(probeId).getOrThrow()}
                (slots+probeId).forEach{TaskSurfaceCompat.cancelDisplayExchange(it);TaskSurfaceCompat.cancelPreparedPromotion(it)}
            }
            if(log.isNotEmpty())File(out,"query-elision-comparison.txt").writeText(log.joinToString("\n"))
        }
    }
}
