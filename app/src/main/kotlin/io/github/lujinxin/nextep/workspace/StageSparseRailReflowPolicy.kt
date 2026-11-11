package io.github.x647368maker.atomicworkbench.workspace

import kotlin.math.abs

/**
 * A pure one-shot FLIP plan: resize the *layout once*, then animate only
 * compositor translation and one UNIFORM scale back to identity.
 *
 * Producer virtual displays, task geometry, touches and UI chrome unchanged.
 */
internal object StageSparseRailReflowPolicy {
    const val DURATION_MS = 190L

    data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float) {
        val cx: Float get() = left + width / 2f
        val cy: Float get() = top + height / 2f
        fun valid(): Boolean = width > 1f && height > 1f &&
            listOf(left, top, width, height).all { it.isFinite() }
    }
    data class Start(val offsetX: Float, val offsetY: Float,
        val uniformScale: Float, val alpha: Float)

    fun supported(before: WorkspaceGeometry?, now: WorkspaceGeometry,
        oldCount: Int, newCount: Int): Boolean =
        before != null && oldCount in 1..4 && newCount in 1..4 &&
            oldCount != newCount && !before.railAtTop && !now.railAtTop &&
            !before.isLandscape && !now.isLandscape &&
            before.screenWidth == now.screenWidth &&
            before.screenHeight == now.screenHeight &&
            before.sidebarSide == now.sidebarSide &&
            before.displayRotation == now.displayRotation &&
            before.navigationInsets == now.navigationInsets &&
            !StageDisplayPolicy.unfoldedNearSquare(now.screenWidth, now.screenHeight)

    /**
     * Existing cards retain their exact screen-space centres at frame zero.
     * A single scale always applies to X and Y, avoiding stretch/distortion.
     * Additions fade in at the final location, never slide over menu/add.
     */
    fun start(old: Bounds?, target: Bounds): Start? {
        if (!target.valid()) return null
        if (old == null) return Start(0f, 0f, .94f, .65f)
        if (!old.valid()) return null
        val sx = old.width / target.width
        val sy = old.height / target.height
        if (!sx.isFinite() || !sy.isFinite() || abs(sx - sy) > .045f) return null
        val scale = ((sx + sy) / 2f)
        if (scale !in .55f..1.6f) return null // large jumps use safe instant layout
        return Start(old.cx - target.cx, old.cy - target.cy, scale, 1f)
    }
}
