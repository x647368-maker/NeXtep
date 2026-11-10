package io.github.x647368maker.atomicworkbench.systemui

/** Pure geometry for undistorted static previews during a tap-to-swap. */
internal object StageFixedFrameGeometry {
    data class FittedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    fun fit(bitmapWidth: Int, bitmapHeight: Int,
            frameLeft: Float, frameTop: Float, frameRight: Float, frameBottom: Float): FittedRect {
        require(bitmapWidth > 0 && bitmapHeight > 0)
        require(frameLeft.isFinite() && frameTop.isFinite() && frameRight.isFinite() && frameBottom.isFinite())
        require(frameRight > frameLeft && frameBottom > frameTop)
        val frameWidth = frameRight - frameLeft
        val frameHeight = frameBottom - frameTop
        val scale = minOf(frameWidth / bitmapWidth, frameHeight / bitmapHeight)
        val width = bitmapWidth * scale
        val height = bitmapHeight * scale
        val left = frameLeft + (frameWidth - width) / 2f
        val top = frameTop + (frameHeight - height) / 2f
        return FittedRect(left, top, left + width, top + height)
    }
}
