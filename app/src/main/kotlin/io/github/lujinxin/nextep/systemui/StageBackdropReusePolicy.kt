package io.github.x647368maker.atomicworkbench.systemui

/** Geometry-only changes never require new wallpaper captures if the buffer still matches. */
internal object StageBackdropReusePolicy {
    fun canReuse(valid: Boolean, width: Int, height: Int, rotation: Int,
        targetWidth: Int, targetHeight: Int, targetRotation: Int): Boolean =
        valid && width > 0 && height > 0 && width == targetWidth && height == targetHeight && rotation == targetRotation
}
