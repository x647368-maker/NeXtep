package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import io.github.x647368maker.atomicworkbench.systemui.SwapPreviewView
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.systemui.WorkspaceSwapAnimator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercise the production async animation contract on a real attached HWUI host. */
internal class PhasedPreviewRegression(private val instrumentation: Instrumentation) {
    fun run(activity: Activity, host: FrameLayout): String {
        val requested = CountDownLatch(1)
        val finished = CountDownLatch(1)
        lateinit var animator: WorkspaceSwapAnimator
        lateinit var complete: (Boolean) -> Unit
        lateinit var source: Bitmap
        lateinit var target: Bitmap
        var releaseAllowed = false
        var completionCount = 0
        val afterRollback = AtomicReference("rollback not invoked")
        instrumentation.runOnMainSync {
            animator = WorkspaceSwapAnimator(activity)
            source = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
            target = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
            animator.animate(source, target, RectF(0f, 0f, 100f, 200f),
                RectF(0f, 0f, 100f, 200f), RectF(100f, 100f, 400f, 700f),
                host, ColorDrawable(Color.rgb(29,42,53)), null, 200, false, null, null,
                move = { error("Async exchange used the synchronous move") },
                isLiveReady = { true },
                moveAsync = { done -> complete = done; requested.countDown() },
                canRelease = { releaseAllowed },
                freezeGeometry = true, // Exercise the new 0.9.16 no-morph rendering path.
                onFinished = { completionCount++; finished.countDown() })
        }
        check(requested.await(6, TimeUnit.SECONDS)) { "Async exchange did not start" }
        try {
            instrumentation.runOnMainSync {
                check(animator.isRunning && completionCount == 0)
                animator.cancel()
                check(animator.isRunning && !source.isRecycled && !target.isRecycled) {
                    "Pending exchange lost its preview protection"
                }
                releaseAllowed = true
                complete(false)
                val summary = "running=${animator.isRunning} count=$completionCount allowed=$releaseAllowed " +
                    "finished.count=${finished.count} sourceRecycled=${source.isRecycled} targetRecycled=${target.isRecycled}"
                afterRollback.set(summary)
                android.util.Log.i("StagePhasedRegression", "After rollback callback: $summary")
            }
            // The async completion callback is allowed to finish on a posted frame.
            // Require eventual release instead of crashing the instrumentation target
            // with a same-main-loop synchronous assertion before that frame can run.
            check(finished.await(3, TimeUnit.SECONDS)) {
                "Rollback preview did not settle within 3 seconds; afterRollback=${afterRollback.get()}"
            }
            instrumentation.runOnMainSync {
                check(!animator.isRunning && completionCount == 1) {
                    "Failed rollback preview release: running=${animator.isRunning} " +
                        "completionCount=$completionCount releaseAllowed=$releaseAllowed " +
                        "sourceRecycled=${source.isRecycled} targetRecycled=${target.isRecycled}"
                }
                check(source.isRecycled && target.isRecycled)
                animator.cancel()
                check(completionCount == 1)
            }
        } finally {
            instrumentation.runOnMainSync { releaseAllowed = true; animator.cancel() }
        }
        verifyUnpairedCorners(activity)
        verifyOpaqueLetterboxAndProtectedNeighbors(activity)
        verifyUnavailableBackdropFallsBackOnce(activity,host)
        return "PASS production async preview retains protection during pending/cancel and releases exactly once after rollback completion\n"+
            "PASS actual fallback drawing: unpaired dual/source-only/fixed corners use explicit endpoint roles and preserve contain"
    }

