package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.min

/**
 * One-tap RenderThread preview -> real main-task presentation handoff.
 *
 * This policy only decides how to retire an already verified temporary
 * preview. It never moves tasks, assumes a new producer frame was presented,
 * relaxes a task owner check, or keeps a bitmap after the exchange.
 */
internal object StageMainEndpointRevealPolicy {
    // Six frames at 120 Hz, short enough to avoid holding the verified app.
    const val BLEND_MS=48L
    // Permit a missing OEM animator-end callback without creating a second
    // unbounded wait. This is inside the existing per-attempt lifetime.
    const val MAX_BLEND_WAIT_MS=72L

    /**
     * A contained source bitmap with a different aspect ratio occupies less
     * than the target. Dropping it in one traversal causes a visible "second
     * zoom" as the real task finally fills the true main-card geometry.
     * Crossfade only material (>2.5% area) endpoint contain differences.
     */
    fun needsBlend(bitmapWidth:Int, bitmapHeight:Int,
                   targetWidth:Float, targetHeight:Float):Boolean {
        if(bitmapWidth<2 || bitmapHeight<2 ||
            !targetWidth.isFinite() || !targetHeight.isFinite() ||
            targetWidth<2f || targetHeight<2f)return false
        val factor=min(targetWidth.toDouble()/bitmapWidth,
            targetHeight.toDouble()/bitmapHeight)
        val coverage=(bitmapWidth*factor)*(bitmapHeight*factor)/
            (targetWidth.toDouble()*targetHeight)
        return coverage.isFinite() && coverage<0.975
    }

    /** Never unmask a real main window before migration and endpoint proof. */
    fun canBegin(acknowledged:Boolean, terminalFrameConfirmed:Boolean,
                 terminalReceiptMissing:Boolean, mainStable:Boolean,
                 alreadyBegun:Boolean):Boolean =
        acknowledged && terminalFrameConfirmed && !terminalReceiptMissing &&
        mainStable && !alreadyBegun

    /** Prevent an immediate teardown mid-blend; never wait beyond 72ms. */
    fun keepPreviewDuringReady(blendBegun:Boolean, blendComplete:Boolean,
                               elapsedMs:Long):Boolean =
        blendBegun && !blendComplete && elapsedMs>=0L &&
        elapsedMs<MAX_BLEND_WAIT_MS
}
