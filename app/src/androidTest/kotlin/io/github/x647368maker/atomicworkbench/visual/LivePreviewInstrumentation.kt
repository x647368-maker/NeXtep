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
                            override fun getOutline(v: View,o: Outline) { o.setRoundRect(0,0,v.width,v.height,16f) }
                        }
                    } else TaskSwitcherView(activity,0).apply {
                        for(i in 0 until childCount) if(getChildAt(i) is TextView) getChildAt(i).visibility=View.GONE
                    }
                    texture=if(legacy) TextureView(activity).also { card.addView(it,FrameLayout.LayoutParams(-1,-1)) }
                        else (card as TaskSwitcherView).textureView
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
            File(out,"render-results.txt").writeText(checks.joinToString("\n")+"\nHWUI_RENDER_PASS\n")
            result.putString("stream",checks.joinToString("\n")+"\nHWUI_RENDER_PASS\n")
            runOnMainSync { activity.finish() }
            finish(Activity.RESULT_OK,result)
        } catch(t: Throwable) { result.putString("stream",t.stackTraceToString());finish(Activity.RESULT_CANCELED,result) }
    }
}
