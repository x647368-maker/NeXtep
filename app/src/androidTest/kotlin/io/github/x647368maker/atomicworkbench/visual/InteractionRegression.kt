package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.*
import android.widget.*
import io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.launcher.TouchCoordinateMapper
import io.github.x647368maker.atomicworkbench.systemui.*
import io.github.x647368maker.atomicworkbench.workspace.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real positioned Android windows/input + actual compositor transactions. No third-party state assertions. */
internal class InteractionRegression(private val inst: Instrumentation) {
    private fun ui(action:()->Unit)=inst.runOnMainSync(action)
    private fun pause() {inst.waitForIdleSync();Thread.sleep(100)}
    private fun tap(view: View) {
        val at=IntArray(2);ui{view.getLocationOnScreen(at)}
        val x=at[0]+view.width/2f;val y=at[1]+view.height/2f
        val t=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val e=MotionEvent.obtain(t,t+20,action,x,y,0)
            check(inst.uiAutomation.injectInputEvent(e,true));e.recycle()
        }
        pause()
    }
    private fun find(v: View, description: String): View? {
        if(v.contentDescription?.toString()==description)return v
        if(v is android.view.ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),description)?.let{return it}
        return null
    }
    fun run(activity: Activity,root: FrameLayout,out: File):List<String> {
        val log=mutableListOf<String>()
        testControls(activity,root,log)
        testSurface(activity,root,out,log)
        File(out,"interaction-results.txt").writeText(log.joinToString("\n"))
        return log
    }
    private fun testControls(activity: Activity,root: FrameLayout,log: MutableList<String>) {
        val wm=activity.getSystemService(WindowManager::class.java)
        var ready=false;var expanded=true;var collapsed=0
        lateinit var host: StageQuickBarHost;lateinit var bar: StageQuickBarView;lateinit var expand: TextView
        val store=StageQuickStore(activity)
        val pins=listOf("0:test-a","0:test-b","0:test-c");store.pins=pins
        ui {
            host=StageQuickBarHost(activity,{ready=it},{})
            bar=StageQuickBarView(activity,{_,_,_->},{},{expanded=false;collapsed++;store.expanded=false;bar.visibility=View.GONE;expand.visibility=View.VISIBLE;host.requestLayout()})
            val entries=pins.mapIndexed {i,key->LauncherAppRepository.AppEntry(key,ComponentName(activity.packageName,activity.javaClass.name),0,"TEST $i",ColorDrawable(Color.rgb(80+i*40,150,200)),false)}
            bar.render(entries)
            expand=TextView(activity).apply {
                text="EXPAND";contentDescription="TEST_EXPAND";gravity=Gravity.CENTER;setTextColor(Color.WHITE)
                setBackgroundColor(Color.DKGRAY);visibility=View.GONE
                setOnClickListener {expanded=true;store.expanded=true;bar.visibility=View.VISIBLE;visibility=View.GONE;host.requestLayout()}
            }
            host.addView(bar,FrameLayout.LayoutParams(-1,166,Gravity.BOTTOM))
            host.addView(expand,FrameLayout.LayoutParams(154,154,Gravity.BOTTOM or Gravity.END))
            val p=WindowManager.LayoutParams(1112,240,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
            p.token=activity.window.decorView.windowToken;p.gravity=Gravity.TOP or Gravity.LEFT;p.x=80;p.y=180;p.setFitInsetsTypes(0)
            wm.addView(host,p)
        }
        pause();check(ready){"Touch region listener not installed"}
        val collapse=find(bar,"收起常用应用快捷栏") ?: error("No collapse control")
        val oldRect=Rect();val origin=IntArray(2)
        ui {check(bar.getGlobalVisibleRect(oldRect));host.getLocationOnScreen(origin)}
        val legacy=Rect(oldRect).apply{offset(-origin[0],-origin[1])}
        log.add("TOUCH windowOrigin=${origin.toList()} oldRegion=$legacy newRegion=$oldRect")
        check(oldRect.top!=legacy.top && origin[1]!=0){"Offset-window regression not exercised"}
        repeat(20) {
            tap(collapse);check(!expanded && bar.visibility==View.GONE && expand.visibility==View.VISIBLE){"Collapse tap was lost"}
            tap(expand);check(expanded && bar.visibility==View.VISIBLE){"Expand tap was lost"}
            check(store.pins==pins && StageQuickStore(activity).expanded){"Pins/order/state lost"}
        }
        check(collapsed==20)
        log.add("PASS 20 actual InputDispatcher collapse/expand cycles in nonzero-offset window; cached icon order and persisted state retained")
        ui{wm.removeViewImmediate(host)}
        lateinit var grip: StageMainHandle
        var menus=0;var actions=0
        ui {
            grip=StageMainHandle(activity,{
                menus++
                AtomicPopupMenu.show(activity,grip,listOf("TEST_ACTION" to {actions++}))
                AtomicPopupMenu.show(activity,grip,listOf("TEST_ACTION" to {actions++}))
            },{})
            root.removeAllViews()
            root.addView(grip,FrameLayout.LayoutParams(154,154).apply{leftMargin=80;topMargin=180})
        }
        pause()
        repeat(20) {
            tap(grip)
            val nodes=inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("TEST_ACTION").orEmpty()
            check(nodes.size==1){"Menu missing or duplicated: ${nodes.size}"}
            check(nodes[0].performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            pause()
        }
        check(menus==20 && actions==20)
        check(grip.width>=153 && grip.height>=153)
        ui{root.removeView(grip)}
        log.add("PASS 20 real 48dp main-menu taps, single popup replacement and action dispatch")
    }
    private fun testSurface(activity:Activity,root:FrameLayout,out:File,log:MutableList<String>) {
        val ready=CountDownLatch(1);lateinit var surfaceView:SurfaceView
        ui {
            root.removeAllViews()
            surfaceView=SurfaceView(activity)
            surfaceView.holder.addCallback(object:SurfaceHolder.Callback {
                override fun surfaceCreated(h:SurfaceHolder){ready.countDown()}
                override fun surfaceChanged(h:SurfaceHolder,f:Int,w:Int,hh:Int){}
                override fun surfaceDestroyed(h:SurfaceHolder){}
            })
            root.addView(surfaceView,FrameLayout.LayoutParams(-1,-1))
        }
        check(ready.await(10,TimeUnit.SECONDS));pause()
        val w=surfaceView.holder.surfaceFrame.width();val h=surfaceView.holder.surfaceFrame.height()
        check(w>1000 && h>2000){"Unexpected source canvas $w x $h"}
        val c=surfaceView.holder.lockCanvas();c.drawColor(Color.rgb(30,45,65))
        val paint=Paint().apply{style=Paint.Style.FILL}
        fun edge(color:Int,l:Float,t:Float,r:Float,b:Float){paint.color=color;c.drawRect(l,t,r,b,paint)}
        edge(Color.RED,0f,0f,32f,h.toFloat());edge(Color.GREEN,w-32f,0f,w.toFloat(),h.toFloat())
        edge(Color.BLUE,0f,0f,w.toFloat(),32f);edge(Color.YELLOW,0f,h-32f,w.toFloat(),h.toFloat())
        paint.color=Color.WHITE;paint.textSize=60f
        for(i in 1..12)c.drawText("FULL SOURCE / LINE $i",80f,150f+i*150,paint)
        surfaceView.holder.unlockCanvasAndPost(c);pause()
        val frameClass=TaskSurfaceCompat::class.java.declaredClasses.first{it.simpleName=="SurfaceFrame"}
        val ctor=frameClass.getDeclaredConstructor(Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Rect::class.java).apply{isAccessible=true}
        val cropMethod=TaskSurfaceCompat::class.java.getDeclaredMethod("sourceCrop",WorkspaceGeometry::class.java).apply{isAccessible=true}
        val write=TaskSurfaceCompat::class.java.getDeclaredMethod("writeFrame",Any::class.java,Any::class.java,frameClass).apply{isAccessible=true}
        TaskSurfaceCompat::class.java.getDeclaredField("displayDensity").apply{isAccessible=true;setFloat(TaskSurfaceCompat,3.2f)}
        val origin=IntArray(2);ui{surfaceView.getLocationOnScreen(origin)}
        var seen:PointF?=null;var current:WorkspaceGeometry?=null
        ui {
            root.setOnTouchListener {_,event ->
                current?.let {g->
                    val copy=MotionEvent.obtain(event)
                    TouchCoordinateMapper.toContent(copy,TouchCoordinateMapper.Transform(g.contentScale,g.contentScale,g.contentTranslationX,g.contentTranslationY))
                    if(event.actionMasked==MotionEvent.ACTION_UP)seen=PointF(copy.x,copy.y)
                    copy.recycle()
                };true
            }
        }
        // SurfaceView does not consume gestures itself; root listener handles ordinary dispatch.
        ui{surfaceView.setOnTouchListener {_,e->
            current?.let {g->val copy=MotionEvent.obtain(e);TouchCoordinateMapper.toContent(copy,TouchCoordinateMapper.Transform(g.contentScale,g.contentScale,g.contentTranslationX,g.contentTranslationY));if(e.actionMasked==MotionEvent.ACTION_UP)seen=PointF(copy.x,copy.y);copy.recycle()};true
        }}
        var previous=0L
        for(i in 0 until 20) {
            val priority=i%2==1
            val b=WorkspaceLayoutProfile.create(w,h,512,WorkspaceNavigationInsets(24,108,24,96))
            val g0=b.geometry.copy(railAtTop=true,topHeight=(StagePresentationPolicy.toolbarDp(priority,false)*3.2f).toInt())
            val p=StagePresentationPolicy.apply(b.copy(geometry=StageAdaptiveLayout.rail(g0,4,3.2f),activeCount=4),priority,true)
            val g=p.geometry;current=g
            val crop=cropMethod.invoke(TaskSurfaceCompat,g) as Rect
            check(crop==Rect(0,0,w,h))
            if(i<2)log.add("SURFACE priority=$priority source=${w}x$h crop=$crop content=${g.contentWidth}x${g.contentHeight}@${g.contentLeft},${g.contentTop} scale=${g.contentScale}")
            val frame=ctor.newInstance(g.contentScale,g.contentScale,g.contentTranslationX,g.contentTranslationY,crop)
            ui{SurfaceControl.Transaction().use {t->write.invoke(TaskSurfaceCompat,t,surfaceView.surfaceControl,frame);t.apply()}}
            Thread.sleep(180)
            val shot=inst.uiAutomation.takeScreenshot() ?: error("No compositor screenshot")
            val points=listOf(Triple(12f,h/2f,Color.RED),Triple(w-12f,h/2f,Color.GREEN),Triple(w/2f,12f,Color.BLUE),Triple(w/2f,h-12f,Color.YELLOW))
            for((x,y,color)in points) {
                val px=(origin[0]+g.contentTranslationX+x*g.contentScale).toInt().coerceIn(0,shot.width-1)
                val py=(origin[1]+g.contentTranslationY+y*g.contentScale).toInt().coerceIn(0,shot.height-1)
                check(shot.getPixel(px,py)==color){"Source edge cropped/mapped incorrectly at $i: $px,$py got=${shot.getPixel(px,py)} expected=$color"}
            }
            val sx=w*.55f;val sy=h*.70f;val tx=origin[0]+g.contentTranslationX+sx*g.contentScale;val ty=origin[1]+g.contentTranslationY+sy*g.contentScale
            val now=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val e=MotionEvent.obtain(now,now+10,action,tx,ty,0);check(inst.uiAutomation.injectInputEvent(e,true));e.recycle()}
            inst.waitForIdleSync();check(seen!=null && kotlin.math.abs(seen!!.x-sx)<3 && kotlin.math.abs(seen!!.y-sy)<3){"Native touch inverse mismatch: $seen vs $sx,$sy"}
            val area=g.contentWidth.toLong()*g.contentHeight
            if(i%2==1)check(area>previous){"Priority had no real area effect"}
            previous=area
            if(i<2)File(out,if(priority)"098-main-priority.png" else "098-main-complete.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)}
            shot.recycle()
        }
        ui{root.setOnTouchListener(null);root.removeAllViews()}
        log.add("PASS production TaskSurfaceCompat writeFrame on actual SurfaceControl: all four coloured source edges + navigation marker preserved through 20 policy changes, injected touch inverse accurate")
    }
}
