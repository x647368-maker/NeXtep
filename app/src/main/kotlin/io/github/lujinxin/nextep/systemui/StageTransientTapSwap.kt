package io.github.x647368maker.atomicworkbench.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Independent one-shot visual layer. Two full-resolution, task-scoped frames
 * are captured off-main BEFORE migration; after the first committed HWUI frame,
 * migrate real tasks in parallel with the 300ms flight. No live mirror shares
 * a migrating task's mutable transform. Normal slots always remain live.
 */
internal class StageTransientTapSwap(private val context:Context) {
    private val ui=Handler(Looper.getMainLooper())
    // At most one active capture, no queued stale captures; worker expires when idle.
    private val worker=ThreadPoolExecutor(0,1,1L,TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(),{r->Thread(r,"StageTapSnapshot").apply{isDaemon=true}},
        ThreadPoolExecutor.AbortPolicy())
    private var active:Attempt?=null
    val isRunning:Boolean get()=active!=null
    fun cancel(){active?.finish("cancelled")}

    fun start(host:FrameLayout,slot:Rect,main:Rect,otherSlots:List<Rect>,
              incoming:TaskSurfaceCompat.MirrorSourceStamp,outgoing:TaskSurfaceCompat.MirrorSourceStamp,
              slotCrop:Rect,mainCrop:Rect,backdrop:Drawable?,
              valid:()->Boolean,moveAsync:((Boolean)->Unit)->Unit,
              liveReady:()->Boolean,trace:(String)->Unit,onFinished:()->Unit,
              onVisualStart:()->Unit={},onVisualEnd:()->Unit={}):Boolean {
        if(active!=null || !host.isAttachedToWindow || !host.isHardwareAccelerated ||
            slot.isEmpty || main.isEmpty || slotCrop.isEmpty || mainCrop.isEmpty) return false
        val a=Attempt(host,Rect(slot),Rect(main),otherSlots.map(::Rect),incoming,outgoing,
            Rect(slotCrop),Rect(mainCrop),backdrop,valid,moveAsync,liveReady,trace,onFinished,onVisualStart,onVisualEnd)
        active=a
        a.begin()
        return true
    }

