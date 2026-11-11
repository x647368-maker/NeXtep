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
    private var retries = 0
    private val idle = Runnable { hideNow() }

    private fun arm() {
        handler.removeCallbacks(idle)
        if (enabled && !pinned && !hidden) handler.postDelayed(idle, 3500)
    }
    /** Event-driven rearm after a new foreground task or workspace activation.
     * A temporary restore/popup transition may leave allowed() false at timeout.
     * No continuous polling or repeating capture is introduced.
     */
    fun armWhenReady() {
        if (!enabled || pinned || hidden) return
        retries = 0
        arm()
    }
    fun setEnabled(value: Boolean) {
        enabled = value; prefs.edit().putBoolean("enabled", value).apply()
        retries = 0
        if (!value) stop() else interact()
    }
    fun setPinned(value: Boolean) {
        pinned = value; prefs.edit().putBoolean("pinned", value).apply()
        retries = 0
        if (value) stop() else interact()
    }
    fun interact() {
        handler.removeCallbacks(idle)
        retries = 0
        if (hidden) { hidden = false; render(false) }
        arm()
    }
    fun touch(active: Boolean) {
        // A long press/drag must never disappear underneath the user's finger.
        if (!active) { interact(); return }
        handler.removeCallbacks(idle)
        if (hidden) { hidden = false; render(false) }
    }
    fun hideNow() {
        handler.removeCallbacks(idle)
        if (!enabled || pinned || hidden) return
        if (allowed()) {
            retries = 0
            hidden = true
            render(true)
        } else if (retries < 2) {
            // Bounded one-shot recovery from a temporary task/popup transition.
            // A new user/task event can rearm later; never poll indefinitely.
            retries++
            handler.postDelayed(idle, 2000)
        }
    }
    fun stop() {
        handler.removeCallbacks(idle)
        retries = 0
        if (hidden) { hidden = false; render(false) }
    }
}
