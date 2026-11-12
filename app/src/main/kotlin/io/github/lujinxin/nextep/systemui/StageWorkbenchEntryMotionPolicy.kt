package io.github.x647368maker.atomicworkbench.systemui

/**
 * One-shot geometry for opening the *real* workbench. Only already-attached
 * presentation hosts are animated: no task/VirtualDisplay recreation,
 * screenshot substitution, new overlay windows or repeating renderer.
 *
 * The real main-task leash remains owned by TaskSurfaceCompat. Keep the
 * system navigation handles and top-level controls at their final scale.
 */
internal object StageWorkbenchEntryMotionPolicy {
    data class Start(val scale: Float, val offsetXdp: Int, val offsetYdp: Int, val alpha: Float)

    fun enabled(animatorsEnabled: Boolean, powerSave: Boolean): Boolean =
        animatorsEnabled && !powerSave

    fun start(title: String, railAtTop: Boolean, railOnRight: Boolean): Start = when (title) {
        "NeXtepContentPanel" -> Start(.975f, 0, 8, .82f)
        "NeXtepSidebar" -> when {
            railAtTop -> Start(.95f, 0, -14, .82f)
            railOnRight -> Start(.95f, -18, 0, .82f)
            else -> Start(.95f, 18, 0, .82f)
        }
        "NeXtepTopBar" -> Start(1f, 0, -10, 0f)
        // Never scale system handles: preserve hit regions and navigation affordances.
        else -> Start(1f, 0, 0, 0f)
    }
}
