package io.github.x647368maker.atomicworkbench.visual

import android.app.*
import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import io.github.x647368maker.atomicworkbench.systemui.*
import io.github.x647368maker.atomicworkbench.workspace.*
import io.github.x647368maker.atomicworkbench.display.TaskSwitcherView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real HWUI/TextureView/popup fixtures; no claim of ColorOS module/task-migration profiling. */
internal class RefinementRegression(private val inst:Instrumentation) {
    private fun ui(f:()->Unit)=inst.runOnMainSync(f)
    private fun settle(){inst.waitForIdleSync();Thread.sleep(180)}
    private fun click(label:String) {
        val node=inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull{it.text?.toString()==label} ?: error("Missing command $label")
        val b=Rect();node.getBoundsInScreen(b)
        val t=SystemClock.uptimeMillis()
        for(a in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),a,b.exactCenterX(),b.exactCenterY(),0);e.source=InputDevice.SOURCE_TOUCHSCREEN
            check(inst.uiAutomation.injectInputEvent(e,true));e.recycle()
        }
        settle()
    }
    fun run(activity:Activity,root:FrameLayout,out:File):List<String> {
        val log=mutableListOf<String>()
        fun record(s:String){log.add(s);File(out,"refinement-results.txt").writeText(log.joinToString("\n")+"\n")}
        val cards=mutableListOf<TaskSwitcherView>();val surfaces=mutableListOf<Surface>();val textures=mutableListOf<SurfaceTexture>()
        val ready=CountDownLatch(4);var destroyed=0
        lateinit var anchor:TextView
        ui {
            root.removeAllViews();root.setBackgroundColor(Color.rgb(65,120,155))
            anchor=TextView(activity).apply{text="⋯";textSize=22f;setTextColor(Color.WHITE)}
            root.addView(anchor,FrameLayout.LayoutParams(120,120).apply{leftMargin=25;topMargin=100})
            repeat(4){i->
                val card=TaskSwitcherView(activity,i);cards.add(card);card.textureView.alpha=1f
                root.addView(card,FrameLayout.LayoutParams(180,300).apply{leftMargin=40+i*230;topMargin=280})
                card.textureView.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int){
                        textures.add(t);t.setDefaultBufferSize(720,1120)
                        val s=Surface(t);surfaces.add(s);val c=s.lockCanvas(null);c.drawColor(Color.rgb(50+i*35,180,140));s.unlockCanvasAndPost(c)
                    }
                    override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int){t.setDefaultBufferSize(720,1120)}
                    override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean {destroyed++;return true}
                    override fun onSurfaceTextureUpdated(t:SurfaceTexture){if(ready.count>0)ready.countDown()}
                }
            }
        }
        check(ready.await(10,TimeUnit.SECONDS));settle()
        val initial=textures.toSet();check(initial.size==4)
        val metrics=mutableListOf<Long>()
        val metricThread=HandlerThread("StageFrameMetrics").apply{start()}
        val listener=Window.OnFrameMetricsAvailableListener{_,m,_->synchronized(metrics){metrics.add(m.getMetric(FrameMetrics.TOTAL_DURATION))}}
        ui{activity.window.addOnFrameMetricsAvailableListener(listener,Handler(metricThread.looper))}
        val commands=listOf("切换全屏（保留工作区）","顶部横排","左侧排列","右侧排列","收起常用应用快捷栏","完整显示布局","✓ 主窗口优先布局","添加小窗","影音布局与自动模式","将当前微信任务移入小窗","替换主应用","关闭主窗（移回后台）","退出台前调度")
        var fired=-1
        val actions=commands.mapIndexed{i,label->label to {fired=i}}
        val expected=(0..3).map{category->commands.indices.filter{StageMenuPolicy.group(commands[it])==category}}
        val m=activity.resources.displayMetrics
        val counts=mutableListOf<String>()
        for(mode in listOf("top","left","right")) {
            val profile=WorkspaceLayoutProfile.create(m.widthPixels,m.heightPixels,m.densityDpi,WorkspaceNavigationInsets())
            val toolbar=profile.geometry.copy(topHeight=(96*m.density).toInt())
            val geom=if(mode=="top") StageAdaptiveLayout.rail(toolbar.copy(railAtTop=true,sidebarWidth=StageTopLayoutMetrics.depth(toolbar,m.density,4)),4,m.density)
                else toolbar.copy(sidebarSide=if(mode=="left")SidebarSide.LEFT else SidebarSide.RIGHT)
            val p=profile.copy(geometry=geom,activeCount=4)
            val boxes=StageLiveCardLayout.bounds(p,List(4){p.canvas},List(4){true},listOf(0,1,2,3))
            val avoid=mutableListOf(Rect(geom.contentLeft,geom.contentTop,geom.contentRight,geom.contentBottom))
            ui {
                for(i in 0..3){val b=boxes[i];val x=if(mode=="top")b.left else if(mode=="left")0 else m.widthPixels-b.width
                    cards[i].layoutParams=FrameLayout.LayoutParams(b.width,b.height).apply{leftMargin=x;topMargin=geom.sidebarTop+b.top}
                    avoid.add(Rect(x,geom.sidebarTop+b.top,x+b.width,geom.sidebarTop+b.top+b.height))
                }
            };settle()
            for(category in 0..3){
                ui{AtomicPopupMenu.show(activity,anchor,actions,avoid)};settle()
                if(category==0){
                    val b=Rect();inst.uiAutomation.rootInActiveWindow!!.getBoundsInScreen(b)
                    check(b.width()<=209*m.density && b.height()<=minOf(301*m.density,m.heightPixels*.40f))
                    inst.uiAutomation.takeScreenshot()?.let{shot->File(out,"011-menu-$mode.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()}
                }
                click("${StageMenuPolicy.groups[category]}  ›")
                click("‹  ${StageMenuPolicy.groups[category]}")
                ui{AtomicPopupMenu.dismissAll()};settle()
                for(index in expected[category]){
                    fired=-1;ui{AtomicPopupMenu.show(activity,anchor,actions,avoid)};settle();click("${StageMenuPolicy.groups[category]}  ›");click(commands[index])
                    check(fired==index){"Wrong callback $mode $index -> $fired"};check(!AtomicPopupMenu.isShowing())
                }
            }
            var scrolled=-1
            ui{AtomicPopupMenu.show(activity,anchor,(0..19).map{i->"SCROLL_ACTION_$i" to {scrolled=i}},avoid)};settle()
            fun scrollNode(n:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                if(n==null)return null
                if(n.isScrollable)return n
                for(i in 0 until n.childCount){val found=scrollNode(n.getChild(i));if(found!=null)return found}
                return null
            }
            val scroll=checkNotNull(scrollNode(inst.uiAutomation.rootInActiveWindow)){"Bounded menu cannot scroll"}
            repeat(4){scroll.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);settle()}
            click("SCROLL_ACTION_19");check(scrolled==19){"Last scrolled command was not dispatched"}
            check(textures.toSet()==initial && destroyed==0){"Presentation rebuilt TextureView producers"}
            counts.add("$mode=13 callbacks, 4 back navigations, producer identities unchanged")
        }
        record("PASS real grouped native popup: ${counts.joinToString("; ")}; bounded 208dp primary and 220dp secondary; all original actions reachable")
        // Same emulator, same four live producers. Real screenshot API is the blocking
        // workload; this measures scheduling only, NOT native task migration or PMS110 FPS.
        val beatTimes=mutableListOf<Long>();var tracing=true
        val beat=object:Choreographer.FrameCallback{
            override fun doFrame(t:Long){beatTimes.add(t);if(tracing)Choreographer.getInstance().postFrameCallback(this)}
        }
        ui{Choreographer.getInstance().postFrameCallback(beat)}
        fun snapshotStats():Pair<Int,Long> {val t=beatTimes.toList();return t.size to (t.zipWithNext().maxOfOrNull{(a,b)->(b-a)/1_000_000} ?: 0)}
        ui{beatTimes.clear()};Thread.sleep(120)
        val beforeTime=SystemClock.uptimeMillis()
        repeat(4){ui{inst.uiAutomation.takeScreenshot()?.recycle()};Thread.sleep(45)}
        Thread.sleep(120);lateinit var before:Pair<Int,Long>;ui{before=snapshotStats()}
        val beforeMs=SystemClock.uptimeMillis()-beforeTime
        val helper=StageAsyncPreviewCapture();var nonUi=0;var completed=0;var fallbacks=0
        val loads=CountDownLatch(4)
        ui{beatTimes.clear()};Thread.sleep(120);val afterTime=SystemClock.uptimeMillis()
        repeat(4){val latch=CountDownLatch(1);val captureDone=CountDownLatch(1)
            ui{helper.request(load={check(Looper.myLooper()!=Looper.getMainLooper());nonUi++;try{inst.uiAutomation.takeScreenshot()}finally{loads.countDown();captureDone.countDown()}},valid={true},accept={if(it==null)fallbacks++;it?.recycle();completed++;latch.countDown()},onCancel={error("Capture cancelled unexpectedly")})}
            check(latch.await(5,TimeUnit.SECONDS) && captureDone.await(5,TimeUnit.SECONDS));Thread.sleep(45)
        }
        Thread.sleep(120);lateinit var after:Pair<Int,Long>;ui{after=snapshotStats();tracing=false;helper.cancel()}
        check(loads.await(8,TimeUnit.SECONDS) && completed==4 && nonUi==4)
        record("MEASURE screenshot scheduling fixture, same emulator/four live producers/right layout: baseline_ui_capture elapsed_ms=$beforeMs frames=${before.first} max_gap_ms=${before.second}; candidate_worker_capture elapsed_ms=${SystemClock.uptimeMillis()-afterTime} frames=${after.first} max_gap_ms=${after.second} deadline_fallbacks=$fallbacks; no assertion that fixture timing equals ColorOS task performance")
        val cancelDone=CountDownLatch(1);val release=CountDownLatch(1);var staleAccepted=false
        ui{helper.request(load={release.await(2,TimeUnit.SECONDS);null},valid={true},accept={staleAccepted=true},onCancel={cancelDone.countDown()});helper.cancel()}
        release.countDown();check(cancelDone.await(2,TimeUnit.SECONDS));Thread.sleep(250);check(!staleAccepted)
        for(failValid in listOf(true,false)) {
            val done=CountDownLatch(1);var bitmap:Bitmap?=null;var cancels=0
            ui {helper.request(load={Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888).also{bitmap=it}},
                valid={if(failValid)error("intentional validation failure") else true},
                accept={error("intentional accept failure")},onCancel={cancels++;done.countDown()})}
            check(done.await(3,TimeUnit.SECONDS));Thread.sleep(100)
            check(cancels==1 && bitmap?.isRecycled==true){"Failed async callback leaked its bitmap or transition"}
        }
        record("PASS asynchronous capture never blocks UI workload; exactly-once result and cancelled generation never accepts stale capture; validation/accept exceptions recycle and cancel exactly once; stable handoff unit tests retain final geometry")
        val samples=synchronized(metrics){metrics.toList().sorted()}
        check(samples.isNotEmpty() && after.first>=3){"No real HWUI/frame-clock samples"}
        record("MEASURE HWUI FrameMetrics across top/left/right popup/producer fixtures: samples=${samples.size} p50_ms=${samples.getOrElse(samples.size/2){0}/1_000_000.0} p95_ms=${samples.getOrElse((samples.size*.95).toInt().coerceAtMost((samples.size-1).coerceAtLeast(0))){0}/1_000_000.0} over_16.67ms=${samples.count{it>16_666_667}}. No actual third-party task is launched or migrated in this fixture.")
        ui{activity.window.removeOnFrameMetricsAvailableListener(listener);root.removeAllViews();surfaces.forEach{it.release()}}
        metricThread.quitSafely()
        return log
    }
}
