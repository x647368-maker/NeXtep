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

/** Fully specified colors: SystemUI's inherited popup styles can mix light backgrounds
 * with dark-theme foregrounds on ColorOS. Use the same readable menu for both actions
 * and saved group selection, independent of the host's day/night theme. */
internal object AtomicPopupMenu {
    private var active: PopupWindow? = null
    fun show(context: Context, anchor: View, items: List<Pair<String, () -> Unit>>) {
        if (items.isEmpty()) return
        active?.dismiss()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val screen = context.resources.displayMetrics
        val width = minOf(dp(300), screen.widthPixels-dp(16)).coerceAtLeast(1)
        val rowHeight = dp(48)
        val padding = dp(6)
        val height = minOf(rowHeight * items.size + padding * 2,
            (screen.heightPixels * 0.75f).toInt()).coerceAtLeast(1)
        val background = GradientDrawable().apply {
            setColor(Color.rgb(30, 35, 44))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1).coerceAtLeast(1), Color.rgb(90, 100, 115))
        }
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(30, 35, 44))
        }
        val content = ScrollView(context).apply {
            this.background = background
            isFillViewport = true
            clipToOutline = true
            addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val popup = PopupWindow(content, width, height, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(background)
            elevation = dp(10).toFloat()
        }
        items.forEach { (label, action) ->
            rows.addView(TextView(context).apply {
                text = label
                contentDescription = label
                textSize = 15f
                minHeight = rowHeight
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(30, 35, 44))
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    // Finish dismissing the action menu before opening the group list.
                    anchor.post { action() }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val x = (location[0] + anchor.width / 2 - width / 2)
            .coerceIn(0, (screen.widthPixels - width).coerceAtLeast(0))
        val y = (location[1] + anchor.height).coerceIn(0,
            (screen.heightPixels - height).coerceAtLeast(0))
        active=popup
        popup.setOnDismissListener {if(active===popup)active=null}
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, x, y)
    }
}
