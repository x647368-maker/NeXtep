package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** Native, bounded workspace sheets. Task icons are identities, never simulated apps. */
internal class AtomicPanel(private val context: Context) {
    private var popup: PopupWindow? = null
    fun dismiss() { popup?.dismiss(); popup = null }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun label(value: String, size: Float = 15f) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    fun surface(active: Boolean = false) = GradientDrawable().apply {
        setColor(if (active) Color.rgb(29, 65, 76) else Color.rgb(35, 42, 52))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1).coerceAtLeast(1), if (active) Color.rgb(121, 218, 223) else Color.rgb(65, 76, 89))
    }
    fun show(anchor: View, title: String, rows: LinearLayout) {
        dismiss()
        if (!anchor.isAttachedToWindow) return
        val screen = context.resources.displayMetrics
        val location = IntArray(2); anchor.getLocationOnScreen(location)
        val width = minOf(dp(420), (screen.widthPixels * .94f).toInt())
        val maxHeight = (screen.heightPixels * .72f).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = surface()
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(title, 20f), LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(label("关闭").apply { setOnClickListener { dismiss() } }, LinearLayout.LayoutParams(dp(64), dp(48)))
        root.addView(header)
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(rows, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = root.measuredHeight.coerceAtMost(maxHeight)
        popup = PopupWindow(root, width, height, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(surface())
            elevation = dp(20).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            showAtLocation(anchor, Gravity.TOP or Gravity.LEFT,
                (screen.widthPixels - width) / 2,
                (location[1] + anchor.height + dp(8)).coerceIn(0, (screen.heightPixels - height).coerceAtLeast(0)))
        }
    }
}
