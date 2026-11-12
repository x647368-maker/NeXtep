package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import io.github.x647368maker.atomicworkbench.display.TaskSwitcherView
import io.github.x647368maker.atomicworkbench.workspace.*
import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android HWUI + live Surface producer. No root, third-party apps or task mocks. */
class LivePreviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle()
        try {
            val activity=startActivitySync(Intent().setClassName(targetContext.packageName,
                targetContext.packageName+".config.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val root=FrameLayout(activity)
            runOnMainSync { root.setBackgroundColor(Color.rgb(20,32,70)); activity.setContentView(root) }
            waitForIdleSync()
            val out=File(targetContext.filesDir,"visual-regression").apply { mkdirs() }
            val checks=mutableListOf<String>()
            checks.add(coldBootstrap(root,activity,out))
            for (legacy in listOf(true,false)) {
                val ready=CountDownLatch(1)
                lateinit var card: FrameLayout
                lateinit var texture: TextureView
                var producer: Surface?=null
                runOnMainSync {
                    root.removeAllViews()
                    card=if(legacy) FrameLayout(activity).apply {
                        clipToOutline=true
                        outlineProvider=object: ViewOutlineProvider() {
                            override fun getOutline(v: View,o: Outline) { o.setRoundRect(0,0,v.width,v.height,io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.radius(v.width.toFloat(),v.height.toFloat(),v.resources.displayMetrics.density)) }
                        }
                    } else TaskSwitcherView(activity,0).apply {
                        for(i in 0 until childCount) if(getChildAt(i) is TextView) getChildAt(i).visibility=View.GONE
                    }
                    texture=if(legacy) TextureView(activity).also { card.addView(it,FrameLayout.LayoutParams(-1,-1)) }
                        else (card as TaskSwitcherView).textureView
                    texture.alpha=1f // Test a live occupied producer, not the initial Empty state.
                    root.addView(card,FrameLayout.LayoutParams(240,520).apply { leftMargin=80;topMargin=100 })
                    texture.surfaceTextureListener=object: TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture,w: Int,h: Int) {
                            st.setDefaultBufferSize(720,1120)
                            texture.setTransform(Matrix().apply { setScale(1f,374f/520f);postTranslate(0f,73f) })
                            if(!legacy) (card as TaskSwitcherView).setLivePreviewBounds(0f,73f,240f,447f)
                            producer=Surface(st)
                            producer!!.lockCanvas(null).also { it.drawColor(Color.rgb(238,185,40));producer!!.unlockCanvasAndPost(it) }
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture,w: Int,h: Int) {}
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) { ready.countDown() }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { producer?.release();return true }
                    }
                }
                check(ready.await(10,TimeUnit.SECONDS)) { "No live texture frame" }
                Thread.sleep(350)
                val shot=uiAutomation.takeScreenshot() ?: error("No HWUI screenshot")
                val xy=IntArray(2);runOnMainSync { card.getLocationOnScreen(xy) }
                val background=shot.getPixel(xy[0]-10,xy[1]+80)
                val corner=shot.getPixel(xy[0]+1,xy[1]+74)
                val center=shot.getPixel(xy[0]+120,xy[1]+250)
                check(center!=background) { "Live center missing" }
                if(legacy) check(corner!=background) { "Baseline square-corner reproduction missing" }
                else check(corner==background) { "LIVE buffer corner not clipped" }
                File(out,if(legacy) "095-parent-only.png" else "096-live-clip.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) }
                checks.add(if(legacy) "PASS reproduced old square inner buffer" else "PASS actual TextureView live buffer corner clipped")
                shot.recycle()
                if(!legacy) {
                    val frame2=CountDownLatch(1)
                    runOnMainSync {
                        texture.surfaceTextureListener=object: TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture,w: Int,h: Int) {}
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture,w: Int,h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture)=true
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {frame2.countDown()}
                        }
                        producer!!.lockCanvas(null).also {it.drawColor(Color.rgb(45,200,150));producer!!.unlockCanvasAndPost(it)}
                    }
                    check(frame2.await(10,TimeUnit.SECONDS)) {"Second live frame missing"}
                    Thread.sleep(200)
                    val next=uiAutomation.takeScreenshot()!!
                    check(next.getPixel(xy[0]+120,xy[1]+250)!=center) {"Static preview"}
                    check(next.getPixel(xy[0]+1,xy[1]+74)==background) {"Corner lost on next frame"}
                    next.recycle();checks.add("PASS changing producer frames preserve clipping")
                }
            }
            for(top in listOf(false,true)) {
                renderFour(root,activity,out,top)
                checks.add("PASS four ${if(top) "top" else "side"} live buffers, rounded corners and exact compact bounds")
            }
            checks.add(StageSparseRailMotionRegression(this).run(activity,root,out))
            checks.add(StagePreviewBitmapRetentionRegression(this).run(activity,root))
            checks.add(StageChangelogRegression(this).run(activity,root,out))
            checks.add(StageUpdateUiRegression(this).run(activity,root,out))
            checks.addAll(InteractionRegression(this).run(activity,root,out))
            checks.addAll(RefinementRegression(this).run(activity,root,out))
            checks.add(PhasedPreviewRegression(this).run(activity,root))
            checks.addAll(TaskMigrationRegression(this).run(activity,out))
            checks.addAll(ShadeRegression(this).run(activity,root,out))
            File(out,"render-results.txt").writeText(checks.joinToString("\n")+"\nHWUI_RENDER_PASS\n")
            result.putString("stream",checks.joinToString("\n")+"\nHWUI_RENDER_PASS\n")
            runOnMainSync { activity.finish() }
            finish(Activity.RESULT_OK,result)
        } catch(t: Throwable) { result.putString("stream",t.stackTraceToString());finish(Activity.RESULT_CANCELED,result) }
    }
    private fun coldBootstrap(root: FrameLayout, activity: Activity, out: File): String {
        val before=java.util.concurrent.atomic.AtomicInteger()
        runOnMainSync {
            root.removeAllViews()
            repeat(4) {
                val hidden=object: FrameLayout(activity) { override fun dispatchDraw(c: Canvas) {} }.apply {alpha=0f}
                val texture=TextureView(activity)
                texture.surfaceTextureListener=object: TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture,w: Int,h: Int) {before.incrementAndGet()}
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture,w: Int,h: Int) {}
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture)=true
                }
                hidden.addView(texture,FrameLayout.LayoutParams(-1,-1))
                root.addView(hidden,FrameLayout.LayoutParams(1,1))
            }
        }
        Thread.sleep(700)
        check(before.get()==0) {"Legacy initialization deadlock was not reproduced"}
        val log=mutableListOf("BEFORE legacy hidden Empty=4 surfaceAvailable=${before.get()} ready=0; old indexOfReady=-1 incorrectly meant full")
        val ready=CountDownLatch(4)
        val slots=mutableListOf<io.github.x647368maker.atomicworkbench.display.VirtualDisplaySlot>()
        val failures=mutableListOf<String>()
        uiAutomation.adoptShellPermissionIdentity()
        runOnMainSync {
            root.removeAllViews()
            val p=WorkspaceLayoutProfile.create(1272,2772,512,WorkspaceNavigationInsets(top=108,bottom=96))
            repeat(4) { i ->
                val slot=io.github.x647368maker.atomicworkbench.display.VirtualDisplaySlot(activity,i)
                slot.setLayoutProfile(p)
                slot.view.setWorkspaceCardVisible(false)
                slot.setListener(object: io.github.x647368maker.atomicworkbench.display.VirtualDisplaySlot.Listener {
                    override fun onDisplayReady(index: Int,id: Int) {log.add("AFTER slot=$index state=Ready display=$id surface=true hiddenHost=1x1 task=none");ready.countDown()}
                    override fun onDisplayReleased(index: Int,id: Int?,reason: String) {log.add("RELEASE slot=$index display=$id reason=$reason")}
                    override fun onDisplayFailed(index: Int,message: String) {failures.add("slot=$index $message")}
                })
                slots.add(slot);root.addView(slot.view,FrameLayout.LayoutParams(1,1));slot.activate()
            }
        }
        check(ready.await(20,TimeUnit.SECONDS)) {"Hidden producer startup failed: $failures"}
        val ids=slots.map{it.displayId()}
        check(ids.filterNotNull().distinct().size==4) {"Four distinct real displays were not created"}
        runOnMainSync {slots.forEach {it.setSuspended(true);it.setSuspended(false)}}
        check(ids==slots.map{it.displayId()}) {"Suspend/resume changed display identity"}
        log.add("PASS four real VirtualDisplays cold-start behind hidden 1x1 hosts; suspend/resume preserved IDs=$ids")
        runOnMainSync {slots.forEach {it.deactivate()};root.removeAllViews()}
        val reopened=CountDownLatch(4)
        runOnMainSync {
            slots.forEachIndexed {i,slot ->
                slot.setListener(object: io.github.x647368maker.atomicworkbench.display.VirtualDisplaySlot.Listener {
                    override fun onDisplayReady(index: Int,id: Int) {log.add("REENTER slot=$index state=Ready display=$id texture=true task=none");reopened.countDown()}
                    override fun onDisplayReleased(index: Int,id: Int?,reason: String) {}
                    override fun onDisplayFailed(index: Int,message: String) {failures.add(message)}
                })
                root.addView(slot.view,FrameLayout.LayoutParams(1,1));slot.activate()
            }
        }
        check(reopened.await(20,TimeUnit.SECONDS)) {"Reenter failed: $failures"}
        check(slots.all{it.displayId()!=null && it.view.textureView.isAvailable})
        runOnMainSync {slots.forEach{it.deactivate()};root.removeAllViews()}
        uiAutomation.dropShellPermissionIdentity()
        File(out,"cold-bootstrap.txt").writeText(log.joinToString("\n"))
        return "PASS cold Empty -> four Ready real displays, zero painted placeholders, exit/reenter and suspend/resume"
    }
    private fun renderFour(root: FrameLayout, activity: Activity, out: File, top: Boolean) {
        val ready=CountDownLatch(4)
        val producers=mutableListOf<Surface>()
        val cards=mutableListOf<TaskSwitcherView>()
        val p0=WorkspaceLayoutProfile.create(1272,2772,512,WorkspaceNavigationInsets(bottom=96))
        val toolbar=p0.geometry.copy(topHeight=307)
        val g=StageAdaptiveLayout.rail(if(top) toolbar.copy(railAtTop=true,sidebarWidth=StageTopLayoutMetrics.depth(toolbar,3.2f,4))
            else toolbar,4,3.2f)
        val p=p0.copy(geometry=g,activeCount=4)
        val sources=if(top) List(4) {p.canvas} else listOf(SlotGeometry(720,1120,320),p.canvas,SlotGeometry(720,1120,320),p.canvas)
        val boxes=StageLiveCardLayout.bounds(p,sources,List(4){true},listOf(0,1,2,3))
        runOnMainSync {
            root.removeAllViews()
            for(i in 0..3) {
                val box=boxes[i];val source=sources[i]
                val card=TaskSwitcherView(activity,i)
                for(c in 0 until card.childCount) if(card.getChildAt(c) is TextView) card.getChildAt(c).visibility=View.GONE
                card.textureView.alpha=1f // Occupied test producer.
                cards.add(card)
                root.addView(card,FrameLayout.LayoutParams(box.width,box.height).apply {
                    leftMargin=if(top) box.left else (g.sidebarWidth-box.width)/2
                    topMargin=g.sidebarTop+box.top
                })
                var first=true
                card.textureView.surfaceTextureListener=object: TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture,w: Int,h: Int) {
                        st.setDefaultBufferSize(source.width,source.height)
                        val scale=minOf(w.toFloat()/source.width,h.toFloat()/source.height)
                        val x=(w-source.width*scale)/2;val y=(h-source.height*scale)/2
                        card.textureView.setTransform(Matrix().apply {setScale(source.width*scale/w,source.height*scale/h);postTranslate(x,y)})
                        card.setLivePreviewBounds(x,y,w-x,h-y)
                        val producer=Surface(st);producers.add(producer)
                        producer.lockCanvas(null).also { canvas ->
                            canvas.drawColor(if(i%2==0) Color.rgb(238,185,40) else Color.rgb(45,200,150))
                            val paint=android.graphics.Paint().apply {color=Color.rgb(40,60,100);textSize=60f;isAntiAlias=true}
                            canvas.drawText("LIVE ${i+1}",70f,140f,paint)
                            producer.unlockCanvasAndPost(canvas)
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture,w: Int,h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture)=true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {if(first){first=false;ready.countDown()}}
                }
            }
        }
        check(ready.await(15,TimeUnit.SECONDS)){"Four live producers did not render"}
        Thread.sleep(300)
        val shot=uiAutomation.takeScreenshot() ?: error("No four-card HWUI screenshot")
        val bg=Color.rgb(20,32,70)
        cards.forEach { card ->
            val xy=IntArray(2);runOnMainSync {card.getLocationOnScreen(xy)}
            check(shot.getPixel(xy[0]+1,xy[1]+1)==bg){"Four-card corner not rounded"}
            check(shot.getPixel(xy[0]+card.width/2,xy[1]+card.height/2)!=bg){"Four-card live center missing"}
        }
        File(out,if(top) "096-top-four-live.png" else "096-side-four-live.png").outputStream().use {shot.compress(Bitmap.CompressFormat.PNG,100,it)}
        shot.recycle()
        runOnMainSync {
            check(cards.all {it.hasVisibleContent()}) {"Visible fixture cards not recognized"}
            root.alpha=0f
            check(cards.none {it.hasVisibleContent()}) {"Hidden parent incorrectly counts as visible capacity"}
            root.alpha=1f
            root.removeAllViews();producers.forEach {it.release()}
        }
    }

}
