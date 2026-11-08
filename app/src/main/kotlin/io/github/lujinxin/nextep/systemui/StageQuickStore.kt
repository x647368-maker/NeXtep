package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context

internal class StageQuickStore(context: Context) {
    var layoutScope: String? = null
    private fun layoutKey(field: String) = io.github.x647368maker.atomicworkbench.workspace.StageDisplayPolicy.layoutKey(layoutScope, field)
    private val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("stage_core_v09", Context.MODE_PRIVATE)
    private var state: StageQuickState
        get() = StageQuickState.decode(prefs.getString("quick_state", null))
        set(v) { prefs.edit().putString("quick_state", v.encode()).apply() }
    var enabled: Boolean
        get() = state.enabled
        set(v) { state = state.copy(enabled = v) }
    var expanded: Boolean
        get() = state.expanded
        set(v) { state = state.copy(expanded = v) }
    var fixed: Boolean
        get() = state.fixed
        set(v) { state = state.copy(fixed = v) }
    var mainPriority: Boolean
        get() = prefs.getBoolean(layoutKey("main_priority"), prefs.getBoolean("main_priority", true))
        set(v) { prefs.edit().putBoolean(layoutKey("main_priority"), v).apply() }
    var adaptive: Boolean
        get() = prefs.getBoolean(layoutKey("adaptive"), prefs.getBoolean("adaptive", true))
        set(v) { prefs.edit().putBoolean(layoutKey("adaptive"), v).apply() }
    var pins: List<String>
        get() = state.pins
        set(v) { state = state.copy(pins = StageQuickPolicy.normalize(v)) }
    var cinemaMode: StageCinemaPolicy.Mode
        get() = runCatching { StageCinemaPolicy.Mode.valueOf(prefs.getString("cinema", "AUTO")!!) }.getOrDefault(StageCinemaPolicy.Mode.AUTO)
        set(v) { prefs.edit().putString("cinema", v.name).apply() }
}
