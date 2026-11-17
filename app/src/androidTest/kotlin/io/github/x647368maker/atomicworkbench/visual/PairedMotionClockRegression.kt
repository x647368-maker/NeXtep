package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import io.github.x647368maker.atomicworkbench.systemui.ExchangeMotionPlan
import io.github.x647368maker.atomicworkbench.systemui.StageSnapshotMotion
import io.github.x647368maker.atomicworkbench.systemui.StageTransientTapSwap
import io.github.x647368maker.atomicworkbench.systemui.SwapPreviewView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * P0-2a API35/37 fixture. Samples both REAL production preview classes with
 * the SAME admitted PairFlight. This does not fake a ColorOS task migration or
 * certify presentation. In particular, it does not claim RT outline is synced
 * to SurfaceFlinger; that is the separate P0-5 display-frame gate.
 */
internal object PairedMotionClockRegression {
    fun run(activity: Activity, instrumentation: Instrumentation): String {
        var report = "P0-2a clock fixture not executed"
        instrumentation.runOnMainSync { report = verifyOnMain(activity) }
        return report
    }

    private fun verifyOnMain(activity: Activity): String {
        val small=StageSnapshotMotion.Box(16f,85f,116f,285f)
        val main=StageSnapshotMotion.Box(140f,45f,520f,675f)
        val incomingW=100
        val incomingH=200
        val outgoingW=240
        val outgoingH=480
        val up=checkNotNull(StageSnapshotMotion.flight(small,main,incomingW,incomingH))
        val down=checkNotNull(StageSnapshotMotion.flight(main,small,outgoingW,outgoingH))
        val pair=StageSnapshotMotion.PairFlight(up,down)
        val plan=checkNotNull(ExchangeMotionPlan.fromExistingPair(pair))
        check(plan.clockMode==ExchangeMotionPlan.ClockMode.PAIRED_CUBIC)

        fun bitmap(width:Int,height:Int,color:Int)=
            Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val canvas=SwapPreviewView(activity,bitmap(incomingW,incomingH,Color.GREEN),
            bitmap(outgoingW,outgoingH,Color.CYAN),
            RectF(small.left,small.top,small.right,small.bottom),
            RectF(small.left,small.top,small.right,small.bottom),
            RectF(main.left,main.top,main.right,main.bottom),
            ColorDrawable(Color.BLUE),null,null,null,
            false,true,pair,emptyList(),true,false)
        val rt=StageTransientTapSwap.FlightView(activity,
            bitmap(incomingW,incomingH,Color.GREEN),
            bitmap(outgoingW,outgoingH,Color.CYAN),
            Rect(small.left.toInt(),small.top.toInt(),small.right.toInt(),small.bottom.toInt()),
            Rect(main.left.toInt(),main.top.toInt(),main.right.toInt(),main.bottom.toInt()),
            emptyList(),ColorDrawable(Color.BLUE),Rect(0,0,600,720),planned=pair)
        val raster=Bitmap.createBitmap(600,720,Bitmap.Config.ARGB_8888)
        var cases=0
        try {
            check(rt.safe)
            canvas.layout(0,0,600,720)
            for (k in 0..120) {
                val raw=k/120f
                val a=canvas.sampleGeometryProgress(raw)
                val b=rt.sampleGeometryProgress(raw)
                val expected=plan.geometryProgress(raw)
                check(abs(a-b) < 0.000005f && abs(a-expected) < 0.000005f) {
                    "Canvas / RT paired clock drift at frame $k: $a vs $b vs $expected"
                }
                val inPose=pair.up.pose(a)
                val outPose=pair.down.pose(b)
                // Check actual production CardView transform properties, not
                // just two calls to the same plan helper.
                rt.progress=raw
                val incomingView=rt.getChildAt(1)
                val outgoingView=rt.getChildAt(0)
                check(abs(incomingView.translationX-(inPose.cx-incomingW*.5f))<.01f &&
                    abs(incomingView.translationY-(inPose.cy-incomingH*.5f))<.01f &&
                    abs(incomingView.scaleX-inPose.scale)<.00001f &&
                    abs(incomingView.scaleY-inPose.scale)<.00001f)
                check(abs(outgoingView.translationX-(outPose.cx-outgoingW*.5f))<.01f &&
                    abs(outgoingView.translationY-(outPose.cy-outgoingH*.5f))<.01f &&
                    abs(outgoingView.scaleX-outPose.scale)<.00001f &&
                    abs(outgoingView.scaleY-outPose.scale)<.00001f)
                // Assert real Canvas bitmap at the expected incoming center.
                // Incoming is drawn after outgoing, so overlap cannot hide it.
                if(k%30==0) {
                    canvas.progress=raw
                    raster.eraseColor(Color.MAGENTA)
                    canvas.draw(Canvas(raster))
                    val px=inPose.cx.roundToInt().coerceIn(0,raster.width-1)
                    val py=inPose.cy.roundToInt().coerceIn(0,raster.height-1)
                    check(raster.getPixel(px,py)==Color.GREEN) {
                        "Production Canvas incoming frame missed shared-clock pose at $k: x=$px y=$py"
                    }
                }
                check(inPose.scale>0f && outPose.scale>0f &&
                    inPose.cx.isFinite() && outPose.cx.isFinite() &&
                    inPose.cy.isFinite() && outPose.cy.isFinite())
                if (k==0 || k==120) {
                    check(abs(inPose.cx-(if(k==0) small.cx else main.cx))<.001f)
                    check(abs(outPose.cx-(if(k==0) main.cx else small.cx))<.001f)
                }
                cases++
            }
            check(abs(plan.geometryProgress(0f))<.00001f &&
                abs(plan.geometryProgress(1f)-1f)<.00001f)
            check(abs(plan.geometryProgress(.5f)-.5f)>.05f) {
                "Paired cubic unexpectedly became linear"
            }
            check(abs(plan.geometryProgress(Float.NaN))<.00001f) {
                "Non-finite input escaped conservative clock guard"
            }
        } finally {
            canvas.release()
            rt.release()
            raster.recycle()
        }
        return "PASS real Canvas pixels and RT child transforms share $cases paired clock samples; exact endpoints"
    }
}
