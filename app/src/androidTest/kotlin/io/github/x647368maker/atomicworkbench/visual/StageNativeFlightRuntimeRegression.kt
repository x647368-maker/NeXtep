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
import android.view.Choreographer
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry
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

    fun run(activity:Activity,host:FrameLayout,out:File,pairedRail:Boolean=false,adaptiveCovered:Boolean=false):String {
        check(!adaptiveCovered || pairedRail)
        val sourceW=if(adaptiveCovered)424 else 180
        val sourceH=if(adaptiveCovered)839 else 240
        val mainW=if(adaptiveCovered)318 else 270
        val mainH=if(adaptiveCovered)693 else 360
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
        lateinit var sourceRoots:List<Rect>
        var hostWidth=0
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
        fun begin(cancelImmediately:Boolean=false,detachImmediately:Boolean=false,
                  immediateMove:Boolean=true):Attempt {
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
                    StageExchangeTelemetry.beginNativeTap("instrumented_live_root_flight")
                    val traceId=StageExchangeTelemetry.currentNativeTapId()
                    val engine=constructor.newInstance(host,ma,mb,Rect(0,0,sourceW,sourceH),
                        Rect(0,0,mainW,mainH),box(small),box(main),null,covers,adaptiveCovered,
                        incoming,outgoing,true) as StageLiveSurfaceExchange
                    flight=engine
                    if(pairedRail)check(engine.preparePairedRailRuntimeTest(
                        StageLiveSurfaceFlightGeometry.Box(0f,0f,host.width.toFloat(),host.height.toFloat()),
                        listOf(box(other)))) { "No certified paired route: adaptive=$adaptiveCovered" }
                    val ended=CountDownLatch(1)
                    val finishes=AtomicInteger()
                    val moves=AtomicInteger()
                    check(engine.start(360,{ complete -> moves.incrementAndGet();complete(false) },
                        { moves.incrementAndGet();false },{ false },{ false },
                        { finishes.incrementAndGet();StageExchangeTelemetry.endNativeTap("instrumented_finish",traceId);ended.countDown() },immediateMove))
                    result=Attempt(engine,ma,mb,ended,finishes,moves)
                    if(cancelImmediately) { engine.stop();engine.stop() }
                    if(detachImmediately) {
                        // Actual framework detach, not an injected isAttached flag.
                        activity.setContentView(FrameLayout(activity).apply { setBackgroundColor(this@StageNativeFlightRuntimeRegression.background) })
                        check(!host.isAttachedToWindow) { "Old compositor host was not detached" }
                    }
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
        fun awaitHandlesReleased(a:Attempt,label:String) {
            check(a.ended.await(4,TimeUnit.SECONDS)) { "No completion after $label" }
            check(a.finishes.get()==1 && a.moves.get()==0) { "Duplicate finish or unexpected move: $label" }
            inst.runOnMainSync {
                check(!a.flight.isRunning && !a.a.isValid && !a.b.isValid) {
                    "Live mirror handle remained after $label"
                }
            }
        }
        fun awaitReleased(a:Attempt,label:String) {
            awaitHandlesReleased(a,label)
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
                hostWidth=host.width
                check(hostWidth>=1000 && host.height>=1800) { "Unexpected API37 test display" }
                // Two-thirds-scale representative portrait report geometry.
                // Actual PopupWindow buffers retain the two distinct source aspects.
                if(adaptiveCovered) {
                    check(hostWidth>=1272)
                    small=Rect(12,359,200,731)
                    main=Rect(212,307,848,1693)
                    other=Rect(12,749,200,1121)
                } else {
                    small=Rect(80,450,260,690)
                    main=Rect(400,1050,940,1770)
                    other=Rect(820,450,1000,690)
                }
                host.addView(View(activity).apply { setBackgroundColor(protectedColor) },
                    FrameLayout.LayoutParams(other.width(),other.height()).apply {
                        leftMargin=other.left;topMargin=other.top
                    })
                p1=PopupWindow(first,sourceW,sourceH,false).apply {
                    isTouchable=false;setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    showAtLocation(host,Gravity.TOP or Gravity.LEFT,if(adaptiveCovered)hostWidth-sourceW else 20,30)
                }
                p2=PopupWindow(second,mainW,mainH,false).apply {
                    isTouchable=false;setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    showAtLocation(host,Gravity.TOP or Gravity.LEFT,if(adaptiveCovered)hostWidth-mainW else 340,if(adaptiveCovered)910 else 30)
                }
            }
            inst.waitForIdleSync()
            Thread.sleep(300)
            inst.runOnMainSync {
                // Source positions include the status-bar inset; a requested
                // PopupWindow offset is NOT its actual physical position.
                check(first.width==sourceW && first.height==sourceH && second.width==mainW && second.height==mainH) {
                    "Actual buffer fixture dimensions differ from requested crop"
                }
                sourceRoots=listOf(first,second).map { view ->
                    check(view.isAttachedToWindow && view.width>0 && view.height>0)
                    val at=IntArray(2);view.getLocationOnScreen(at)
                    Rect(at[0]-origin[0],at[1]-origin[1],
                        at[0]-origin[0]+view.width,at[1]-origin[1]+view.height)
                }
            }
            evidence.add("hostOrigin=${origin.joinToString()} sourceRoots=${sourceRoots.joinToString()}")
            // Interrupt before a later commit/timer can start the animation.
            val early=begin(cancelImmediately=true)
            awaitReleased(early,"early")
            if(pairedRail) {
                // No screenshot/sleep between two actual sessions. Posted
                // callbacks from the old session must not finish its successor.
                val rapidA=begin(cancelImmediately=true)
                val rapidB=begin(cancelImmediately=true)
                awaitReleased(rapidA,"rapid-a")
                awaitReleased(rapidB,"rapid-b")
            }
            // Screen capture can block until after the terminal frame on a
            // busy emulator. Cancel an independent flight from its frame clock
            // so the race with a screenshot cannot invalidate teardown proof.
            val submitted=cls.getDeclaredField("nativeFrameCount").apply { isAccessible=true }
            val terminal=cls.getDeclaredField("frameComplete").apply { isAccessible=true }
            val firstCommitted=cls.getDeclaredField("firstTransactionCommitted").apply { isAccessible=true }
            var cancelledAtFrame=0
            for (trial in 1..3) {
                val cancellation=begin()
                val cancelled=AtomicInteger(0)
                inst.runOnMainSync {
                    val clock=Choreographer.getInstance()
                    clock.postFrameCallback(object : Choreographer.FrameCallback {
                        override fun doFrame(frameTimeNanos:Long) {
                            if(!cancellation.flight.isRunning || terminal.getBoolean(cancellation.flight))return
                            val frames=submitted.getInt(cancellation.flight)
                            if(frames>=2) {
                                cancelled.set(frames)
                                cancellation.flight.stop()
                                cancellation.flight.stop()
                            } else clock.postFrameCallback(this)
                        }
                    })
                }
                awaitHandlesReleased(cancellation,"mid-$trial")
                awaitReleased(cancellation,if(cancelled.get()>=2) "mid" else "mid-attempt-$trial")
                cancelledAtFrame=cancelled.get()
                if(cancelledAtFrame>=2)break
                inst.runOnMainSync {
                    check(!firstCommitted.getBoolean(cancellation.flight) && submitted.getInt(cancellation.flight)==0) {
                        "Admitted GPU flight did not reach frame-clock cancellation"
                    }
                }
                evidence.add("mid attempt $trial: first transaction missed budget; no frames or move; handles and pixels restored")
            }
            check(cancelledAtFrame>=2) { "No cancellation during submitted live frames" }
            evidence.add("mid cancellation: live flight active after ${cancelledAtFrame} submitted frames")

            // Prepare the full-resolution sampling region and the unchanged
            // 12px lattice BEFORE the flight starts. Source exclusion is
            // based on measured physical root bounds, never expected offsets.
            val scanTop=minOf(small.top,main.top)
            val scanHeight=main.bottom-scanTop
            val pixels=IntArray(hostWidth*scanHeight)
            val positions=ArrayList<Int>()
            val coordinateX=ArrayList<Int>()
            val coordinateY=ArrayList<Int>()
            for(y in scanTop until main.bottom step 12) {
                for(x in 0 until hostWidth step 12) {
                    if(sourceRoots.none { it.contains(x,y) }) {
                        positions.add((y-scanTop)*hostWidth+x)
                        coordinateX.add(x)
                        coordinateY.add(y)
                    }
                }
            }
            val sampleOffsets=positions.toIntArray()
            val sampleX=coordinateX.toIntArray()
            val sampleY=coordinateY.toIntArray()
            val outsideSmall=BooleanArray(sampleOffsets.size) { i ->
                !small.contains(sampleX[i],sampleY[i])
            }
            val outsideMain=BooleanArray(sampleOffsets.size) { i ->
                !main.contains(sampleX[i],sampleY[i])
            }
            // Recreate immediately after cleanup and observe physical moving
            // pixels. Repeat to verify stale task preflight at the endpoint.
            for(label in listOf("mid","stale")) {
                var observed=false
                // Admission can legitimately reject a slow compositor ACK.
                // Repeat only a zero-frame, fully retired attempt; a flight
                // that actually started must meet every original pixel assertion.
                for(trial in 1..3) {
                val attempt=begin()
                try {
                    var moving=false
                    var samples=0
                    var last:Bitmap?=null
                    var firstMoving:Bitmap?=null
                    var firstCenters:FloatArray?=null
                    var distinctMoving=false
                    var outsideBoth=false
                    val axisX=(main.centerX()-small.centerX()).toFloat()
                    val axisY=(main.centerY()-small.centerY()).toFloat()
                    val axisLength=kotlin.math.hypot(axisX,axisY)
                    fun projection(x:Float,y:Float)=(x*axisX+y*axisY)/axisLength
                    val start=SystemClock.uptimeMillis()
                    val deadline=start+1450L
                    // Retain only one screenshot while sampling. Compression
                    // and evidence writes on failure happen AFTER observation.
                    try {
                    while(SystemClock.uptimeMillis()<deadline && attempt.ended.count>0 && !moving) {
                        val requested=SystemClock.uptimeMillis()
                        val frame=shot()
                        val captured=SystemClock.uptimeMillis()
                        if(last!==firstMoving)last?.recycle()
                        last=frame
                        samples++
                        run {
                            check(matches(pixel(frame,other.centerX(),other.centerY()),protectedColor)) {
                                "Protected unselected slot changed during $label"
                            }
                            var up=0;var down=0;var movedUp=0;var movedDown=0
                            var ux=0L;var uy=0L;var dx=0L;var dy=0L
                            // One read of the original physical bitmap,
                            // rather than 110 repeated Bitmap JNI row reads.
                            // Every coordinate and color threshold is unchanged.
                            frame.getPixels(pixels,0,hostWidth,origin[0],
                                origin[1]+scanTop,hostWidth,scanHeight)
                            val readAt=SystemClock.uptimeMillis()
                            // Identical 12px sampling lattice and color
                            // thresholds; precompute division and Rect.contains
                            // only once, outside the moving compositor window.
                            // Reduces analysis jitter without allowing any
                            // missing two-card pixel evidence to pass.
                            for(i in sampleOffsets.indices) {
                                val c=pixels[sampleOffsets[i]]
                                val x=sampleX[i]
                                val y=sampleY[i]
                                if(matches(c,blue)) { up++;ux+=x;uy+=y;if(outsideSmall[i])movedUp++ }
                                if(matches(c,orange)) { down++;dx+=x;dy+=y;if(outsideMain[i])movedDown++ }
                            }
                            val both=up>12 && down>12 && movedUp>12 && movedDown>12
                            if(!pairedRail)moving=both
                            else if(up>12 && down>12) {
                                val centers=floatArrayOf(ux.toFloat()/up,uy.toFloat()/up,dx.toFloat()/down,dy.toFloat()/down)
                                val forwardIn=projection(centers[0]-small.centerX(),centers[1]-small.centerY())
                                val forwardOut=projection(main.centerX()-centers[2],main.centerY()-centers[3])
                                if(both)outsideBoth=true
                                val prior=firstCenters
                                if(prior==null && forwardIn>12f && forwardOut>12f) {
                                    firstCenters=centers;firstMoving=frame
                                } else if(prior!=null) {
                                    val d1=projection(centers[0]-prior[0],centers[1]-prior[1])
                                    val d2=projection(prior[2]-centers[2],prior[3]-centers[3])
                                    if(d1>12f && d2>12f)distinctMoving=true
                                    evidence.add("$label distinct forward displacement blue=$d1 orange=$d2")
                                    // Mid is an interruption test, not proof of
                                    // a completed swap. The stale-terminal case
                                    // ALSO requires actual external footprint
                                    // pixels from both departing mirrors.
                                    moving=distinctMoving && (label=="mid" || outsideBoth) && d1>12f && d2>12f
                                }
                                evidence.add("$label actual_centers=${centers.joinToString()} forward=$forwardIn/$forwardOut bothOutside=$both")
                            }
                            evidence.add("$label sample=$samples requested=${requested-start}ms captured=${captured-start}ms read=${readAt-captured}ms analysis=${SystemClock.uptimeMillis()-readAt}ms blue=$up orange=$down movedBlue=$movedUp movedOrange=$movedDown")
                        }
                    }
                    } finally {
                        try {
                            firstMoving?.let { save(it,"native-flight-first-moving-$label.png") }
                            last?.let { save(it,if(moving)"native-flight-moving-$label.png" else "native-flight-failed-moving-$label.png") }
                        } finally {
                            firstMoving?.recycle()
                            if(last!==firstMoving)last?.recycle()
                        }
                        evidence.add("$label samples=$samples elapsed=${SystemClock.uptimeMillis()-start}ms ended=${attempt.ended.count==0L}")
                    }
                    if(!moving && attempt.ended.count==0L) {
                        val rejectedBeforeFlight=java.util.concurrent.atomic.AtomicBoolean(false)
                        inst.runOnMainSync {
                            rejectedBeforeFlight.set(!firstCommitted.getBoolean(attempt.flight) &&
                                submitted.getInt(attempt.flight)==0 && !attempt.flight.isRunning)
                        }
                        if(rejectedBeforeFlight.get()) {
                            awaitReleased(attempt,"$label-admission-$trial")
                            val failed=File(out,"native-flight-failed-moving-$label.png")
                            if(failed.exists()) check(failed.renameTo(File(out,"native-flight-rejected-$label-$trial.png")))
                            evidence.add("$label attempt $trial: admission rejected before any flight frame; no move, one finish, handles and pixels restored")
                            continue
                        }
                    }
                    check(moving) {
                        "No two moving real GPU card pixel regions during $label; samples=$samples distinct=$distinctMoving bothOutside=$outsideBoth; inspect native-flight-runtime.txt and native-flight-trace.txt"
                    }
                    awaitReleased(attempt,if(label=="mid") "movement" else label)
                    if(label=="stale") {
                        // The main lane only reads the state. Throwing a test
                        // assertion on that lane kills the instrumentation
                        // process before the finally block saves its evidence.
                        // Both original terminal conditions remain mandatory.
                        val complete=cls.getDeclaredField("frameComplete").apply { isAccessible=true }
                        val terminal=cls.getDeclaredField("terminalTransactionCommitted").apply { isAccessible=true }
                        val flags=BooleanArray(2)
                        var outcome=""
                        inst.runOnMainSync {
                            flags[0]=complete.getBoolean(attempt.flight)
                            flags[1]=terminal.getBoolean(attempt.flight)
                            outcome=StageLiveSurfaceExchange.lastOutcome()
                        }
                        evidence.add("stale terminal frame="+flags[0]+" committed="+flags[1]+" outcome="+outcome)
                        if(flags[0] && !flags[1]) {
                            val trace=StageExchangeTelemetry.nativeDescription().lineSequence()
                                .lastOrNull { it.contains("instrumented_live_root_f begin@") }.orEmpty()
                            check(trace.contains("native_move_rejected:terminal_unconfirmed") &&
                                !trace.contains("native_terminal_transaction_committed") &&
                                attempt.moves.get()==0) {
                                "Terminal ACK missed without fail-closed trace: "+trace
                            }
                            evidence.add("stale attempt $trial: compositor receipt refused, no move, released; retry")
                            continue
                        }
                        check(flags[0] && flags[1]) {
                            "Stale task test did not reach committed terminal geometry: frame="+flags[0]+
                                " committed="+flags[1]+" outcome="+outcome
                        }
                    }
                    observed=true
                    break
                } finally { inst.runOnMainSync { attempt.flight.stop() } }
                }
                check(observed) { "No admitted flight with real moving pixels after three $label attempts" }
            }
            if(pairedRail) {
                // Exercise the *actual* post-ready compositor reveal, not a
                // mocked policy boolean. Fake Shell IDs are never migrated.
                // This reproduces the lost ColorOS ValueAnimator pulse/end
                // class without claiming a real OEM task handoff.
                val revealOnly=begin(immediateMove=false)
                try {
                    inst.runOnMainSync {
                        val flight=revealOnly.flight
                        val gpuClock=cls.getDeclaredField("animator").apply { isAccessible=true }
                        val originalClock=checkNotNull(gpuClock.get(flight) as? ValueAnimator)
                        originalClock.removeAllUpdateListeners()
                        originalClock.removeAllListeners()
                        originalClock.cancel()
                        cls.getDeclaredField("frameComplete").apply { isAccessible=true }
                            .setBoolean(flight,true)
                        cls.getDeclaredField("moveComplete").apply { isAccessible=true }
                            .setBoolean(flight,true)
                        val revealMethod=cls.getDeclaredMethod("revealReadyDestination",
                            Function0::class.java,Function0::class.java,
                            Long::class.javaPrimitiveType!!).apply { isAccessible=true }
                        revealMethod.invoke(flight,{ true },{ true },48L)
                        val reveal=checkNotNull(cls.getDeclaredField("revealAnimator")
                            .apply { isAccessible=true }.get(flight) as? ValueAnimator)
                        // Suppress BOTH native animator listeners and frame
                        // callbacks. The one-shot terminal clock must own exit.
                        reveal.removeAllUpdateListeners()
                        reveal.removeAllListeners()
                        reveal.cancel()
                    }
                    check(revealOnly.ended.await(1200L,TimeUnit.MILLISECONDS)) {
                        "Ready endpoint reveal remained masked after missing animator pulse/end"
                    }
                    awaitHandlesReleased(revealOnly,"missing-reveal-callbacks")
                    val outcome=BooleanArray(2)
                    inst.runOnMainSync {
                        outcome[0]=cls.getDeclaredField("handoffReady")
                            .apply { isAccessible=true }.getBoolean(revealOnly.flight)
                        outcome[1]=cls.getDeclaredField("handoffTimedOut")
                            .apply { isAccessible=true }.getBoolean(revealOnly.flight)
                    }
                    check(outcome[0] && !outcome[1]) {
                        "No confirmed bounded final-alpha compositor handoff: ready=${outcome[0]} timeout=${outcome[1]}"
                    }
                    evidence.add("forced missing reveal pulse/end: compositor terminal committed, handoff ready, one finish, no 1800ms watchdog")
                } finally { inst.runOnMainSync { revealOnly.flight.stop() } }
                val detached=begin(detachImmediately=true)
                awaitHandlesReleased(detached,"host-detach")
                inst.runOnMainSync {
                    check(!host.isAttachedToWindow)
                    p1?.dismiss();p2?.dismiss()
                }
                var gone=false
                for(i in 1..8) {
                    Thread.sleep(55)
                    val frame=shot()
                    try {
                        gone=matches(pixel(frame,small.centerX(),small.centerY()),background) &&
                            matches(pixel(frame,main.centerX(),main.centerY()),background)
                        if(gone) { save(frame,"native-flight-clean-host-detach.png");break }
                    } finally { frame.recycle() }
                }
                check(gone) { "Detached host left visible mirror/mask pixels" }
                evidence.add("host-detach: actual detach, one finish, no move, both handles released, pixels restored")
            }
            return if(adaptiveCovered) "PASS adaptive mixed-aspect covered flight: real moving pixels, protected slot, cancellation, stale-owner terminal and detach cleanup"
                else if(pairedRail) "PASS paired contain trial: distinct source-excluded moving frames, both outgoing footprints, protected slot, rapid cancellation, host detach and stale migration rejection"
                else "PASS production native GPU animator: moving live roots, protected slot, early/mid cancellation, stale migration rejection, repeat cleanup"
        } finally {
            File(out,"native-flight-runtime.txt").writeText(evidence.joinToString("\n"))
            File(out,"native-flight-trace.txt").writeText(StageExchangeTelemetry.nativeDescription()+"\nOutcome: "+StageLiveSurfaceExchange.lastOutcome())
            inst.runOnMainSync { p1?.dismiss();p2?.dismiss();host.removeAllViews() }
        }
    }
}
