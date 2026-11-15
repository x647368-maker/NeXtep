package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.systemui.StageTransientTapSwap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Test-only: actual HWUI first commit and owner dispatch, synthetic pixels.
 * No capture, real task migration, OEM GPU equivalence or FPS claim.
 */
internal class StageFirstCommitResolutionProbe(private val i:Instrumentation) {
    fun run(activity:Activity,host:FrameLayout,out:File):String {
        val report=File(out,"first-commit-resolution.txt")
        val lines=mutableListOf<String>()
        fun record(s:String){lines.add(s);report.writeText(lines.joinToString("\n")+"\n")}
        val owner=Handler(Looper.getMainLooper())
        val metrics=android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getRealMetrics(metrics)
        i.waitForIdleSync()
        val w=host.width
        val h=host.height
        check(w>200 && h>300)
        record("profile display=${metrics.widthPixels}x${metrics.heightPixels} host=${w}x${h} density=${metrics.densityDpi} refresh_hz=${activity.display?.refreshRate}")
        record("scope: same production FlightView, synthetic marker cards, initial HWUI commit callback vs owner post; no actual first physical pixel, no task capture/migration")
        fun box(l:Float,t:Float,r:Float,b:Float)=Rect((w*l).toInt(),(h*t).toInt(),(w*r).toInt(),(h*b).toInt())
        val small=box(.08f,.06f,.24f,.23f)
        val main=box(.20f,.27f,.80f,.90f)
        val other=box(.76f,.06f,.94f,.23f)
        val green=Color.rgb(34,194,102)
        val orange=Color.rgb(239,119,39)
        val purple=Color.rgb(91,50,167)
        val samples=mutableListOf<Long>()
        val dispatches=mutableListOf<Long>()
        var failures=0
        repeat(22){ round ->
            val measured=round>=2
            val a=Bitmap.createBitmap(small.width(),small.height(),Bitmap.Config.ARGB_8888).apply{eraseColor(green)}
            val b=Bitmap.createBitmap(main.width(),main.height(),Bitmap.Config.ARGB_8888).apply{eraseColor(orange)}
            val done=CountDownLatch(1)
            val live=AtomicBoolean(true)
            var flight:StageTransientTapSwap.FlightView?=null
            var attachStart=0L
            var registered=0L
            var callbackAt=0L
            var ownerAt=0L
            var accepted=false
            var callback:Runnable?=null
            val retired=CountDownLatch(1)
            try {
                i.runOnMainSync {
                    host.removeAllViews()
                    host.setBackgroundColor(purple)
                    val v=StageTransientTapSwap.FlightView(activity,a,b,small,main,listOf(other),
                        ColorDrawable(Color.rgb(89,143,168)),Rect(0,0,w,h),
                        mainBackdrop=ColorDrawable(Color.rgb(28,88,165)))
                    flight=v
                    check(v.safe)
                    attachStart=SystemClock.uptimeMillis()
                    host.addView(v,FrameLayout.LayoutParams(-1,-1))
                    callback=Runnable {
                        val delivered=SystemClock.uptimeMillis()
                        owner.post {
                            if(live.compareAndSet(true,false)){
                                callbackAt=delivered
                                ownerAt=SystemClock.uptimeMillis()
                                accepted=v.drawn && v.isAttachedToWindow
                                done.countDown()
                            }
                        }
                    }
                    registered=SystemClock.uptimeMillis()
                    host.viewTreeObserver.registerFrameCommitCallback(checkNotNull(callback))
                    v.invalidate()
                    host.invalidate()
                }
                val returned=done.await(2,TimeUnit.SECONDS)
                // Do not convert a timeout into a successful frame.
                if(!returned || !accepted){
                    if(measured)failures++
                    record("attempt=$round warmup=${!measured} result=${if(!returned)"callback_missing" else "draw_not_verified"}")
                }else{
                    val commit=callbackAt-registered
                    val dispatch=ownerAt-callbackAt
                    val total=ownerAt-attachStart
                    check(commit>=0 && dispatch>=0 && total>=0)
                    record("attempt=$round warmup=${!measured} result=committed register_to_callback_ms=$commit callback_to_owner_ms=$dispatch attach_to_owner_ms=$total")
                    if(measured){samples.add(total);dispatches.add(dispatch)}
                    // Independent screenshot smoke once, OUTSIDE the timings.
                    if(round==2){
                        val xy=IntArray(2);i.runOnMainSync{host.getLocationOnScreen(xy)}
                        val shot=checkNotNull(i.uiAutomation.takeScreenshot())
                        try {
                            fun similar(actual:Int,expected:Int)=listOf(
                                kotlin.math.abs(Color.red(actual)-Color.red(expected)),
                                kotlin.math.abs(Color.green(actual)-Color.green(expected)),
                                kotlin.math.abs(Color.blue(actual)-Color.blue(expected))).all{it<22}
                            check(similar(shot.getPixel(xy[0]+small.centerX(),xy[1]+small.centerY()),green))
                            check(similar(shot.getPixel(xy[0]+main.centerX(),xy[1]+main.centerY()),orange))
                            check(similar(shot.getPixel(xy[0]+other.centerX(),xy[1]+other.centerY()),purple))
                            record("pixel_smoke=verified")
                        }finally{shot.recycle()}
                    }
                }
            }finally{
                live.set(false)
                val v=flight
                if(v!=null){
                    i.runOnMainSync{
                        callback?.let{cb->runCatching{host.viewTreeObserver.unregisterFrameCommitCallback(cb)}}
                        host.removeView(v)
                        // Wait for an actual removal commit before recycling pixels.
                        host.viewTreeObserver.registerFrameCommitCallback(Runnable{retired.countDown()})
                        host.invalidate()
                    }
                    check(retired.await(2,TimeUnit.SECONDS)){"Removal commit missing; stop isolated probe, do not recycle in-flight buffers"}
                    i.runOnMainSync{v.release()}
                }else{a.recycle();b.recycle()}
            }
            Thread.sleep(100L)
        }
        fun stats(v:List<Long>):String {
            if(v.isEmpty())return "n=0"
            val sorted=v.sorted()
            val p50=if(v.size%2==0)(sorted[v.size/2-1]+sorted[v.size/2])/2.0 else sorted[v.size/2].toDouble()
            return "n=${v.size} p50=$p50 p95=${sorted[kotlin.math.ceil(.95*v.size).toInt()-1]} max=${sorted.last()}"
        }
        record("summary failures=$failures total_ms ${stats(samples)} dispatch_ms ${stats(dispatches)}")
        check(samples.size+failures==20)
        // The probe may report a missing commit, but never declares performance pass.
        return "First commit resolution probe captured 20 attempts, failures=$failures; see first-commit-resolution.txt"
    }
}
