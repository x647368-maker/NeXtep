package io.github.x647368maker.atomicworkbench.workspace

/**
 * A foldable side-rail width transition changes the visible main task surface
 * without changing the app's fullscreen source bounds.
 *
 * Restrict a delayed one-shot Shell transform reassertion to that transition;
 * ordinary phone and wide tablet layouts are never opted in.
 */
internal object StageN5RailRefitPolicy {
    fun shouldReassert(before: WorkspaceGeometry, after: WorkspaceGeometry): Boolean =
        StageDisplayPolicy.unfoldedNearSquare(after.screenWidth, after.screenHeight) &&
            before.screenWidth == after.screenWidth &&
            before.screenHeight == after.screenHeight &&
            before.displayRotation == after.displayRotation &&
            before.navigationInsets == after.navigationInsets &&
            before.sidebarSide == after.sidebarSide &&
            !before.railAtTop && !after.railAtTop &&
            before.sidebarWidth != after.sidebarWidth &&
            (before.contentScale != after.contentScale ||
                before.contentTranslationX != after.contentTranslationX ||
                before.contentTranslationY != after.contentTranslationY)
}
