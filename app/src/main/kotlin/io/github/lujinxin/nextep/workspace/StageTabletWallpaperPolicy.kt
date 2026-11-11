package io.github.x647368maker.atomicworkbench.workspace

/**
 * WorkspacePanelHost draws native wide tablets in physical display axes (0°),
 * even when a ported ColorOS reports displayRotation 1 or 3. Rotating the crop
 * geometry back to a phone's portrait axis makes top and side wallpaper
 * fragments sample different screen positions.
 *
 * Keep the phone/fold rotation projection untouched. A stale wallpaper sample
 * must not be stretched into a different display orientation; the system live
 * wallpaper remains visible through the translucent fallback until recapture.
 */
internal object StageTabletWallpaperPolicy {
    /**
     * The TopBar already owns FLAG_SHOW_WALLPAPER and disables wallpaper
     * touch events. On a Y700-style wide screen, a screenshot copy sampled
     * beside the *live* wallpaper will be out of phase during rotation.
     * Draw no duplicate pixels in the rail or the top controls instead.
     */
    fun useLiveSystemWallpaper(g: WorkspaceGeometry): Boolean =
        StageDisplayPolicy.wideTablet(g.screenWidth, g.screenHeight)

    fun panel(g: WorkspaceGeometry): WorkspaceGeometry =
        if (StageDisplayPolicy.wideTablet(g.screenWidth, g.screenHeight)) g
        else g.rotatedTo(0)

    fun frameMatches(g: WorkspaceGeometry, width: Int, height: Int, sampleRotation: Int): Boolean =
        !StageDisplayPolicy.wideTablet(g.screenWidth, g.screenHeight) ||
            (width == g.screenWidth && height == g.screenHeight &&
                sampleRotation == g.displayRotation)
}
