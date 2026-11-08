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
    var onVisibilityChanged: (Boolean) -> Unit = {}
    val isShowing get() = popup?.isShowing == true
    fun dismiss() { popup?.dismiss(); popup = null }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val dark get() = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val textColor get() = if (dark) Color.WHITE else Color.rgb(28, 28, 30)
    fun label(value: String, size: Float = 15f) = TextView(context).apply {
        text = value; textSize = size; setTextColor(textColor)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }
    fun surface(active: Boolean = false) = GradientDrawable().apply {
        setColor(if (dark) Color.rgb(35, 35, 38) else Color.rgb(248, 248, 250))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1).coerceAtLeast(1), if (active) Color.rgb(121, 218, 223) else Color.argb(32, 128, 128, 128))
    }
    fun show(anchor: View, title: String, rows: LinearLayout, fillContent: Boolean = false) {
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
            if (!fillContent) addView(rows, ViewGroup.LayoutParams(-1, -2))
        }
        if (fillContent) root.addView(rows, LinearLayout.LayoutParams(-1, 0, 1f))
        else root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, if (fillContent) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST))
        val height = root.measuredHeight.coerceAtMost(maxHeight)
        popup = PopupWindow(root, width, height, true).apply {
            isOutsideTouchable = true
            setOnDismissListener { onVisibilityChanged(false) }
            setBackgroundDrawable(surface())
            elevation = dp(8).toFloat()
            windowLayoutType = SystemUiWindowTypeResolver.resolve() ?: android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            onVisibilityChanged(true)
            showAtLocation(anchor, Gravity.TOP or Gravity.LEFT,
                (screen.widthPixels - width) / 2,
                (location[1] + anchor.height + dp(8)).coerceIn(0, (screen.heightPixels - height).coerceAtLeast(0)))
        }
    }
}
