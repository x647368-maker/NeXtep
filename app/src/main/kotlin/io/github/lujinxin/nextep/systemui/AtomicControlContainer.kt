package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Reserve space for controls, so group actions never cover the original media buttons. */
internal class AtomicControlContainer(
    context: Context,
    private val pager: ControlAreaPager,
    onSave: () -> Unit,
    onRestore: (android.view.View) -> Unit,
    onSidebar: () -> Unit,
) : FrameLayout(context), WorkspacePanelRotationContent {
    init {
        val toolbarHeight = (40 * resources.displayMetrics.density).toInt()
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            bottomMargin = toolbarHeight
        })
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(150, 25, 30, 40))
        }
        fun button(label: String, action: (android.view.View) -> Unit) {
            toolbar.addView(TextView(context).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                contentDescription = label
                setOnClickListener(action)
            }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        button("保存工作组") { onSave() }
        button("恢复工作组", onRestore)
        button("收起/展开") { onSidebar() }
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, toolbarHeight, Gravity.BOTTOM))
    }

    override fun onPanelRotationChanged(rotationDegrees: Float) = pager.onPanelRotationChanged(rotationDegrees)
}
