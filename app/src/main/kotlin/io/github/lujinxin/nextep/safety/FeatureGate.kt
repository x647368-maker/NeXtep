package io.github.x647368maker.atomicworkbench.safety

enum class FeatureGate(val defaultEnabled: Boolean) {
    TOP_RIGHT_STATUS_BAR_GESTURE(true),
    SYSTEM_UI(true),
    LAUNCHER_TRANSFORM(true),
    VIRTUAL_DISPLAY_SLOTS(true),
    TASK_SWAP(true),
    SYSTEM_SERVER_PATCHES(true),
    FIXED_ORIENTATION_LETTERBOX(true),
    SIZE_COMPAT_DISPLAY_INSETS(true),
}
