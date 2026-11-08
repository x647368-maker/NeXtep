package io.github.x647368maker.atomicworkbench.display

/** Same aspect fit as the live TextureView; letterbox taps never become app input. */
internal data class SlotTouchProjection(val viewWidth: Int, val viewHeight: Int, val sourceWidth: Int, val sourceHeight: Int) {
    init { require(listOf(viewWidth, viewHeight, sourceWidth, sourceHeight).all { it > 0 }) }
    val scale = minOf(viewWidth.toFloat() / sourceWidth, viewHeight.toFloat() / sourceHeight)
    val left = (viewWidth - sourceWidth * scale) / 2f
    val top = (viewHeight - sourceHeight * scale) / 2f
    fun contains(x: Float, y: Float) = x >= left && y >= top && x < left + sourceWidth * scale && y < top + sourceHeight * scale
    fun sourceX(x: Float) = (x - left) / scale
    fun sourceY(y: Float) = (y - top) / scale
}
