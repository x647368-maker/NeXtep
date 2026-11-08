package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.x647368maker.atomicworkbench.apps.LauncherAppRepository

internal class StageQuickBarView(context: Context, private val choose: (LauncherAppRepository.AppEntry, View, Boolean) -> Unit,
    private val add: (View) -> Unit, private val collapse: () -> Unit) : LinearLayout(context) {
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    init {
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Color.argb(160, 35, 38, 44)); cornerRadius = dp(22).toFloat() }
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; addView(row)
        }, LayoutParams(0, dp(52), 1f))
        addView(TextView(context).apply {
            text = "⌃"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = "收起常用应用快捷栏"; setOnClickListener { collapse() }
        }, LayoutParams(dp(44), dp(48)))
    }
    fun render(entries: List<LauncherAppRepository.AppEntry>) {
        row.removeAllViews()
        entries.forEach { entry ->
            val icon = ImageView(context).apply {
                setPadding(dp(7), dp(7), dp(7), dp(7)); scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(entry.icon.constantState?.newDrawable(resources)?.mutate() ?: entry.icon)
                contentDescription = entry.label
                setOnClickListener { choose(entry, this, false) }
                setOnLongClickListener { choose(entry, this, true); true }
            }
            row.addView(icon, LayoutParams(dp(48), dp(48)))
        }
        row.addView(TextView(context).apply {
            text = "+"; textSize = 25f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            contentDescription = "选择应用，长按可固定"; setOnClickListener { add(this) }
        }, LayoutParams(dp(44), dp(48)))
    }
}
