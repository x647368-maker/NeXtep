package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

/**
 * A same-viewport task exchange must not be cancelled by transient slot
 * Occupied -> Ready -> Occupied callbacks, or by a relayout caused only by
 * those callbacks. Screen rotation, folding, navigation-inset changes and
 * explicit layout-mode changes remain authoritative and cancel normally.
 *
 * This changes ONLY presentation timing. Task migration and identity remain
 * owned by SlotTaskCoordinator and the current exchange session.
 */
internal object StageSwapViewportHoldPolicy {
    fun retain(overlayRunning: Boolean, captured: WorkspaceGeometry?, now: WorkspaceGeometry): Boolean =
        overlayRunning && captured != null &&
            captured.screenWidth == now.screenWidth &&
            captured.screenHeight == now.screenHeight &&
            captured.displayRotation == now.displayRotation &&
            captured.navigationInsets == now.navigationInsets &&
            captured.isLandscape == now.isLandscape &&
            captured.railAtTop == now.railAtTop &&
            captured.sidebarSide == now.sidebarSide
}
