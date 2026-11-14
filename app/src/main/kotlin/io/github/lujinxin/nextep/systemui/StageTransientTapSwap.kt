package io.github.x647368maker.atomicworkbench.systemui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
              liveReady:()->Boolean,trace:(String)->Unit,onFinished:()->Unit):Boolean {
        if(active!=null || !host.isAttachedToWindow || !host.isHardwareAccelerated ||
            slot.isEmpty || main.isEmpty || slotCrop.isEmpty || mainCrop.isEmpty) return false
        val a=Attempt(host,Rect(slot),Rect(main),otherSlots.map(::Rect),incoming,outgoing,
            Rect(slotCrop),Rect(mainCrop),backdrop,valid,moveAsync,liveReady,trace,onFinished)
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
    ) {
        private var waitingCapture=true
        private var waitingCommit=false
        private var moveStarted=false
        private var moveAck=false
        private var flightDone=false
        private var fallback=false
        private var handoffStarted=false
        private var view:FlightView?=null
        private var animator:ValueAnimator?=null
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
                    val a=TaskSurfaceCompat.captureObservedSwapPreview(incoming,slotCrop)
                    val b=if(a!=null && StageTransientTapPolicy.canCaptureSecond(SystemClock.uptimeMillis(),deadline))
                        TaskSurfaceCompat.captureObservedSwapPreview(outgoing,mainCrop) else null
                    ui.post { captured(a,b,deadline) }
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
            animator=ValueAnimator.ofFloat(0f,1f).apply {
                duration=StageTransientTapPolicy.FLIGHT_MS
                interpolator=PathInterpolator(.22f,0f,.12f,1f)
                addUpdateListener{
                    if(current()){v.progress=it.animatedValue as Float;v.invalidate()}
                }
                addListener(object:AnimatorListenerAdapter(){
                    override fun onAnimationEnd(animation:Animator){
                        if(!current())return
                        v.progress=1f;v.invalidate()
                        flightDone=true
                        trace("snapshot_flight_end")
                        maybeHandoff()
                    }
                })
                start()
            }
            // The real coordinator owns identity, rollback and input. Exactly one call.
            requestMove()
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
            animator?.removeAllListeners()
            animator?.removeAllUpdateListeners()
            animator?.cancel()
            animator=null
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

    private class FlightView(context:Context,private val incoming:Bitmap,private val outgoing:Bitmap,
                             slot:Rect,main:Rect,others:List<Rect>,private val backdrop:Drawable?):View(context){
        private fun box(r:Rect)=StageLiveSurfaceFlightGeometry.Box(r.left.toFloat(),r.top.toFloat(),r.right.toFloat(),r.bottom.toFloat())
        private val small=box(slot)
        private val large=box(main)
        private val obstacles=others.map(::box)
        private val excluded=others.map(::Rect)
        private val upCrop=StageLiveSurfaceFlightGeometry.Crop(0,0,incoming.width,incoming.height)
        private val downCrop=StageLiveSurfaceFlightGeometry.Crop(0,0,outgoing.width,outgoing.height)
        val safe=StageLiveSurfaceFlightGeometry.corridorAvoidsOtherSlots(small,large,
            incoming.width,incoming.height,obstacles,mainCropWidth=outgoing.width,mainCropHeight=outgoing.height)
        private val covered=safe && StageLiveSurfaceFlightGeometry.canUseCoveredFlight(small,large,upCrop,downCrop,obstacles)
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clip=Path()
        private val bounds=RectF()
        private val crop=Rect()
        private val mask=Path().apply{
            addRect(RectF(slot),Path.Direction.CW);addRect(RectF(main),Path.Direction.CW)
        }
        var progress=0f
        var drawn=false
            private set
        private var released=false
        init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;isClickable=false}
        override fun onDraw(canvas:Canvas){
            if(released || !canvas.isHardwareAccelerated)return
            val save=canvas.save()
            // The other three cards are never painted over, even if a rail slightly overlaps.
            excluded.forEach{canvas.clipOutRect(it)}
            val cover=canvas.save()
            canvas.clipPath(mask)
            canvas.drawColor(Color.rgb(12,14,21))
            backdrop?.setBounds(0,0,width,height);backdrop?.draw(canvas)
            canvas.restoreToCount(cover)
            drawCard(canvas,outgoing,large,small,downCrop)
            drawCard(canvas,incoming,small,large,upCrop)
            canvas.restoreToCount(save)
            drawn=true
        }
        private fun drawCard(canvas:Canvas,bitmap:Bitmap,from:StageLiveSurfaceFlightGeometry.Box,
                             to:StageLiveSurfaceFlightGeometry.Box,source:StageLiveSurfaceFlightGeometry.Crop){
            val frame=StageLiveSurfaceFlightGeometry.mirrorFrame(from,to,source,progress,covered) ?: return
            val b=frame.box
            bounds.set(b.left,b.top,b.right,b.bottom)
            crop.set(frame.crop.left,frame.crop.top,frame.crop.right,frame.crop.bottom)
            val radius=io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.radius(
                bounds.width(),bounds.height(),resources.displayMetrics.density)
            clip.reset();clip.addRoundRect(bounds,radius,radius,Path.Direction.CW)
            val saved=canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(bitmap,crop,bounds,paint)
            canvas.restoreToCount(saved)
        }
        fun release(){if(released)return;released=true;incoming.recycle();if(outgoing!==incoming)outgoing.recycle()}
    }
}