    private inner class Attempt(
        val host:FrameLayout,val slot:Rect,val main:Rect,val others:List<Rect>,
        val incoming:TaskSurfaceCompat.MirrorSourceStamp,val outgoing:TaskSurfaceCompat.MirrorSourceStamp,
        val slotCrop:Rect,val mainCrop:Rect,val backdrop:Drawable?,
        val valid:()->Boolean,val moveAsync:((Boolean)->Unit)->Unit,
        val liveReady:()->Boolean,val trace:(String)->Unit,val onFinished:()->Unit,
        val onVisualStart:()->Unit,val onVisualEnd:()->Unit,
    ) {
        private var waitingCapture=true
        private var waitingCommit=false
        private var moveStarted=false
        private var moveAck=false
        private var flightDone=false
        private var fallback=false
        private var handoffStarted=false
        private var visualOwned=false
        private var view:FlightView?=null
        private var terminalCommit:Runnable?=null
        private var terminalTimeout:Runnable?=null
        private val handoff=StageStableHandoff()
        private var handoffBegan=0L
        private fun current()=active===this
        private fun stillValid()=runCatching(valid).getOrDefault(false) && host.isAttachedToWindow
        private val captureTimeout=Runnable {
            if(current() && waitingCapture){waitingCapture=false; direct("capture_timeout")}
        }
        private val commitTimeout=Runnable {
            if(current() && waitingCommit){waitingCommit=false; direct("first_commit_timeout")}
        }
        private val lifetime=Runnable {if(current())finish("lifetime_timeout")}
        private val checkHandoff=object:Runnable{
            override fun run(){
                if(!current())return
                if(!host.isAttachedToWindow){finish("host_detached");return}
                val elapsed=SystemClock.uptimeMillis()-handoffBegan
                val ready=runCatching(liveReady).getOrDefault(false)
                when(handoff.decide(ready,elapsed>=StageTransientTapPolicy.HANDOFF_BUDGET_MS)){
                    StageStableHandoff.Decision.READY->finish("ready")
                    StageStableHandoff.Decision.TIMEOUT->finish("handoff_timeout")
                    StageStableHandoff.Decision.WAIT->host.postOnAnimation(this)
                }
            }
        }
        fun begin(){
            val deadline=SystemClock.uptimeMillis()+StageTransientTapPolicy.CAPTURE_BUDGET_MS
            trace("snapshot_capture_begin")
            ui.postDelayed(captureTimeout,StageTransientTapPolicy.CAPTURE_BUDGET_MS)
            ui.postDelayed(lifetime,StageTransientTapPolicy.MAX_ATTEMPT_MS)
            runCatching {
                worker.execute {
                    var incomingFailure="none"
                    var outgoingFailure="not_attempted"
                    val a=TaskSurfaceCompat.captureObservedSwapPreview(incoming,slotCrop){incomingFailure=it}
                    val b=if(a!=null && StageTransientTapPolicy.canCaptureSecond(SystemClock.uptimeMillis(),deadline)){
                        outgoingFailure="none"
                        TaskSurfaceCompat.captureObservedSwapPreview(outgoing,mainCrop){outgoingFailure=it}
                    } else null
                    ui.post {
                        if(current() && waitingCapture && (a==null || b==null))
                            trace("snapshot_capture_result:slot="+incomingFailure+",main="+outgoingFailure)
                        captured(a,b,deadline)
                    }
                }
            }.onFailure {
                waitingCapture=false
                ui.removeCallbacks(captureTimeout)
                direct("capture_busy")
            }
        }
        private fun captured(a:Bitmap?,b:Bitmap?,deadline:Long){
            if(!current() || !waitingCapture){a?.recycle();b?.recycle();return}
            waitingCapture=false
            ui.removeCallbacks(captureTimeout)
            if(!stillValid()){a?.recycle();b?.recycle();finish("stale_before_move");return}
            if(a==null || b==null || SystemClock.uptimeMillis()>=deadline){
                a?.recycle();b?.recycle();direct("capture_unavailable");return
            }
            val v=FlightView(context,a,b,slot,main,others,backdrop)
            if(!v.safe){v.release();direct("corridor_unavailable");return}
            view=v
            val added=runCatching{
                host.addView(v,FrameLayout.LayoutParams(-1,-1))
            }.isSuccess
            if(!added){removeVisual();direct("attach_failed");return}
            trace("snapshot_pair_ready")
            waitingCommit=true
            ui.postDelayed(commitTimeout,StageTransientTapPolicy.FIRST_COMMIT_BUDGET_MS)
            runCatching {
                host.viewTreeObserver.registerFrameCommitCallback {
                    ui.post {
                        if(current() && waitingCommit && v.drawn && v.isAttachedToWindow) {
                            waitingCommit=false
                            ui.removeCallbacks(commitTimeout)
                            beginFlight(v)
                        }
                    }
                }
                v.invalidate()
            }.onFailure{
                waitingCommit=false
                ui.removeCallbacks(commitTimeout)
                direct("commit_unavailable")
            }
        }
        private fun beginFlight(v:FlightView){
            if(!StageTransientTapPolicy.canStartMove(stillValid(),v.drawn,moveStarted)){
                finish("stale_at_commit");return
            }
            trace("snapshot_first_frame_committed")
            // Prevent the slot's independent entrance/exit animation from
            // running underneath the two-card flight and exposing a second scale.
            visualOwned=true
            runCatching(onVisualStart).onFailure {
                finish("visual_start_failed");return
            }
            val backend=v.startMotion(StageTransientTapPolicy.FLIGHT_MS,trace) {
                if(current())awaitTerminalFrame(v)
            }
            trace("snapshot_motion_backend="+backend)
            // Do not start Binder work on the same stack that arms the motion.
            // The first moving frame gets a traversal opportunity; migration
            // still overlaps the 300ms visual flight.
            v.postOnAnimation { if(current())requestMove() }
        }
        private fun awaitTerminalFrame(v:FlightView){
            if(!current() || terminalCommit!=null || flightDone)return
            v.progress=1f
            v.invalidate()
            val observer=host.viewTreeObserver
            val callback=Runnable {
                ui.post {
                    if(!current() || flightDone)return@post
                    clearTerminalCallbacks()
                    flightDone=true
                    trace("snapshot_flight_end")
                    trace("snapshot_terminal_commit")
                    maybeHandoff()
                }
            }
            val timeout=Runnable{
                if(current() && !flightDone){
                    trace("snapshot_terminal_commit_timeout")
                    finish("terminal_commit_timeout")
                }
            }
            terminalCommit=callback;terminalTimeout=timeout
            ui.postDelayed(timeout,StageTransientTapPolicy.FIRST_COMMIT_BUDGET_MS)
            runCatching{observer.registerFrameCommitCallback(callback)}
                .onFailure{trace("snapshot_terminal_callback_unavailable")}
        }
        private fun clearTerminalCallbacks(){
            terminalCommit?.let{cb->
                runCatching{host.viewTreeObserver.unregisterFrameCommitCallback(cb)}
            }
            terminalTimeout?.let{ui.removeCallbacks(it)}
            terminalCommit=null;terminalTimeout=null
        }
        private fun direct(why:String){
            if(!current() || moveStarted)return
            trace("snapshot_fallback:"+why)
            removeVisual()
            if(!stillValid()){finish("stale_fallback");return}
            fallback=true
            flightDone=true
            requestMove()
        }
        private fun requestMove(){
            if(!current() || moveStarted)return
            moveStarted=true
            trace("snapshot_real_move_requested")
            runCatching {
                moveAsync { ok ->
                    if(!current())return@moveAsync
                    trace("snapshot_real_move_ack="+ok)
                    if(!ok || fallback){finish(if(ok)"fallback_ready" else "move_failed");return@moveAsync}
                    moveAck=true
                    maybeHandoff()
                }
            }.onFailure{finish("move_exception")}
        }
        private fun maybeHandoff(){
            if(!current() || handoffStarted ||
                !StageTransientTapPolicy.canHandoff(flightDone,moveAck))return
            handoffStarted=true
            handoffBegan=SystemClock.uptimeMillis()
            checkHandoff.run()
        }
        private fun removeVisual(){
            clearTerminalCallbacks()
            view?.stopMotion()
            if(visualOwned){visualOwned=false;runCatching(onVisualEnd)}
            val old=view ?: return
            view=null
            runCatching{host.removeView(old)}
            // RenderThread may still own the last drawn hardware buffer.
            // Release only after removal commits or the bounded one-shot fallback.
            if(!old.drawn || !host.isAttachedToWindow){old.release();return}
            val observer=host.viewTreeObserver
            var callback:Runnable?=null
            val release=Runnable{
                callback?.let{cb->runCatching{if(observer.isAlive)observer.unregisterFrameCommitCallback(cb)}}
                old.release()
            }
            callback=Runnable{ui.removeCallbacks(release);ui.post(release)}
            runCatching{if(observer.isAlive)observer.registerFrameCommitCallback(checkNotNull(callback))}
            ui.postDelayed(release,200L)
            host.invalidate()
        }
        fun finish(result:String){
            if(!current())return
            active=null
            waitingCapture=false;waitingCommit=false
            ui.removeCallbacks(captureTimeout);ui.removeCallbacks(commitTimeout);ui.removeCallbacks(lifetime)
            host.removeCallbacks(checkHandoff)
            removeVisual()
            trace("snapshot_end:"+result)
            onFinished()
        }
    }

