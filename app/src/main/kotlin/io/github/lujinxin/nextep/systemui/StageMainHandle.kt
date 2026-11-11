package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import kotlin.math.abs

/** A small main-window grip; never intercept the app's own scroll/back gestures. */
internal class StageMainHandle(context: Context, private val menu: () -> Unit,
    private val close: () -> Unit, private val quickBarTap: (() -> Boolean)? = null
) : TextView(context) {
    private var x = 0f
    private var y = 0f
    private var touchActive = false
    private var longPressHandled = false
    private val longPress = Runnable {
        if (touchActive) {
            longPressHandled = performLongClick()
        }
    }

    fun setTabletQuickBarShortcut(enabled: Boolean) {
        contentDescription = if (enabled)
            "点击展开或收起常用应用栏，长按主窗菜单；在把手上向左滑动关闭主窗"
        else "主窗菜单；在把手上向左滑动关闭主窗"
    }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    init {
        text = "⋯"; textSize = 22f; gravity = Gravity.CENTER
        setTextColor(Color.rgb(145, 145, 150))
        background=GradientDrawable().apply {setColor(Color.argb(190,35,38,44));cornerRadius=16*resources.displayMetrics.density}
        setShadowLayer(resources.displayMetrics.density, 0f, 0f, Color.argb(140, 255, 255, 255))
        contentDescription = "主窗菜单；在把手上向左滑动关闭主窗"
        isClickable = true
        isLongClickable = true
        setOnClickListener { if (quickBarTap?.invoke() != true) menu() }
        // The main menu remains reachable on Y700. All other devices retain
        // the original single-tap action and left-swipe-to-close gesture.
        setOnLongClickListener { menu(); true }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.rawX; y = event.rawY
                touchActive = true
                longPressHandled = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.rawX - x) > slop || abs(event.rawY - y) > slop) {
                    removeCallbacks(longPress)
                    touchActive = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                touchActive = false
                if (longPressHandled) return true
                val dx = event.rawX - x; val dy = event.rawY - y
                if (dx < -48 * resources.displayMetrics.density && abs(dx) > abs(dy) * 1.4f) close()
                else if (abs(dx) < slop && abs(dy) < slop) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                touchActive = false
                return true
            }
        }
        return true
    }
}
