package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.systemui.StageTransientTapSwap

/** Exercise the actual PR79/80 flight renderer, not the legacy drag animator.
 * Synthetic labeled colors make both task directions and unaffected pixels
 * objectively detectable; this is not an OEM task-capture test. */
internal class StageTransientDualFlightRegression(private val instrumentation:Instrumentation) {
    fun run(activity:Activity,host:FrameLayout):String {
        val green=Color.rgb(34,194,102)
        val orange=Color.rgb(239,119,39)
        val purple=Color.rgb(91,50,167)
        lateinit var flight:StageTransientTapSwap.FlightView
        lateinit var small:Rect
        lateinit var main:Rect
        lateinit var other:Rect
        val origin=IntArray(2)
        instrumentation.runOnMainSync {
            host.removeAllViews()
            host.setBackgroundColor(purple)
            host.getLocationOnScreen(origin)
            val w=host.width
            val h=host.height
            check(w>200 && h>300)
            fun box(l:Float,t:Float,r:Float,b:Float)=Rect(
                (w*l).toInt(),(h*t).toInt(),(w*r).toInt(),(h*b).toInt())
            small=box(.08f,.06f,.24f,.23f)
            main=box(.20f,.27f,.80f,.90f)
            other=box(.76f,.06f,.94f,.23f)
            val a=Bitmap.createBitmap(small.width(),small.height(),Bitmap.Config.ARGB_8888).apply{eraseColor(green)}
            val b=Bitmap.createBitmap(main.width(),main.height(),Bitmap.Config.ARGB_8888).apply{eraseColor(orange)}
            flight=StageTransientTapSwap.FlightView(activity,a,b,small,main,listOf(other),null,Rect(0,0,w,h))
            check(flight.safe){"Production flight rejected a separated top rail"}
            host.addView(flight,FrameLayout.LayoutParams(-1,-1))
        }
        fun similar(value:Int,expect:Int):Boolean =
            kotlin.math.abs(Color.red(value)-Color.red(expect))<22 &&
                kotlin.math.abs(Color.green(value)-Color.green(expect))<22 &&
                kotlin.math.abs(Color.blue(value)-Color.blue(expect))<22
        fun frame(progress:Float,verify:(Bitmap)->Unit) {
            instrumentation.runOnMainSync { flight.progress=progress;flight.invalidate() }
            instrumentation.waitForIdleSync()
            Thread.sleep(100)
            val shot=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try{verify(shot)}finally{shot.recycle()}
        }
        fun pixel(shot:Bitmap,x:Int,y:Int)=shot.getPixel(origin[0]+x,origin[1]+y)
        try {
            frame(0f){shot->
                check(similar(pixel(shot,small.centerX(),small.centerY()),green))
                check(similar(pixel(shot,main.centerX(),main.centerY()),orange))
                check(similar(pixel(shot,other.centerX(),other.centerY()),purple))
            }
            frame(.45f){shot->
                var up=0;var down=0
                for(y in small.top until main.bottom step 12)
                    for(x in small.left until main.right step 12){
                        val c=pixel(shot,x,y)
                        if(similar(c,green))up++
                        if(similar(c,orange))down++
                    }
                check(up>3 && down>3){"Both task cards must remain visible mid-flight: $up/$down"}
                check(similar(pixel(shot,other.centerX(),other.centerY()),purple))
            }
            frame(1f){shot->
                check(similar(pixel(shot,small.centerX(),small.centerY()),orange))
                check(similar(pixel(shot,main.centerX(),main.centerY()),green))
                check(similar(pixel(shot,other.centerX(),other.centerY()),purple))
            }
            // Run the production renderer's real animated path as well as
            // deterministic position samples. No screenshot is used as a
            // task migration acknowledgment; only renderer pixels are tested.
            val done=java.util.concurrent.CountDownLatch(1)
            val ipcEntered=java.util.concurrent.CountDownLatch(1)
            val releaseIpc=java.util.concurrent.CountDownLatch(1)
            val ipcDone=java.util.concurrent.CountDownLatch(1)
            var backend=""
            try {
                instrumentation.runOnMainSync {
                    val lane=io.github.x647368maker.atomicworkbench.display.StageExchangeBinderLane { action ->
                        host.post { action() }
                    }
                    flight.progress=0f
                    // Longer test-only duration leaves enough real screenshot
                    // sampling time in slower emulators. Production is still 300ms.
                    backend=flight.startMotion(1400L,{}){done.countDown()}
                    check(lane.submit({
                        ipcEntered.countDown()
                        check(releaseIpc.await(8,java.util.concurrent.TimeUnit.SECONDS))
                    }){it.getOrThrow();ipcDone.countDown()})
                }
                check(ipcEntered.await(2,java.util.concurrent.TimeUnit.SECONDS))
                check(backend=="render_thread" || backend=="ui_transform")
                var middleSeen=false
                repeat(5){
                    if(done.count>0 && !middleSeen){
                        Thread.sleep(100)
                        val shot=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            var greenMoved=0;var oldVisible=0
                            for(y in small.top until main.bottom step 12)
                                for(x in small.left until main.right step 12){
                                    val c=pixel(shot,x,y)
                                    if(!small.contains(x,y) && similar(c,green))greenMoved++
                                    if(similar(c,orange))oldVisible++
                                }
                            if(done.count>0 && greenMoved>3 && oldVisible>3)middleSeen=true
                            check(similar(pixel(shot,other.centerX(),other.centerY()),purple))
                        } finally {shot.recycle()}
                    }
                }
                check(middleSeen){"No composed moving two-card pixels while isolated IPC was blocked"}
                check(done.await(4,java.util.concurrent.TimeUnit.SECONDS)){"Motion was blocked by simulated IPC"}
                check(ipcDone.count==1L){"Worker unexpectedly completed before explicit release"}
                frame(1f){shot->
                    check(similar(pixel(shot,small.centerX(),small.centerY()),orange))
                    check(similar(pixel(shot,main.centerX(),main.centerY()),green))
                    check(similar(pixel(shot,other.centerX(),other.centerY()),purple))
                }
            } finally {
                releaseIpc.countDown()
            }
            check(ipcDone.await(3,java.util.concurrent.TimeUnit.SECONDS))
            // A late slot buffer must not retain an already verified main
            // endpoint or its background mask. Test actual composed pixels.
            instrumentation.runOnMainSync { flight.revealMain() }
            frame(1f){shot->
                check(similar(pixel(shot,main.centerX(),main.centerY()),purple)) {
                    "Main preview/mask still covers ready live endpoint"
                }
                check(similar(pixel(shot,small.centerX(),small.centerY()),orange)) {
                    "Independent main reveal removed unready slot preview"
                }
                check(similar(pixel(shot,other.centerX(),other.centerY()),purple))
            }
            android.util.Log.i("StageTransientFlight","motion backend="+backend)
        } finally {
            instrumentation.runOnMainSync {host.removeView(flight)}
            instrumentation.waitForIdleSync()
            Thread.sleep(200)
            instrumentation.runOnMainSync {flight.release()}
        }
        return "PASS production transient HWUI flight: both directions, endpoint colors, unaffected rail, independent main reveal, moving pixels under blocked simulated IPC"
    }
}
