package workbench2.residentlab

import android.app.Activity
import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch

class HostActivity : Activity() {
    lateinit var root: FrameLayout
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
    }
}

/** Only a new, disposable, same-UID target. No third-party content or input bridge. */
class TargetActivity : Activity() {
    lateinit var content: TargetView
        private set
    val destroyed = CountDownLatch(1)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        content = TargetView(this)
        setContentView(content)
    }
    override fun onDestroy() { super.onDestroy(); destroyed.countDown() }
}

class TargetView(context: android.content.Context) : View(context) {
    @Volatile var touches = 0
        private set
    @Volatile var downs = 0
        private set
    @Volatile var rendered = 0
        private set
    val workloadFinished = CountDownLatch(1)
    private var remaining = 0
    private val paint = Paint()
    fun startWorkload() {
        check(remaining == 0 && rendered == 0)
        remaining = 120
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if (touches == 0) Color.RED else Color.GREEN)
        paint.color = Color.WHITE
        // Identical moving stripe on both output types; center pixel remains a state marker.
        val left = (rendered * 7 % width.coerceAtLeast(1)).toFloat()
        canvas.drawRect(left, 0f, left + 10f, 24f, paint)
        if (remaining > 0) {
            rendered++
            remaining--
            if (remaining > 0) postInvalidateOnAnimation() else workloadFinished.countDown()
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downs++
            MotionEvent.ACTION_UP -> { touches++; performClick(); invalidate() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
