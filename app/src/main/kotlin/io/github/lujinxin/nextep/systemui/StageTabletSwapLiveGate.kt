package io.github.x647368maker.atomicworkbench.systemui

/**
 * The existing swap handoff observes two consecutive UI checks, but both
 * checks can inspect the SAME newly submitted TextureView buffer on a vendor
 * ROM. A brief task reparent can then expose a blank compositor frame.
 *
 * Hold a real (not synthesized) new buffer for a short stable dwell before
 * releasing the existing transient swap overlay. The existing 600ms maximum
 * wait is still owned by WorkspaceSwapAnimator and always wins on timeout.
 * This gate never captures an extra screenshot, resizes a virtual display or
 * touches a third-party process.
 */
internal class StageTabletSwapLiveGate(
    private val stableDwellMs: Long = 48L,
) {
    private var readySince: Long = -1L

    fun ready(nowMs: Long, actualFrameReady: Boolean): Boolean {
        if (!actualFrameReady) {
            readySince = -1L
            return false
        }
        if (readySince < 0L || nowMs < readySince) readySince = nowMs
        return nowMs - readySince >= stableDwellMs
    }
}
