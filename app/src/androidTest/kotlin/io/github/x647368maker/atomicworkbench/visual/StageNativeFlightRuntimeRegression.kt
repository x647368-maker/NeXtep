package io.github.x647368maker.atomicworkbench.visual

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.systemui.StageGpuMirrorOcclusion
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceExchange
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The actual production native animator, two actual live window mirrors,
 * actual SurfaceFlinger composition and cleanup. The two test roots are
 * intentionally NOT owned ShellTaskOrganizer tasks. The source preflight
 * must reject migration; never inject a successful ownership or move ACK.
 * This is not an OEM/task migration test.
 */
internal class StageNativeFlightRuntimeRegression(private val inst:Instrumentation) {
    private val blue=Color.rgb(36,111,241)
    private val orange=Color.rgb(238,119,39)
    private val protectedColor=Color.rgb(51,209,138)
    private val background=Color.rgb(83,44,139)

    private fun matches(value:Int,expected:Int)=
        abs(Color.red(value)-Color.red(expected))<25 &&
        abs(Color.green(value)-Color.green(expected))<25 &&
        abs(Color.blue(value)-Color.blue(expected))<25

    fun run(activity:Activity,host:FrameLayout,out:File):String {
        check(Build.VERSION.SDK_INT>=37)
        check(ValueAnimator.getDurationScale()>=2f) { "Test requires extended system animation scale" }
        val first=View(activity).apply { setBackgroundColor(blue) }
        val second=View(activity).apply { setBackgroundColor(orange) }
        var p1:PopupWindow?=null
        var p2:PopupWindow?=null
        val origin=IntArray(2)
        lateinit var small:Rect
        lateinit var main:Rect
        lateinit var other:Rect
        val evidence=mutableListOf<String>()
        fun shot():Bitmap=checkNotNull(inst.uiAutomation.takeScreenshot())
        fun save(frame:Bitmap,name:String) {
            File(out,name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        fun pixel(frame:Bitmap,x:Int,y:Int)=frame.getPixel(origin[0]+x,origin[1]+y)
        fun box(r:Rect)=StageLiveSurfaceFlightGeometry.Box(
            r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat())
        val cls=StageLiveSurfaceExchange::class.java
        val constructor=cls.declaredConstructors.single { it.parameterCount==13 }
            .apply { isAccessible=true }
        val companion=StageLiveSurfaceExchange.Companion
        val mirrorMethod=companion.javaClass.getDeclaredMethod("createWindowMirror",View::class.java)
            .apply { isAccessible=true }
        class Attempt(val flight:StageLiveSurfaceExchange,val a:SurfaceControl,val b:SurfaceControl,
                      val ended:CountDownLatch,val finishes:AtomicInteger,val moves:AtomicInteger)
        fun begin(cancelImmediately:Boolean=false):Attempt {
            var result:Attempt?=null
            inst.runOnMainSync {
                var a:SurfaceControl?=null
                var b:SurfaceControl?=null
                var masks:StageGpuMirrorOcclusion?=null
                var flight:StageLiveSurfaceExchange?=null
                try {
                    val ma=checkNotNull(mirrorMethod.invoke(companion,first) as? SurfaceControl)
                    a=ma
                    val mb=checkNotNull(mirrorMethod.invoke(companion,second) as? SurfaceControl)
                    b=mb
                    val covers=checkNotNull(StageGpuMirrorOcclusion.create())
                    masks=covers
                    // Nonexistent task IDs are deliberate: render live roots,
                    // then verify that real migration never begins.
                    val incoming=TaskSurfaceCompat.MirrorSourceStamp(-1001,91,Any(),ma)
                    val outgoing=TaskSurfaceCompat.MirrorSourceStamp(-1002,0,Any(),mb)
                    val engine=constructor.newInstance(host,ma,mb,Rect(0,0,180,240),
                        Rect(0,0,270,360),box(small),box(main),null,covers,false,
                        incoming,outgoing,true) as StageLiveSurfaceExchange
                    flight=engine
                    val ended=CountDownLatch(1)
                    val finishes=AtomicInteger()
                    val moves=AtomicInteger()
                    check(engine.start(360,{ complete -> moves.incrementAndGet();complete(false) },
                        { moves.incrementAndGet();false },{ false },{ false },
                        { finishes.incrementAndGet();ended.countDown() },true))
                    result=Attempt(engine,ma,mb,ended,finishes,moves)
                    if(cancelImmediately) { engine.stop();engine.stop() }
                } finally {
                    if(result==null) {
                        val failedFlight=flight
                        if(failedFlight!=null) runCatching { failedFlight.stop() } else {
                            runCatching { masks?.release() }
                            runCatching { b?.release() }
                            runCatching { a?.release() }
                        }
                    }
                }
            }
            return checkNotNull(result)
        }
        fun awaitReleased(a:Attempt,label:String) {
            check(a.ended.await(4,TimeUnit.SECONDS)) { "No completion after $label" }
            check(a.finishes.get()==1 && a.moves.get()==0) { "Duplicate finish or unexpected move: $label" }
            inst.runOnMainSync {
                check(!a.flight.isRunning && !a.a.isValid && !a.b.isValid) {
                    "Live mirror handle remained after $label"
                }
            }
            var restored=false
            for(i in 1..8) {
                Thread.sleep(55)
                val frame=shot()
                try {
                    restored=matches(pixel(frame,small.centerX(),small.centerY()),background) &&
                        matches(pixel(frame,main.centerX(),main.centerY()),background) &&
                        matches(pixel(frame,other.centerX(),other.centerY()),protectedColor)
                    if(restored) { save(frame,"native-flight-clean-$label.png");break }
                    if(i==8) save(frame,"native-flight-failed-clean-$label.png")
                } finally { frame.recycle() }
            }
            check(restored) { "Mirror or mask pixels remained after $label" }
            evidence.add("$label: one finish, no move, both handles released, physical pixels restored")
        }
        try {
            inst.runOnMainSync {
                host.removeAllViews();host.setBackgroundColor(background)
                host.getLocationOnScreen(origin)
                check(host.width>=1000 && host.height>=1800) { "Unexpected API37 test display" }
                small=Rect(80,450,260,690)
                main=Rect(400,1050,940,1770)
                other=Rect(820,450,1000,690)
                host.addView(View(activity).apply { setBackgroundColor(protectedColor) },
                    FrameLayout.LayoutParams(other.width(),other.height()).apply {
                        leftMargin=other.left;topMargin=other.top
                    })
                p1=PopupWindow(first,180,240,false).apply {
                    isTouchable=false;setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    showAtLocation(host,Gravity.TOP or Gravity.LEFT,20,30)
                }
                p2=PopupWindow(second,270,360,false).apply {
                    isTouchable=false;setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    showAtLocation(host,Gravity.TOP or Gravity.LEFT,340,30)
                }
            }
            inst.waitForIdleSync()
            Thread.sleep(300)
            // Interrupt before a later commit/timer can start the animation.
            val early=begin(cancelImmediately=true)
            awaitReleased(early,"early")
            // Recreate immediately after cleanup, observe physical moving pixels,
            // and cancel the active flight. Repeat with stale task preflight.
            for(label in listOf("mid","stale")) {
                val attempt=begin()
                try {
                    var moving=false
                    val deadline=SystemClock.uptimeMillis()+1450L
                    while(SystemClock.uptimeMillis()<deadline && attempt.ended.count>0 && !moving) {
                        val frame=shot()
                        try {
                            check(matches(pixel(frame,other.centerX(),other.centerY()),protectedColor)) {
                                "Protected unselected slot changed during $label"
                            }
                            var up=0;var down=0;var movedUp=0;var movedDown=0
                            for(y in small.top until main.bottom step 12)
                                for(x in 0 until host.width step 12) {
                                    val c=pixel(frame,x,y)
                                    if(matches(c,blue)) { up++; if(!small.contains(x,y))movedUp++ }
                                    if(matches(c,orange)) { down++; if(!main.contains(x,y))movedDown++ }
                                }
                            moving=up>12 && down>12 && movedUp>12 && movedDown>12
                            if(moving)save(frame,"native-flight-moving-$label.png")
                        } finally { frame.recycle() }
                    }
                    check(moving) { "No two moving real GPU card pixel regions during $label" }
                    if(label=="mid")inst.runOnMainSync { attempt.flight.stop();attempt.flight.stop() }
                    awaitReleased(attempt,label)
                    if(label=="stale") inst.runOnMainSync {
                        // Read-only test inspection: ensure the real animator
                        // reached its terminal transaction, not just watchdog
                        // teardown, before rejecting these non-owned roots.
                        val complete=cls.getDeclaredField("frameComplete").apply { isAccessible=true }
                        val terminal=cls.getDeclaredField("terminalTransactionCommitted").apply { isAccessible=true }
                        check(complete.getBoolean(attempt.flight) && terminal.getBoolean(attempt.flight)) {
                            "Stale task test did not reach committed terminal geometry"
                        }
                    }
                } finally { inst.runOnMainSync { attempt.flight.stop() } }
            }
            return "PASS production native GPU animator: moving live roots, protected slot, early/mid cancellation, stale migration rejection, repeat cleanup"
        } finally {
            File(out,"native-flight-runtime.txt").writeText(evidence.joinToString("\n"))
            inst.runOnMainSync { p1?.dismiss();p2?.dismiss();host.removeAllViews() }
        }
    }
}
