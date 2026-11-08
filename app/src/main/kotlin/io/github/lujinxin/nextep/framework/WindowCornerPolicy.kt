package io.github.x647368maker.atomicworkbench.framework

import kotlin.math.min

/** Radius is specified in visible pixels, then inverted into task surface coordinates. */
internal object WindowCornerPolicy {
    fun radius(width: Float, height: Float, density: Float): Float =
        min(16f * density, min(width, height) * .08f).coerceAtLeast(0f)
    fun sourceRadius(scaleX: Float, scaleY: Float, width: Int, height: Int, density: Float, cropped: Boolean): Float {
        val scale = min(scaleX, scaleY)
        if (!cropped || !scale.isFinite() || scale <= 0f || scale >= 1f || width <= 0 || height <= 0) return 0f
        val fade = ((1f - scale) / .08f).coerceIn(0f, 1f)
        return radius(width * scaleX, height * scaleY, density) / scale * fade
    }
}
