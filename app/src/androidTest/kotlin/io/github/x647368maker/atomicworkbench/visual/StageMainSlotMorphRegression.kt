package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.systemui.WorkspaceSwapAnimator
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * PR47 regression: the real production overlay must visibly enlarge a valid
 * small-window snapshot EVEN IF ColorOS declines to capture the main task.
 * The test renders to actual HWUI screenshots; production always replaces this
 * short-lived image with a genuine task Surface after the existing handoff.
 */
internal class StageMainSlotMorphRegression(private val instrumentation: Instrumentation) {
    fun run(activity: Activity, host: FrameLayout, out: File): String {
        val source=Bitmap.createBitmap(180,300,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(45,192,122))
        }
        val sourceBox=RectF(45f,360f,225f,660f)
        val mainBox=RectF(330f,160f,1060f,2150f)
        val moved=CountDownLatch(1)
        val finished=CountDownLatch(1)
        lateinit var swap: WorkspaceSwapAnimator
        instrumentation.runOnMainSync {
            host.removeAllViews()
            host.setBackgroundColor(Color.rgb(20,22,38))
            swap=WorkspaceSwapAnimator(activity)
            swap.animate(
                source=source,
                target=null, // **main screenshot failure** reproduced explicitly
                start=RectF(sourceBox),
                sourceBounds=RectF(sourceBox),
                targetBounds=mainBox,
                host=host,
                backdrop=null,
                stationaryBackground=null,
                durationMs=300,
                returningHome=false,
                sourceQuad=null,
                targetQuad=null,
                move={ error("Phased tap must not call blocking synchronous task move") },
                isLiveReady={ false }, // hold overlay while HWUI is inspected
                moveAsync={ done -> moved.countDown();done(true) },
                freezeGeometry=false,
                uniformTapMorph=true,
                onFinished={ finished.countDown() },
            )
        }
        try {
            check(moved.await(4,TimeUnit.SECONDS)) {
                "Real task move did not start after first composed frame"
            }
            check(!source.isRecycled) { "Main-slot preview destroyed before a scale-up frame" }
            val origin=IntArray(2)
            instrumentation.runOnMainSync { host.getLocationOnScreen(origin) }
            var grown=false
            var captured:Bitmap?=null
            repeat(5) {
                if(!grown) {
                    Thread.sleep(65L)
                    val shot=instrumentation.uiAutomation.takeScreenshot()
                    if(shot!=null) {
                        var greenOutsideSmall=0
                        for(y in 300..1800 step 95) for(x in 265..1040 step 70) {
                            val px=origin[0]+x;val py=origin[1]+y
                            if(px in 0 until shot.width && py in 0 until shot.height) {
                                val color=shot.getPixel(px,py)
                                if(abs(Color.red(color)-45)<28 &&
                                   abs(Color.green(color)-192)<28 &&
                                   abs(Color.blue(color)-122)<28) greenOutsideSmall++
                            }
                        }
                        if(greenOutsideSmall>2) {
                            grown=true;captured?.recycle();captured=shot
                        } else shot.recycle()
                    }
                }
            }
            check(grown) {
                "PR47 regression: after tapping a small window, no real HWUI " +
                "pixels ever expanded beyond the original card bounds"
            }
            captured?.let { shot ->
                File(out,"main-slot-source-only-morph-3712.png").outputStream().use {
                    shot.compress(Bitmap.CompressFormat.PNG,100,it)
                }
                shot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { swap.cancel() }
        }
        check(finished.await(3,TimeUnit.SECONDS)) { "Morph cleanup did not complete" }
        check(source.isRecycled) { "Scale-up preview leaked after animation handoff" }
        return "PASS HWUI source-only real card: bitmap grows beyond old slot, async move starts after first frame, no stale or leaked preview"
    }
}
