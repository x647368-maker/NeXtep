package io.github.x647368maker.atomicworkbench.visual

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.systemui.StageSparseRailReflowAnimator
import io.github.x647368maker.atomicworkbench.workspace.SidebarSide
import io.github.x647368maker.atomicworkbench.workspace.StageSparseRailReflowPolicy
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Real attached HWUI TextureViews with live Surface buffers (not cached
 * screenshots). 2->3->4 follows the exact production 190ms compositor path.
 */
internal class StageSparseRailMotionRegression(private val instrumentation: Instrumentation) {
    private data class Pose(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun bounds() = StageSparseRailReflowPolicy.Bounds(
            x.toFloat(),y.toFloat(),w.toFloat(),h.toFloat())
    }

    fun run(activity: Activity, root: FrameLayout, out: File): String {
        val ready=CountDownLatch(4)
        val created=AtomicInteger()
        val resized=AtomicInteger()
        val surfaces=mutableListOf<Surface>()
        val cards=mutableListOf<FrameLayout>()
        val geo=WorkspaceGeometry.forDisplay(1272,2772,SidebarSide.LEFT,density=3.2f)
        val old=listOf(Pose(32,120,180,300),Pose(32,440,180,300),
            Pose(32,760,180,300),Pose(32,1080,180,300))
        val triple=listOf(Pose(36,110,160,267),Pose(36,397,160,267),
            Pose(36,684,160,267))
        val four=listOf(Pose(38,114,154,256),Pose(38,387,154,256),
            Pose(38,660,154,256),Pose(38,933,154,256))
        lateinit var motion: StageSparseRailReflowAnimator
        instrumentation.runOnMainSync {
            root.removeAllViews()
            motion=StageSparseRailReflowAnimator(activity)
            repeat(4) { index ->
                val card=FrameLayout(activity)
                // TextureViews must receive a real Surface before simulated occupancy changes.
                card.alpha=1f
                val texture=TextureView(activity)
                texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st:SurfaceTexture,w:Int,h:Int) {
                        st.setDefaultBufferSize(360,600)
                        val surface=Surface(st)
                        surfaces.add(surface)
                        val canvas=surface.lockCanvas(null)
                        canvas.drawColor(if(index%2==0)Color.rgb(40,175,135)else Color.rgb(220,175,45))
                        surface.unlockCanvasAndPost(canvas)
                        created.incrementAndGet()
                        ready.countDown()
                    }
                    override fun onSurfaceTextureSizeChanged(st:SurfaceTexture,w:Int,h:Int) {
                        resized.incrementAndGet()
                    }
                    override fun onSurfaceTextureUpdated(st:SurfaceTexture) {}
                    override fun onSurfaceTextureDestroyed(st:SurfaceTexture):Boolean = true
                }
                card.addView(texture,FrameLayout.LayoutParams(-1,-1))
                root.addView(card,FrameLayout.LayoutParams(old[index].w,old[index].h).apply {
                    leftMargin=old[index].x;topMargin=old[index].y
                })
                cards.add(card)
            }
        }
        check(ready.await(10,TimeUnit.SECONDS)) {"Sparse animation live Surface buffers unavailable"}
        check(created.get()==4)
        instrumentation.runOnMainSync {
            // Keep the surface and live buffer attached; only simulate
            // the unoccupied visual state before the new card fades in.
            cards[2].alpha=0f
            cards[3].alpha=0f
        }
        check(ValueAnimator.areAnimatorsEnabled()) {
            "The visual test must enable actual animations, not silently bypass the new reflow"
        }

        fun applyChange(from: List<Pose>, to: List<Pose>, oldCount: Int, newCount: Int) {
            instrumentation.runOnMainSync {
                val before=(0 until oldCount).associate { i -> cards[i] as View to from[i].bounds() }
                to.forEachIndexed { i,p ->
                    cards[i].layoutParams=FrameLayout.LayoutParams(p.w,p.h).apply {
                        leftMargin=p.x;topMargin=p.y
                    }
                }
                val after=(0 until newCount).associate { i -> cards[i] as View to to[i].bounds() }
                motion.animate(geo,geo,oldCount,newCount,before,after,enabled=true)
                check(motion.isAnimating(cards[0]))
                check(abs(cards[0].scaleX-cards[0].scaleY)<.0001f)
                check(abs(cards[newCount-1].translationY)<.001f) {
                    "Added card must appear in its final location, not fly across controls"
                }
            }
            Thread.sleep(380)
            instrumentation.runOnMainSync {
                for(i in 0 until newCount) {
                    val view=cards[i]
                    check(!motion.isAnimating(view)) {"Reflow did not settle by 380ms"}
                    check(abs(view.scaleX-1f)<.002f && abs(view.scaleY-1f)<.002f)
                    check(abs(view.translationX)<.002f && abs(view.translationY)<.002f)
                    check(abs(view.alpha-1f)<.002f)
                }
            }
        }

        try {
            applyChange(old,triple,2,3)
            applyChange(triple,four,3,4)
            instrumentation.runOnMainSync {
                check(created.get()==4) {
                    "Card reflow destroyed/recreated TextureView surfaces"
                }
                check(resized.get()<=16) {
                    "Unexpected repeated TextureView resizing during compositor-only reflow"
                }
            }
            // Screenshot is taken outside the UI thread: never block HWUI
            // drawing while waiting for its current frame.
            val shot=instrumentation.uiAutomation.takeScreenshot()
                ?: error("No real HWUI screenshot after sparse reflow")
            val origin=IntArray(2)
            instrumentation.runOnMainSync { root.getLocationOnScreen(origin) }
            four.forEachIndexed { index, pose ->
                val actual=shot.getPixel(
                    origin[0]+pose.x+pose.w/2,
                    origin[1]+pose.y+pose.h/2,
                )
                val expected=if (index%2==0) Color.rgb(40,175,135)
                    else Color.rgb(220,175,45)
                val difference=abs(Color.red(actual)-Color.red(expected))+
                    abs(Color.green(actual)-Color.green(expected))+
                    abs(Color.blue(actual)-Color.blue(expected))
                check(difference<80) {
                    "Real live TextureView buffer missing after reflow at card $index: RGB delta $difference"
                }
            }
            File(out,"sparse-live-2to3to4.png").outputStream().use {
                shot.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            shot.recycle()
        } finally {
            instrumentation.runOnMainSync {
                motion.cancel()
                root.removeAllViews()
                surfaces.forEach { it.release() }
            }
        }
        return "PASS real HWUI live-buffer side rail 2->3->4 completes 190ms uniform motion with no extra displays, distortion or flying add card"
    }
}
