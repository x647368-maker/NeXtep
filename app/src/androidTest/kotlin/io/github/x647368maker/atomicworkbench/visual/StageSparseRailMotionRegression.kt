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
        val single=listOf(Pose(30,118,200,334))
        val triple=listOf(Pose(36,110,160,267),Pose(36,397,160,267),
            Pose(36,684,160,267))
        val four=listOf(Pose(38,114,154,256),Pose(38,387,154,256),
            Pose(38,660,154,256),Pose(38,933,154,256))
        lateinit var motion: StageSparseRailReflowAnimator
        instrumentation.runOnMainSync {
            root.removeAllViews()
            motion=StageSparseRailReflowAnimator(activity, trialDevice = true)
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

        fun applyChange(from: List<Pose>, to: List<Pose>,
            oldCount: Int, newCount: Int, settleMs: Long = 380L) {
            instrumentation.runOnMainSync {
                val wasInFlight=motion.isAnimating(cards[0])
                val oldPose=from[0]
                val oldCenterX=oldPose.x+oldPose.w/2f+cards[0].translationX
                val oldCenterY=oldPose.y+oldPose.h/2f+cards[0].translationY
                val oldVisibleWidth=oldPose.w*cards[0].scaleX
                val oldVisibleHeight=oldPose.h*cards[0].scaleY
                val oldAlpha=cards[0].alpha
                val before=(0 until oldCount).associate { i -> cards[i] as View to from[i].bounds() }
                to.forEachIndexed { i,p ->
                    cards[i].layoutParams=FrameLayout.LayoutParams(p.w,p.h).apply {
                        leftMargin=p.x;topMargin=p.y
                    }
                }
                // Occupancy removal hides its live card without releasing the
                // TextureView/producer; readdition reuses the same Surface.
                for(i in newCount until oldCount) cards[i].alpha=0f
                val after=(0 until newCount).associate { i -> cards[i] as View to to[i].bounds() }
                motion.animate(geo,geo,oldCount,newCount,before,after,enabled=true)
                if(wasInFlight) {
                    // This is the regression that caught abrupt 2->3->4
                    // snaps: the *rendered* rectangle must be continuous at
                    // the exact instant an old animation is interrupted.
                    val toPose=to[0]
                    val newCenterX=toPose.x+toPose.w/2f+cards[0].translationX
                    val newCenterY=toPose.y+toPose.h/2f+cards[0].translationY
                    check(abs(newCenterX-oldCenterX)<1.5f &&
                        abs(newCenterY-oldCenterY)<1.5f) {
                        "Rapid reflow teleported the live card center"
                    }
                    check(abs(toPose.w*cards[0].scaleX-oldVisibleWidth)<2.5f &&
                        abs(toPose.h*cards[0].scaleY-oldVisibleHeight)<2.5f) {
                        "Rapid reflow jumped the live card scale"
                    }
                    check(abs(cards[0].alpha-oldAlpha)<.04f) {
                        "Rapid reflow flashed alpha on an existing live task"
                    }
                }
                check(motion.isAnimating(cards[0]))
                check(abs(cards[0].scaleX-cards[0].scaleY)<.0001f)
                if (newCount > oldCount) {
                    check(abs(cards[newCount-1].translationY)<.001f) {
                        "Added card must appear in its final location, not fly across controls"
                    }
                } else if (newCount < oldCount) {
                    check(abs(cards[oldCount-1].alpha)<.01f) {
                        "Removing a task must not briefly flash the old card"
                    }
                }
            }
            Thread.sleep(settleMs)
            instrumentation.runOnMainSync {
                for(i in 0 until newCount) {
                    val view=cards[i]
                    if(settleMs < StageSparseRailReflowPolicy.DURATION_MS) {
                        // Keep a transition active so the next call tests
                        // continuity rather than an already settled card.
                        if(i==0) check(motion.isAnimating(view)) {
                            "Expected a live transition during rapid changes"
                        }
                    } else {
                        check(!motion.isAnimating(view)) {"Reflow did not settle by 380ms"}
                        check(abs(view.scaleX-1f)<.002f && abs(view.scaleY-1f)<.002f)
                        check(abs(view.translationX)<.002f && abs(view.translationY)<.002f)
                        check(abs(view.alpha-1f)<.002f)
                    }
                }
            }
        }

        try {
            // Rapid 2->3->4 must not snap back to nominal layout between
            // one-shot animations. Reverse 4->3->2 and immediately add again.
            applyChange(old,triple,2,3,settleMs=60L)
            applyChange(triple,four,3,4)
            applyChange(four,triple,4,3,settleMs=60L)
            applyChange(triple,old.take(2),3,2,settleMs=60L)
            applyChange(old.take(2),single,2,1)
            applyChange(single,old.take(2),1,2,settleMs=60L)
            applyChange(old.take(2),triple,2,3,settleMs=60L)
            applyChange(triple,four,3,4)
            instrumentation.runOnMainSync {
                check(created.get()==4) {
                    "Card reflow destroyed/recreated TextureView surfaces"
                }
                // Eight occupancy transitions can produce one legitimate
                // host TextureView size notification for each affected card.
                // They must not resize every animation frame or recreate a
                // single producer Surface/VirtualDisplay.
                check(resized.get()<=32) {
                    "Repeated per-frame TextureView resize detected: ${resized.get()}"
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
        return "PASS real HWUI 2->3->4->3->2->1->2->3->4 rapid interruption-safe uniform motion, preserved live buffers, no stale transform or flying add card"
    }
}
