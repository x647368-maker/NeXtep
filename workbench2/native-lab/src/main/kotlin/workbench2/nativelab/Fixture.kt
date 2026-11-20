package workbench2.nativelab

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Canvas
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** One disposable native task. Same test package, separate WM task; no product data. */
object Fixture {
    const val RED=23
    const val GREEN=178
    const val BLUE=87
    @Volatile var task=-1
    @Volatile var width=0
    @Volatile var height=0
    @Volatile var dpi=0
    @Volatile var sizeEpoch=0L
    @Volatile var drawn=false
    @Volatile var focused=false
    @Volatile var touches=0
    @Volatile var destroyed=false
}
class HostActivity: Activity() {
    lateinit var slot: FrameLayout
    @Volatile var hostTouches=0
    @Volatile var destroyed=false
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=FrameLayout(this)
        root.setBackgroundColor(Color.rgb(30,35,60))
        slot=FrameLayout(this)
        slot.setOnTouchListener { _, event ->
            if(event.actionMasked==MotionEvent.ACTION_UP) hostTouches++
            true
        }
        root.addView(slot,FrameLayout.LayoutParams(-1,234,Gravity.TOP or Gravity.LEFT).apply{topMargin=76})
        setContentView(root)
    }
    override fun onDestroy(){ destroyed=true;super.onDestroy() }
}
class TargetActivity: Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        Fixture.task=taskId
        Fixture.dpi=resources.configuration.densityDpi
        setContentView(object:View(this) {
            override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
                super.onSizeChanged(w,h,oldw,oldh)
                Fixture.width=w;Fixture.height=h;Fixture.sizeEpoch++
            }
            override fun onDraw(canvas:Canvas) {
                canvas.drawColor(Color.rgb(Fixture.RED,Fixture.GREEN,Fixture.BLUE))
                Fixture.drawn=true
            }
            override fun onTouchEvent(event:MotionEvent):Boolean {
                if(event.actionMasked==MotionEvent.ACTION_UP) Fixture.touches++
                return true
            }
        })
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus);Fixture.focused=hasFocus
    }
    override fun onDestroy() { Fixture.destroyed=true;super.onDestroy() }
}
