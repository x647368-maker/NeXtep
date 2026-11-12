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

    /** Keep legacy Ace-only *layout sizing* unchanged. Motion is device-agnostic. */
    fun trialModel(model: String): Boolean = model.equals("PMS110", ignoreCase = true)

    /**
     * Animate only a card-count change within the *same physical viewport*.
     * Fold/unfold, rotation, side/top preset changes, insets and rail relocation
     * use the existing immediate safe layout; none may interpolate stale pixels
     * or stale input geometry. Valid moving cards are then checked one by one
     * by start(): only uniform X/Y scale is accepted, otherwise instant fallback.
     */
    fun supported(before: WorkspaceGeometry?, now: WorkspaceGeometry,
        oldCount: Int, newCount: Int): Boolean =
        before != null && oldCount in 1..4 && newCount in 1..4 &&
            oldCount != newCount &&
            before.railAtTop == now.railAtTop &&
            before.isLandscape == now.isLandscape &&
            before.screenWidth == now.screenWidth &&
            before.screenHeight == now.screenHeight &&
            before.sidebarSide == now.sidebarSide &&
            before.displayRotation == now.displayRotation &&
            before.navigationInsets == now.navigationInsets

    /**
     * Capture the real on-screen rectangle of a card *before* cancelling a
     * running compositor animation. On rapid 2->3->4 changes the nominal old
     * layout is not the same as the card's rendered position.
     *
     * This math does not alter the virtual-display producer, the live buffer,
     * touch mapping or the underlying View layout.
     */
    fun visibleBounds(layout: Bounds, scaleX: Float, scaleY: Float,
        offsetX: Float, offsetY: Float): Bounds? {
        if (!layout.valid() ||
            !listOf(scaleX, scaleY, offsetX, offsetY).all { it.isFinite() } ||
            scaleX <= 0f || scaleY <= 0f || abs(scaleX - scaleY) > .045f) return null
        val visibleWidth = layout.width * scaleX
        val visibleHeight = layout.height * scaleY
        val centerX = layout.cx + offsetX
        val centerY = layout.cy + offsetY
        return Bounds(centerX - visibleWidth / 2f, centerY - visibleHeight / 2f,
            visibleWidth, visibleHeight).takeIf { it.valid() }
    }

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
