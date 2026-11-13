package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog

/**
 * Short-lived, non-touchable full physical-screen compositor root.
 * The old NeXtepContentPanel root covers ONLY the main task rectangle and
 * cannot present a mirrored card travelling from the rail to the main task.
 * No task/VD/input owner is changed by this temporary window.
 */
internal class StageNativeSwapFullscreenHost private constructor(
    private val manager: WindowManager,
    val view: FrameLayout,
) {
    private var closed=false
    private var frameDeadline: Runnable? = null

    /** Wait for a genuine RenderThread frame: addView() alone does not make
     * rootSurfaceControl available on many ColorOS builds. One-shot fallback
     * cannot keep a window or timer alive after a canceled exchange. */
    fun afterFirstCommittedFrame(launch: () -> Unit) {
        if (closed) return
        var called=false
        val ready=Runnable {
            if (closed || called) return@Runnable
            called=true
            frameDeadline?.let { view.removeCallbacks(it) }
            frameDeadline=null
            launch()
        }
        frameDeadline=ready
        runCatching {
            val observer=view.viewTreeObserver
            if (observer.isAlive) observer.registerFrameCommitCallback {
                view.post(ready)
            }
        }.onFailure { NeXtepLog.warn("native_swap","No first-frame commit callback",it) }
        view.postInvalidateOnAnimation()
        view.postDelayed(ready, 140L)
    }

    fun close() {
        if (closed) return
        closed=true
        frameDeadline?.let { view.removeCallbacks(it) }
        frameDeadline=null
        runCatching { manager.removeViewImmediate(view) }
            .onFailure { NeXtepLog.warn("native_swap","Temporary swap root removal failed",it) }
    }

    companion object {
        fun coversDisplay(rootLeft:Int,rootTop:Int,rootWidth:Int,rootHeight:Int,
                          physicalWidth:Int,physicalHeight:Int):Boolean =
            rootLeft==0 && rootTop==0 && rootWidth>=physicalWidth &&
                rootHeight>=physicalHeight && physicalWidth>1 && physicalHeight>1

        fun open(context:Context, manager:WindowManager, type:Int,
                 width:Int,height:Int):StageNativeSwapFullscreenHost? {
            if (width<2 || height<2) return null
            val view=FrameLayout(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable=false
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // A practically invisible 1x1 draw primes the compositor
                // without a full-screen blur or persistent render loop.
                addView(View(context).apply {
                    setBackgroundColor(Color.argb(1,0,0,0))
                },FrameLayout.LayoutParams(1,1))
            }
            val flags=WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val params=WindowManager.LayoutParams(
                width,height,type,flags,PixelFormat.TRANSLUCENT
            ).apply {
                gravity=Gravity.TOP or Gravity.LEFT
                x=0
                y=0
                title="AtomicTransientSwapSurface"
                if (Build.VERSION.SDK_INT>=28) {
                    layoutInDisplayCutoutMode=
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            return runCatching {
                manager.addView(view,params)
                StageNativeSwapFullscreenHost(manager,view)
            }.onFailure {
                NeXtepLog.warn("native_swap","Full-screen temporary host unavailable",it)
            }.getOrNull()
        }
    }
}
