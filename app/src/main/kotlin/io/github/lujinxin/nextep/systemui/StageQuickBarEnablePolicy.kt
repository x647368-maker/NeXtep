package io.github.x647368maker.atomicworkbench.systemui

/** Disabled bar never exposes a clickable shortcut or requests app recommendations. */
internal object StageQuickBarEnablePolicy {
    fun showBar(ready: Boolean, enabled: Boolean, expanded: Boolean) = ready && enabled && expanded
    fun showHandle(ready: Boolean, enabled: Boolean, expanded: Boolean) = ready && enabled && !expanded
}
