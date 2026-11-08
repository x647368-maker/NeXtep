package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** Keep the existing blurred card background, but do not paint it over the enlarged main. */
internal class MainFooterBackdrop(private val background: Drawable, private val main: Rect) : Drawable() {
    override fun draw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipOutRect(main)
        background.draw(canvas)
        canvas.restoreToCount(saved)
    }
    override fun onBoundsChange(bounds: Rect) { background.bounds = bounds }
    override fun setAlpha(alpha: Int) { background.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { background.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
