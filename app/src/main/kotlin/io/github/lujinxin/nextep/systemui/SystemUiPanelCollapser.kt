package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog

object SystemUiPanelCollapser {
    fun collapse(context: Context) { tryCollapse(context) }

    fun tryCollapse(context: Context): Boolean = runCatching {
            val manager = context.getSystemService("statusbar")
                ?: error("StatusBarManager unavailable")
            manager.javaClass.getMethod("collapsePanels").invoke(manager)
            true
        }.onFailure { error ->
            NeXtepLog.warn("systemui_panel", "Unable to collapse SystemUI panels", error)
        }.getOrDefault(false)
}
