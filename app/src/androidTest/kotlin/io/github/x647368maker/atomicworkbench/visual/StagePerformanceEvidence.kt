package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import io.github.x647368maker.atomicworkbench.display.TaskSwitcherView
import io.github.x647368maker.atomicworkbench.systemui.StageSparseRailReflowAnimator
import io.github.x647368maker.atomicworkbench.workspace.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Isolated instrumentation, not production SystemUI nor the GPU flight experiment.
 * Same four real 720x1120 Surface producers, stable card sizes from production layout.
 * Continuous 30Hz-target canvas producer and vsync invalidation are explicit fixture load.
 * All samples are raw Window FrameMetrics; no screenshot, disk IO or bitmap scan during capture.
 */
internal class StagePerformanceEvidence(private val inst: Instrumentation) {
    private data class Pose(val x:Int,val y:Int,val w:Int,val h:Int) {
        fun bounds()=StageSparseRailReflowPolicy.Bounds(x.toFloat(),y.toFloat(),w.toFloat(),h.toFloat())
    }
    private data class Layout(val geometry:WorkspaceGeometry,val poses:List<Pose>)
    private data class Sample(val row:LongArray,val valid:Boolean)
    private data class Phase(val round:Int,val layout:String,val kind:String,val target:Int,
        val wall:Long,val cpu:Long,val beatCount:Int,val updates:List<Int>,val posts:List<Int>,
        val samples:List<Sample>,val dropped:Long,val completed:Boolean)
    private val phases=mutableListOf<Phase>()
    private val cards=mutableListOf<TaskSwitcherView>()
    private val textures=arrayOfNulls<SurfaceTexture>(4)
    private val surfaces=arrayOfNulls<Surface>(4)
    private val updates=List(4){AtomicInteger()}
    private val posts=List(4){AtomicInteger()}
    private val ready=CountDownLatch(4)
    private val destroyed=AtomicInteger()
    private val workerStop=AtomicBoolean(false)
    private val workerFailure=AtomicReference<Throwable?>(null)
    private lateinit var activity:Activity
    private lateinit var root:FrameLayout
    private lateinit var marker:View
    private lateinit var metricThread:HandlerThread
    private lateinit var motion:StageSparseRailReflowAnimator
    private var worker:Thread?=null
    private var rootWidth=0
    private var rootHeight=0
    private var metadata=""
    private fun ui(f:()->Unit)=inst.runOnMainSync(f)
    private fun fit(i:Int,w:Int,h:Int) {
        if(w<=0 || h<=0)return
        val scale=minOf(w/720f,h/1120f)
        val x=(w-720f*scale)/2f
        val y=(h-1120f*scale)/2f
        cards[i].textureView.setTransform(Matrix().apply {
            setScale(720f*scale/w,1120f*scale/h);postTranslate(x,y)
        })
        cards[i].setLivePreviewBounds(x,y,w-x,h-y)
    }
    private fun draw(i:Int,s:Surface,seq:Int,paint:Paint) {
        val canvas=s.lockCanvas(null)
        try {
            canvas.drawColor(if(i%2==0)Color.rgb(238,185,40) else Color.rgb(45,200,150))
            val x=(seq%60)*9f
            canvas.drawRect(x,100f,x+120f,310f,paint)
        } finally {s.unlockCanvasAndPost(canvas)}
    }
    private fun layout(top:Boolean,count:Int=4):Layout {
        val base=WorkspaceLayoutProfile.create(1272,2772,512,WorkspaceNavigationInsets(bottom=96))
        val toolbar=base.geometry.copy(topHeight=307)
        val geo=StageAdaptiveLayout.rail(if(top)toolbar.copy(railAtTop=true,
            sidebarWidth=StageTopLayoutMetrics.depth(toolbar,3.2f,count)) else toolbar,count,3.2f)
        val profile=base.copy(geometry=geo,activeCount=count)
        val boxes=StageLiveCardLayout.bounds(profile,List(4){SlotGeometry(720,1120,320)},
            List(4){it<count},listOf(0,1,2,3))
        return Layout(geo,boxes.map { Pose(if(top)it.left else (geo.sidebarWidth-it.width)/2,
            geo.sidebarTop+it.top,it.width,it.height) })
    }
    private fun apply(layout:Layout,count:Int=4) {
        layout.poses.forEachIndexed { i,p ->
            if(i<count)check(p.x>=0 && p.y>=0 && p.w>0 && p.h>0 &&
                p.x+p.w<=rootWidth && p.y+p.h<=rootHeight) { "Card outside actual root: "+p }
            cards[i].layoutParams=FrameLayout.LayoutParams(p.w,p.h).apply{leftMargin=p.x;topMargin=p.y}
            cards[i].alpha=if(i<count)1f else 0f
            fit(i,p.w,p.h)
        }
    }
    private fun setup() {
        activity=inst.startActivitySync(Intent().setClassName(inst.targetContext.packageName,
            inst.targetContext.packageName+".config.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui {
            activity.window.setDecorFitsSystemWindows(false)
            activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
            root=FrameLayout(activity).apply{setBackgroundColor(Color.rgb(20,32,70))}
            activity.setContentView(root)
        }
        inst.waitForIdleSync()
        ui {
            rootWidth=root.width;rootHeight=root.height
            check(root.isHardwareAccelerated && root.isAttachedToWindow && rootWidth>0 && rootHeight>0)
            val dm=activity.resources.displayMetrics
            metadata="api="+Build.VERSION.SDK_INT+"\nfingerprint="+Build.FINGERPRINT+
                "\nmetrics="+dm.widthPixels+"x"+dm.heightPixels+" densityDpi="+dm.densityDpi+
                "\nroot="+rootWidth+"x"+rootHeight+" refreshHz="+activity.display?.refreshRate+
                "\nworkload=4x720x1120 RGBA canvas, 30Hz-target producer, vsync marker; no screenshots in sampling\n"+
                "geometry=1272x2772@512, bottom inset96, toolbar307; test fixture not OEM task engine\n"
            motion=StageSparseRailReflowAnimator(activity,trialDevice=true)
            val initial=layout(false)
            for(i in 0..3) {
                val card=TaskSwitcherView(activity,i)
                for(c in 0 until card.childCount)if(card.getChildAt(c)is TextView)card.getChildAt(c).visibility=View.GONE
                card.textureView.alpha=1f
                cards.add(card)
                var first=true
                card.textureView.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st:SurfaceTexture,w:Int,h:Int) {
                        check(surfaces[i]==null){"Unexpected producer recreation slot="+i}
                        st.setDefaultBufferSize(720,1120);fit(i,w,h)
                        textures[i]=st
                        val s=Surface(st);surfaces[i]=s
                        draw(i,s,0,Paint().apply{color=Color.WHITE});posts[i].incrementAndGet()
                    }
                    override fun onSurfaceTextureSizeChanged(st:SurfaceTexture,w:Int,h:Int) {
                        st.setDefaultBufferSize(720,1120);fit(i,w,h)
                    }
                    override fun onSurfaceTextureUpdated(st:SurfaceTexture) {
                        updates[i].incrementAndGet()
                        if(first){first=false;ready.countDown()}
                    }
                    override fun onSurfaceTextureDestroyed(st:SurfaceTexture):Boolean {
                        destroyed.incrementAndGet();return true
                    }
                }
                val p=initial.poses[i]
                root.addView(card,FrameLayout.LayoutParams(p.w,p.h).apply{leftMargin=p.x;topMargin=p.y})
            }
            marker=View(activity).apply{setBackgroundColor(Color.WHITE)}
            root.addView(marker,FrameLayout.LayoutParams(8,8).apply{leftMargin=8;topMargin=rootHeight-32})
        }
        check(ready.await(20,TimeUnit.SECONDS)){"Four actual TextureView producers did not render"}
        ui {apply(layout(false))}
        metricThread=HandlerThread("PerfFrameMetrics").apply{start()}
        worker=Thread({
            val paint=Paint().apply {color=Color.rgb(245,245,245)}
            var seq=1
            try {
                while(!workerStop.get()) {
                    val next=SystemClock.uptimeMillis()+33L
                    for(i in 0..3) {
                        if(workerStop.get())break
                        draw(i,checkNotNull(surfaces[i]),seq,paint);posts[i].incrementAndGet()
                    }
                    seq++
                    val remaining=next-SystemClock.uptimeMillis()
                    if(remaining>0)SystemClock.sleep(remaining)
                }
            } catch(t:Throwable) {
                if(!workerStop.get())workerFailure.set(t)
            }
        },"PerfLiveProducer").apply{start()}
    }
    private fun collect(round:Int,label:String,kind:String,target:Int,change:(()->Unit)?=null) {
        val handler=Handler(metricThread.looper)
        val rows=ArrayList<Sample>(target+64)
        val timestamps=HashSet<Long>(target+64)
        val latch=CountDownLatch(target)
        var drop=0L
        var start=0L;var end=0L;var cpuStart=0L;var cpuEnd=0L
        var beats=0;var running=true
        var beforeUpdates=emptyList<Int>();var beforePosts=emptyList<Int>()
        var endUpdates=emptyList<Int>();var endPosts=emptyList<Int>()
        val beat=object:Choreographer.FrameCallback {
            override fun doFrame(t:Long) {
                if(!running)return
                beats++;marker.translationX=(beats%100).toFloat()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        val listener=Window.OnFrameMetricsAvailableListener { _,m,missing ->
            val row=longArrayOf(m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                m.getMetric(FrameMetrics.VSYNC_TIMESTAMP),m.getMetric(FrameMetrics.TOTAL_DURATION),
                m.getMetric(FrameMetrics.DRAW_DURATION),m.getMetric(FrameMetrics.SYNC_DURATION),
                m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                m.getMetric(FrameMetrics.DEADLINE),m.getMetric(FrameMetrics.FIRST_DRAW_FRAME),missing.toLong())
            synchronized(rows) {
                drop+=missing.toLong()
                val valid=row[0]>=start && row[0]>0 && row[2]>0 && row[8]!=1L && timestamps.add(row[0])
                rows.add(Sample(row,valid))
                if(valid)latch.countDown()
            }
        }
        var completed=false
        try {
            ui {
                beforeUpdates=updates.map{it.get()};beforePosts=posts.map{it.get()}
                start=System.nanoTime();cpuStart=Debug.threadCpuTimeNanos()
                activity.window.addOnFrameMetricsAvailableListener(listener,handler)
                Choreographer.getInstance().postFrameCallback(beat)
                change?.invoke()
            }
            // One shared 60s budget: frame count AND all four real producers must
            // advance. A fast HWUI marker can reach its count before a slow
            // canvas producer reaches slot 0. Preserve every collected row.
            val deadline=SystemClock.uptimeMillis()+60_000L
            completed=latch.await((deadline-SystemClock.uptimeMillis()).coerceAtLeast(0L),TimeUnit.MILLISECONDS)
            if(completed) {
                while(SystemClock.uptimeMillis()<deadline && workerFailure.get()==null &&
                    !updates.indices.all { updates[it].get()>beforeUpdates[it] && posts[it].get()>beforePosts[it] }) {
                    Thread.sleep(25)
                }
                completed=updates.indices.all { updates[it].get()>beforeUpdates[it] && posts[it].get()>beforePosts[it] }
            }
        } finally {
            ui {
                end=System.nanoTime();cpuEnd=Debug.threadCpuTimeNanos()
                running=false;Choreographer.getInstance().removeFrameCallback(beat)
                activity.window.removeOnFrameMetricsAvailableListener(listener)
                endUpdates=updates.map{it.get()};endPosts=posts.map{it.get()}
            }
            val drained=CountDownLatch(1)
            val drainOk=handler.post{drained.countDown()} && drained.await(5,TimeUnit.SECONDS)
            val data=synchronized(rows){rows.map{it.copy(valid=it.valid && it.row[0]<=end)}}
            phases.add(Phase(round,label,kind,target,end-start,cpuEnd-cpuStart,beats,
                endUpdates.zip(beforeUpdates){a,b->a-b},endPosts.zip(beforePosts){a,b->a-b},
                data,synchronized(rows){drop},completed&&drainOk))
        }
        workerFailure.get()?.let { throw IllegalStateException("Live producer failed",it) }
        val p=phases.last()
        check(p.completed && p.samples.count{it.valid}>=target){"Insufficient unique valid FrameMetrics "+label+" "+kind+" round="+round}
        check(p.updates.all{it>0}&&p.posts.all{it>0}){"Missing live buffer update in measured phase: "+p.updates}
        ui {check(destroyed.get()==0 && cards.indices.all {cards[it].textureView.surfaceTexture===textures[it]})
            {"Live SurfaceTexture identity changed during measured layout"}}
    }
    private fun write(out:File) {
        File(out,"metadata.txt").writeText(metadata+"destroyed="+destroyed.get()+"\nworker_failure="+workerFailure.get()+"\n")
        File(out,"frames.csv").bufferedWriter().use { w ->
            w.appendLine("round,layout,phase,valid,intended_vsync_ns,vsync_ns,total_ns,draw_ns,sync_ns,command_ns,swap_ns,deadline_ns,first_draw,dropped")
            for(p in phases)for(s in p.samples)w.appendLine(listOf(p.round,p.layout,p.kind,if(s.valid)1 else 0).joinToString(",")+","+s.row.joinToString(","))
        }
        File(out,"phases.csv").bufferedWriter().use { w ->
            w.appendLine("round,layout,phase,target,valid,callbacks,dropped,wall_ns,main_cpu_ns,beats,updates0,updates1,updates2,updates3,posts0,posts1,posts2,posts3,completed")
            for(p in phases)w.appendLine(listOf(p.round,p.layout,p.kind,p.target,p.samples.count{it.valid},
                p.samples.size,p.dropped,p.wall,p.cpu,p.beatCount).joinToString(",")+","+p.updates.joinToString(",")+","+p.posts.joinToString(",")+","+(if(p.completed)1 else 0))
        }
    }
    fun run(out:File):String {
        out.mkdirs()
        try {
            setup()
            for(round in 1..3) {
                val order=if(round%2==1)listOf(true,false)else listOf(false,true)
                for(top in order) {
                    val label=if(top)"top"else "side"
                    ui { apply(layout(top)) }
                    collect(round,label,"warmup",30)
                    collect(round,label,"stable",120)
                }
            }
            val three=layout(false,3)
            val four=layout(false,4)
            ui {apply(three,3)}
            Thread.sleep(450)
            collect(1,"side","reflow_3to4",30) {
                val before=(0 until 3).associate{cards[it] as View to three.poses[it].bounds()}
                apply(four)
                val after=(0 until 4).associate{cards[it] as View to four.poses[it].bounds()}
                motion.animate(three.geometry,four.geometry,3,4,before,after,true)
                check(motion.isAnimating(cards[3])){"Production count reflow not admitted"}
            }
            ui {check(cards.none{motion.isAnimating(it)}){"Count reflow did not settle"}}
            // All measurement listeners and frame pump are stopped before screenshots.
            for(top in listOf(true,false)) {
                ui {apply(layout(top))}
                Thread.sleep(450)
                val shot=checkNotNull(inst.uiAutomation.takeScreenshot())
                try {File(out,if(top)"top-four.png"else "side-four.png").outputStream().use{
                    check(shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
                }} finally {shot.recycle()}
            }
        } finally {
            workerStop.set(true)
            worker?.join(4000)
            try {
                if(::root.isInitialized)ui {
                    if(::motion.isInitialized)motion.cancel()
                    root.removeAllViews()
                    surfaces.forEach{it?.release()}
                }
                worker?.join(2000)
            } finally {
                if(::metricThread.isInitialized){metricThread.quitSafely();metricThread.join(2000)}
                write(out)
            }
        }
        check(worker?.isAlive!=true && destroyed.get()==4){"Four producers did not fully detach/release"}
        // Original framework migration semantics, with additional phase clocks only.
        PerformanceTaskMigrationProbe(inst).run(activity,out)
        ui {activity.finish()}
        return "PERFORMANCE_EVIDENCE_PASS: 3x120 valid frames per four-card layout, separate reflow, migration phase timings"
    }
}
