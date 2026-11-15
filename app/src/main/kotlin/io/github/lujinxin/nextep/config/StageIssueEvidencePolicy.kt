package io.github.x647368maker.atomicworkbench.config

/** Existing user actions only: collect command responses in the selected scope. */
internal object StageIssueEvidencePolicy {
    fun commandLabel(name: String, scope: Set<String>): String? {
        val entry = when (name) {
            "save", "new", "restore", "delete", "rename" -> "services" to "功能服务/工作组/$name"
            "quick_enable", "quick_off", "quick_collapse", "quick_expand", "quick_auto", "quick_fixed" -> "services" to "功能服务/快捷栏/$name"
            "add_window", "replace_window", "close_window", "main_priority", "main_complete",
            "layout_side", "layout_top", "adaptive_on", "adaptive_off", "left", "right",
            "collapse", "expand" -> "layout" to "布局/$name"
            "cinema_auto", "cinema_landscape", "cinema_portrait", "cinema_off",
            "cinema_enter", "cinema_leave", "wechat_move" -> "content" to "应用操作/$name"
            else -> return null
        }
        return entry.second.takeIf { entry.first in scope }
    }
}