    // Rasterize the exact production View with no admitted PairFlight.
    // This is a deterministic renderer contract, not an OEM task handoff.
    private fun verifyUnpairedCorners(activity:Activity) {
        instrumentation.runOnMainSync {
            val unit=16f*activity.resources.displayMetrics.density
            val small=RectF(12f,12f,12f+5f*unit,12f+7f*unit)
            val main=RectF(12f+6f*unit,12f,12f+18f*unit,12f+16f*unit)
            val bg=Color.rgb(91,50,167)
            val green=Color.rgb(34,194,102)
            val orange=Color.rgb(239,119,39)
            val raster=Bitmap.createBitmap(kotlin.math.ceil(main.right+12).toInt(),
                kotlin.math.ceil(main.bottom+12).toInt(),Bitmap.Config.ARGB_8888)
            try {
                for((dual,frozen) in listOf(true to false,false to false,true to true,false to true)){
                    val source=Bitmap.createBitmap(100,140,Bitmap.Config.ARGB_8888).apply{eraseColor(green)}
                    val target=if(dual) Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888).apply{eraseColor(orange)} else null
                    val v=SwapPreviewView(activity,source,target,small,small,main,ColorDrawable(bg),null,
                        null,null,frozen,!frozen,null,emptyList(),true,false)
                    try {
                        v.layout(0,0,raster.width,raster.height)
                        fun draw(p:Float){
                            raster.eraseColor(bg);v.progress=p;v.draw(Canvas(raster))
                        }
                        fun read(x:Float,y:Float)=raster.getPixel(x.toInt(),y.toInt())
                        fun clippedSlot(){
                            val d=unit*.15f
                            check(read(small.left+d,small.top+d)==bg){"Small corner square: dual=$dual frozen=$frozen"}
                        }
                        draw(0f)
                        clippedSlot()
                        check(read(small.centerX(),small.centerY())==green)
                        if(dual)check(read(main.centerX(),main.centerY())==orange)
                        draw(1f)
                        if(frozen || dual)clippedSlot()
                        if(frozen) check(read(small.centerX(),small.centerY())==green)
                        else {
                            check(read(main.centerX(),main.centerY())==green)
                            if(dual)check(read(small.centerX(),small.centerY())==orange)
                        }
                    } finally { v.release() }
                }
            } finally { raster.recycle() }
        }
    }

    // Probe the production SwapPreviewView, not a duplicate policy renderer.
    private fun verifyOpaqueLetterboxAndProtectedNeighbors(activity:Activity) {
        instrumentation.runOnMainSync {
            val backing=Color.rgb(74,130,182)
            val untouched=Color.rgb(192,155,42)
            val sourceColor=Color.rgb(24,202,108)
            val from=RectF(24f,40f,124f,240f)
            val to=RectF(240f,40f,560f,300f)
            val neighbor=RectF(565f,42f,600f,120f)
            val raster=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888)
            val source=Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888).apply { eraseColor(sourceColor) }
            val preview=SwapPreviewView(activity,source,null,from,from,to,
                ColorDrawable(backing),null,null,null,false,true,null,
                listOf(neighbor),true,false)
            try {
                preview.layout(0,0,raster.width,raster.height)
                raster.eraseColor(untouched)
                preview.progress=1f
                preview.draw(Canvas(raster))
                check(raster.getPixel(252,170)==backing) {
                    "Portrait source into landscape produced a non-wallpaper letterbox"
                }
                check(raster.getPixel(400,170)==sourceColor) {
                    "Source content was stretched, cropped or missing at destination"
                }
                check(raster.getPixel(580,65)==untouched) {
                    "Protected neighboring card changed during Canvas exchange"
                }
                check(raster.getPixel(150,330)==untouched) {
                    "Non-participating control area changed"
                }
                // A legitimate all-black app frame is not a failed preview.
                val black=Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(Color.BLACK) }
                val blackPreview=SwapPreviewView(activity,black,null,from,from,to,
                    ColorDrawable(backing),null,null,null,false,true,null,
                    listOf(neighbor),true,false)
                try {
                    raster.eraseColor(untouched)
                    blackPreview.layout(0,0,raster.width,raster.height)
                    blackPreview.progress=1f
                    blackPreview.draw(Canvas(raster))
                    check(raster.getPixel(400,170)==Color.BLACK &&
                        raster.getPixel(252,170)==backing) {
                        "Valid black task content was misclassified as an artificial fill"
                    }
                } finally { blackPreview.release() }
            } finally {
                preview.release()
                raster.recycle()
            }
        }
    }

    private fun verifyUnavailableBackdropFallsBackOnce(activity:Activity,host:FrameLayout) {
        instrumentation.runOnMainSync {
            check(host.isAttachedToWindow && host.isHardwareAccelerated) {
                "Fallback evidence requires an otherwise capable HWUI preview host"
            }
            for(background in listOf(null,ColorDrawable(Color.TRANSPARENT))) {
                val source=Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888)
                val animator=WorkspaceSwapAnimator(activity)
                var moves=0
                var finishes=0
                animator.animate(source,null,RectF(20f,20f,120f,220f),
                    RectF(20f,20f,120f,220f),RectF(200f,20f,560f,320f),
                    host,background,null,200,false,null,null,
                    move={moves++;true},isLiveReady={true},
                    onFinished={finishes++})
                check(!animator.isRunning && source.isRecycled && moves==1 && finishes==1) {
                    "Missing/transparent background leaked preview or repeated task move"
                }

                // When the real move is asynchronous the visual rejection
                // must neither dispatch a second move nor complete twice.
                val asyncSource=Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888)
                var asyncMoves=0
                var asyncFinishes=0
                val asyncPreview=WorkspaceSwapAnimator(activity)
                asyncPreview.animate(asyncSource,null,RectF(20f,20f,120f,220f),
                    RectF(20f,20f,120f,220f),RectF(200f,20f,560f,320f),
                    host,background,null,200,false,null,null,
                    move={error("Visual rejection used sync move")},
                    isLiveReady={true},
                    moveAsync={ done ->
                        asyncMoves++
                        done(true)
                        done(false) // Simulated duplicate late ACK: must be ignored.
                    },
                    onFinished={asyncFinishes++})
                check(!asyncPreview.isRunning && asyncSource.isRecycled &&
                    asyncMoves==1 && asyncFinishes==1) {
                    "Visual rejection duplicated async move or completion"
                }

                val throwingSource=Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888)
                var failuresFinished=0
                val throwingPreview=WorkspaceSwapAnimator(activity)
                throwingPreview.animate(throwingSource,null,RectF(20f,20f,120f,220f),
                    RectF(20f,20f,120f,220f),RectF(200f,20f,560f,320f),
                    host,background,null,200,false,null,null,
                    move={error("Visual rejection used sync move")},
                    isLiveReady={true},
                    moveAsync={ throw IllegalStateException("Injected async submit failure") },
                    onFinished={failuresFinished++})
                check(!throwingPreview.isRunning && throwingSource.isRecycled &&
                    failuresFinished==1) {
                    "Async submission failure leaked visual completion"
                }
            }
        }
    }
}
