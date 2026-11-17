package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Canvas-preview background and contain geometry contract.
 *
 * A task image is always contained, never stretched to hide an uncovered
 * strip. The strip must show a previously prepared opaque workbench material,
 * not an implicit black fill or the old task still rendering underneath.
 * No capture, allocation, or producer query is performed on an animation frame.
 */
internal object StagePreviewFillPolicy {
    data class Frame(
        val content: StageFixedFrameGeometry.FittedRect,
        val horizontalGap: Float,
        val verticalGap: Float,
    ) {
        val hasUncoveredArea: Boolean get() = horizontalGap > .5f || verticalGap > .5f
    }

    fun frame(
        bitmapWidth: Int, bitmapHeight: Int,
        left: Float, top: Float, right: Float, bottom: Float,
    ): Frame {
        val content=StageFixedFrameGeometry.fit(bitmapWidth,bitmapHeight,left,top,right,bottom)
        return Frame(content,
            ((content.left-left)+(right-content.right)).coerceAtLeast(0f),
            ((content.top-top)+(bottom-content.bottom)).coerceAtLeast(0f))
    }

    /** A translucent wallpaper drawable alone cannot conceal a real old task. */
    fun canStart(opaquePreparedUnderlay: Boolean, opaqueDrawable: Boolean): Boolean =
        opaquePreparedUnderlay || opaqueDrawable

    /**
     * Reuse the already cached software wallpaper. One opaque pixel supplies a
     * matching safety color *under* the complete wallpaper drawable, so an
     * alpha hole cannot reveal a stale task; it is not the visible letterbox.
     * Transparent, recycled and protected/HARDWARE sources are not invented.
     */
    fun sampledOpaqueUnderlay(bitmap: Bitmap?): Int? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 ||
            bitmap.config == Bitmap.Config.HARDWARE) return null
        return runCatching {
            bitmap.getPixel(bitmap.width/2,bitmap.height/2)
                .takeIf { Color.alpha(it)==255 }
        }.getOrNull()
    }
}
