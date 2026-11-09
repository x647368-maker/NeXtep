package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context
import android.os.Handler

/** Optional idle hiding of the existing host. Never changes viewport or task state. */
internal class StageSmartRail(context: Context, private val handler: Handler,
    private val allowed: () -> Boolean, private val render: (Boolean) -> Unit) {
    private val prefs = context.getSharedPreferences("atomic_smart_rail_v1", Context.MODE_PRIVATE)
    var enabled = prefs.getBoolean("enabled", false); private set
    var pinned = prefs.getBoolean("pinned", false); private set
    var hidden = false; private set
    private val idle = Runnable { hideNow() }
    fun setEnabled(value: Boolean) {
        enabled = value; prefs.edit().putBoolean("enabled", value).apply(); interact()
    }
    fun setPinned(value: Boolean) {
        pinned = value; prefs.edit().putBoolean("pinned", value).apply(); interact()
    }
    fun interact() {
        handler.removeCallbacks(idle)
        if (hidden) { hidden = false; render(false) }
        if (enabled && !pinned) handler.postDelayed(idle, 3500)
    }
    fun hideNow() {
        handler.removeCallbacks(idle)
        if (enabled && !pinned && !hidden && allowed()) { hidden = true; render(true) }
    }
    fun stop() {
        handler.removeCallbacks(idle)
        if (hidden) { hidden = false; render(false) }
    }
}