    /** Two independently composed immutable cards. The parent records its
     * mask once; transform updates do not re-record the bitmaps or backdrop.
     * The optional platform RenderNodeAnimator drives the transforms on
     * RenderThread. Unsupported OEMs keep the exact same motion via UI
     * property transforms, not the removed multi-phase renderer.
     */
    internal class FlightView(context:Context,private val incoming:Bitmap,private val outgoing:Bitmap,
                             slot:Rect,main:Rect,others:List<Rect>,private val backdrop:Drawable?):FrameLayout(context){
        private fun box(r:Rect)=StageSnapshotMotion.Box(r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat())
        private val small=box(slot)
        private val large=box(main)
        private val excluded=others.map(::Rect)
        private val up=StageSnapshotMotion.flight(small,large,incoming.width,incoming.height)
        private val down=StageSnapshotMotion.flight(large,small,outgoing.width,outgoing.height)
        val safe=up!=null && down!=null
        private val mask=Path().apply{
            addRect(RectF(slot),Path.Direction.CW);addRect(RectF(main),Path.Direction.CW)
        }
        private val upView=CardView(context,incoming,main)
        private val downView=CardView(context,outgoing,main)
        private val animations=mutableListOf<Animator>()
        var progress=0f
            set(value){
                field=value.coerceIn(0f,1f)
                up?.let{upView.place(it.pose(field))}
                down?.let{downView.place(it.pose(field))}
            }
        var drawn=false
            private set
        private var released=false
        init{
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable=false
            setWillNotDraw(false)
            clipChildren=false;clipToPadding=false
            // Incoming remains below the shrinking outgoing card where paths
            // cross; the opposing arcs expose both without hiding other slots.
            addView(upView,LayoutParams(incoming.width,incoming.height))
            addView(downView,LayoutParams(outgoing.width,outgoing.height))
            progress=0f
        }
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            if(released || !canvas.isHardwareAccelerated)return
            val save=canvas.save()
            excluded.forEach{canvas.clipOutRect(it)}
            canvas.clipPath(mask)
            canvas.drawColor(Color.rgb(12,14,21))
            backdrop?.setBounds(0,0,width,height);backdrop?.draw(canvas)
            canvas.restoreToCount(save)
        }
        override fun dispatchDraw(canvas:Canvas){
            if(released)return
            val save=canvas.save()
            excluded.forEach{canvas.clipOutRect(it)}
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
            if(canvas.isHardwareAccelerated && safe)drawn=true
        }
        fun startMotion(duration:Long,trace:(String)->Unit,onEnd:()->Unit):String{
            stopMotion()
            progress=0f
            val a=up ?: error("Invalid incoming geometry")
            val b=down ?: error("Invalid outgoing geometry")
            val easing=PathInterpolator(.28f,.05f,.22f,1f)
            // Build every animator before starting any; an unsupported
            // reflection path leaves no partially animated card.
            val native=runCatching{
                val klass=Class.forName("android.view.RenderNodeAnimator")
                val ctor=klass.getConstructor(Int::class.javaPrimitiveType,Float::class.javaPrimitiveType)
                val target=klass.getMethod("setTarget",View::class.java)
                val startValue=klass.getMethod("setStartValue",Float::class.javaPrimitiveType)
                fun channel(view:CardView,flight:StageSnapshotMotion.Flight,name:String,
                            value:(StageSnapshotMotion.Pose)->Float):Animator{
                    val from=value(flight.start)
                    val exact=value(flight.end)
                    val to=StageSnapshotMotion.hardwareEnd(from,exact)
                    val anim=ctor.newInstance(klass.getField(name).getInt(null),to) as Animator
                    target.invoke(anim,view)
                    startValue.invoke(anim,from)
                    anim.duration=duration
                    anim.interpolator=TimeInterpolator{t->
                        val raw=t.coerceIn(0f,1f)
                        if(raw>=1f)1f else (value(flight.pose(easing.getInterpolation(raw)))-from)/(to-from)
                    }
                    return anim
                }
                val list=mutableListOf<Animator>()
                for((v,f) in listOf(upView to a,downView to b)){
                    list+=channel(v,f,"TRANSLATION_X"){it.cx-v.width*.5f}
                    list+=channel(v,f,"TRANSLATION_Y"){it.cy-v.height*.5f}
                    list+=channel(v,f,"SCALE_X"){it.scale}
                    list+=channel(v,f,"SCALE_Y"){it.scale}
                }
                animations.addAll(list)
                var remaining=list.size
                list.forEach{anim->
                    anim.addListener(object:AnimatorListenerAdapter(){
                        override fun onAnimationEnd(animation:Animator){
                            if(--remaining==0){
                                animations.clear()
                                progress=1f
                                onEnd()
                            }
                        }
                    })
                }
                list.forEach{it.start()}
                true
            }.getOrElse{
                stopMotion()
                trace("snapshot_rt_unavailable")
                progress=0f
                false
            }
            if(native)return "render_thread"
            val fallback=ValueAnimator.ofFloat(0f,1f).apply{
                this.duration=duration
                interpolator=easing
                addUpdateListener{progress=it.animatedValue as Float}
                addListener(object:AnimatorListenerAdapter(){
                    override fun onAnimationEnd(animation:Animator){
                        animations.clear();progress=1f;onEnd()
                    }
                })
            }
            animations.add(fallback);fallback.start()
            return "ui_transform"
        }
        fun stopMotion(){
            val old=animations.toList()
            animations.clear()
            old.forEach{anim->
                anim.removeAllListeners()
                if(anim is ValueAnimator)anim.removeAllUpdateListeners()
                runCatching{anim.cancel()}
            }
        }
        private class CardView(context:Context,private val bitmap:Bitmap,main:Rect):View(context){
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val image=RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat())
            init{
                pivotX=bitmap.width*.5f;pivotY=bitmap.height*.5f
                // Use the physical main endpoint's roundness, converted to
                // source coordinates. Interpolation scales naturally with
                // each card. The actual live corners are never modified.
                val scale=minOf(main.width().toFloat()/bitmap.width,
                    main.height().toFloat()/bitmap.height).coerceAtLeast(.001f)
                val radius=io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.radius(
                    main.width().toFloat(),main.height().toFloat(),resources.displayMetrics.density)/scale
                outlineProvider=object:ViewOutlineProvider(){
                    override fun getOutline(view:View,outline:Outline){
                        outline.setRoundRect(0,0,bitmap.width,bitmap.height,radius)
                    }
                }
                clipToOutline=true
            }
            fun place(p:StageSnapshotMotion.Pose){
                translationX=p.cx-bitmap.width*.5f
                translationY=p.cy-bitmap.height*.5f
                scaleX=p.scale;scaleY=p.scale
            }
            override fun onDraw(canvas:Canvas){
                if(!bitmap.isRecycled)canvas.drawBitmap(bitmap,null,image,paint)
            }
        }
        fun release(){
            if(released)return
            released=true;stopMotion()
            incoming.recycle();if(outgoing!==incoming)outgoing.recycle()
        }
    }
}
