package io.github.x647368maker.atomicworkbench.workspace

/** Display-class-only physical hit area rules.
 * False preserves the exact legacy phone and near-square fold offsets.
 * True reserves disjoint 48dp window-grip and quick/rail buttons.
 */
internal object NativeLandscapeTabletChrome {
    fun toolbarTopDp(nativeTablet: Boolean): Int = if (nativeTablet) 2 else 40
    fun toolbarLeftDp(nativeTablet: Boolean): Int = if (nativeTablet) 64 else 8
    fun toolbarRightDp(nativeTablet: Boolean): Int = if (nativeTablet) 124 else 8

    /** The native tablet's toolbar already starts at availableTop (below status/cutout).
     * Do not exclude the legacy 40dp phone status band a second time.
     */
    fun localStatusExclusionDp(nativeTablet: Boolean): Int =
        if (nativeTablet) 0 else WorkspaceControlMetrics.STATUS_HEIGHT_DP
}
