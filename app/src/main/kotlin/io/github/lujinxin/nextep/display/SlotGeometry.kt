package io.github.x647368maker.atomicworkbench.display

import android.content.Context

/**
 * Recorded physical portrait canvas, shared with the main display. The preview
 * scales this canvas rather than changing app resolution for each rail slot.
 * Moving a landscape task between displays can still cause an Android configuration change.
 */
data class SlotGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
) {
    init {
        require(width > 0 && height > 0) { "Slot dimensions must be positive" }
        require(densityDpi > 0) { "Slot density must be positive" }
    }

    companion object {
        /**
         * Keeps the default display's portrait width and density, independent of main rotation.
         */
        fun matchingDefaultDisplay(context: Context): SlotGeometry {
            val displayMetrics = context.resources.displayMetrics
            require(displayMetrics.widthPixels > 0 && displayMetrics.heightPixels > 0) {
                "Default display dimensions must be usable"
            }
            return forRecordedDisplay(displayMetrics.widthPixels, displayMetrics.heightPixels,
                displayMetrics.densityDpi)
        }

        fun forRecordedDisplay(width: Int, height: Int, densityDpi: Int) =
            SlotGeometry(minOf(width, height), maxOf(width, height), densityDpi)
    }
}
