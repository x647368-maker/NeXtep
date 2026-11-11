package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.WORKSPACE_SLOT_COUNT
import kotlin.math.roundToInt

data class WorkspaceNavigationInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

data class WorkspaceGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val topHeight: Int,
    val sidebarWidth: Int,
    val sidebarSide: SidebarSide,
    val navigationInsets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(),
    val displayRotation: Int = 0,
    val railAtTop: Boolean = false,
    val mainTopOverlap: Int = 0,
    val mainInset: Int = 0,
) {
    val isLandscape: Boolean get() = screenWidth > screenHeight
    /**
     * A wide, high-resolution display may report rotation 0/2 or 1/3 under
     * a ported phone ROM. Use physical dimensions, not ROM brand/rotation,
     * to keep tablet panels upright. This does NOT force side rails into
     * top/bottom rails: railAtTop is a separate explicit user choice.
     * Existing source/bridge records (v18) remain unchanged.
     */
    val isNativeLandscapeRail: Boolean get() =
        !railAtTop && StageDisplayPolicy.wideLandscapeTablet(screenWidth, screenHeight)
    // Surface rotations are quarter turns from the phone's natural portrait axes.
    // Keep controls at the physical phone top while the main app follows the display.
    val isControlOnLeft: Boolean get() = isLandscape && displayRotation == 1
    val panelRotationDegrees: Float get() = if (railAtTop || !isLandscape || isNativeLandscapeRail) 0f else if (isControlOnLeft) -90f else 90f
    // Project the selected button's unchanged diagram into display coordinates.
    // Rotation never changes the selected layout or the diagram in panel axes.
    val panelSidebarSide: SidebarSide get() = SidebarButtonLayout.sideInPanel(sidebarSide)
    val isSidebarAtTop: Boolean get() =
        if (isNativeLandscapeRail) false
        else SidebarButtonLayout.isAtScreenTop(sidebarSide, isLandscape, isControlOnLeft)

    // Insets constrain the DESTINATION only. Source task coordinates always start
    // at (0,0) and include the complete physical fullscreen task, including edges.
    val availableLeft: Int get() = navigationInsets.left
    val availableTop: Int get() = navigationInsets.top
    val availableRight: Int get() = screenWidth - navigationInsets.right
    val availableBottom: Int get() = screenHeight - navigationInsets.bottom
    val availableWidth: Int get() = availableRight - availableLeft
    val availableHeight: Int get() = availableBottom - availableTop

    init {
        require(screenWidth > 1 && screenHeight > 1)
        require(displayRotation in 0..3)
        require(navigationInsets.left >= 0 && navigationInsets.top >= 0 &&
            navigationInsets.right >= 0 && navigationInsets.bottom >= 0)
        require(navigationInsets.left <= screenWidth - 2 &&
            navigationInsets.right <= screenWidth - navigationInsets.left - 2)
        require(navigationInsets.top <= screenHeight - 2 &&
            navigationInsets.bottom <= screenHeight - navigationInsets.top - 2)
        require(availableWidth > 1 && availableHeight > 1)
        require(mainInset >= 0 && mainInset * 2 < minOf(availableWidth, availableHeight))
        require(mainTopOverlap in 0..maxOf(screenWidth, screenHeight))
        require(topHeight in 1 until (if (isLandscape) availableWidth else availableHeight))
        require(!railAtTop || topHeight + sidebarWidth < availableHeight)
        require(sidebarWidth in 1 until (if (isLandscape) availableHeight else availableWidth))
    }

    // Control-strip thickness and slot-rail thickness use portrait logical axes.
    val controlWidth: Int get() = if (isLandscape && !railAtTop && !isNativeLandscapeRail) topHeight else availableWidth
    val controlHeight: Int get() = if (isLandscape && !railAtTop && !isNativeLandscapeRail) availableHeight else topHeight
    val controlLeft: Int get() = if (isLandscape && !railAtTop && !isNativeLandscapeRail && !isControlOnLeft) availableRight - topHeight else availableLeft
    val controlTop: Int get() = availableTop
    // The rail and toolbar reserve space; the real main task fits uniformly in
    // the remainder. Extra height becomes wallpaper breathing room, not a tall toolbar.
    val sidebarLeft: Int get() = if (railAtTop) availableLeft
        else if (isNativeLandscapeRail) if (sidebarSide == SidebarSide.LEFT) availableLeft else availableRight - sidebarWidth
        else if (isLandscape) availableLeft + (if (isControlOnLeft) topHeight else 0)
        else if (sidebarSide == SidebarSide.LEFT) availableLeft else availableRight - sidebarWidth
    val sidebarTop: Int get() = if (railAtTop || isNativeLandscapeRail || !isLandscape) availableTop + topHeight else
        if (isSidebarAtTop) availableTop else availableBottom - sidebarWidth
    val sidebarPhysicalWidth: Int get() = if (railAtTop) availableWidth else if (isNativeLandscapeRail) sidebarWidth else if (isLandscape) availableWidth - topHeight else sidebarWidth
    val sidebarPhysicalHeight: Int get() = if (railAtTop) sidebarWidth else if (isNativeLandscapeRail) availableHeight - topHeight else if (isLandscape) sidebarWidth else availableHeight - topHeight
    val sidebarLogicalHeight: Int get() = if (railAtTop || isNativeLandscapeRail) sidebarPhysicalHeight else if (isLandscape) sidebarPhysicalWidth else sidebarPhysicalHeight

    private val regionWidth: Int get() = (if (railAtTop) availableWidth else availableWidth - if (isNativeLandscapeRail) sidebarWidth else if (isLandscape) topHeight else sidebarWidth) - 2 * mainInset
    private val effectiveMainOverlap: Int get() = if (railAtTop) minOf(mainTopOverlap, sidebarWidth - 1) else 0
    private val regionHeight: Int get() = (if (railAtTop) availableHeight - topHeight - sidebarWidth + effectiveMainOverlap
        else if (isNativeLandscapeRail) availableHeight - topHeight
        else availableHeight - if (isLandscape) sidebarWidth else topHeight) - 2 * mainInset
    val contentScale: Float get() = minOf(regionWidth.toFloat() / screenWidth, regionHeight.toFloat() / screenHeight)
    val contentWidth: Int get() = (screenWidth * contentScale).roundToInt().coerceAtLeast(1)
    val contentHeight: Int get() = (screenHeight * contentScale).roundToInt().coerceAtLeast(1)
    val contentTop: Int get() = availableTop + mainInset + (if (railAtTop) topHeight + sidebarWidth - effectiveMainOverlap
        else if (isNativeLandscapeRail) topHeight
        else if (!isLandscape) topHeight else if (isSidebarAtTop) sidebarWidth else 0) +
        (regionHeight - contentHeight) / 2
    val contentBottom: Int get() = contentTop + contentHeight
    val contentLeft: Int get() = availableLeft + mainInset + (if (railAtTop) 0 else if (isNativeLandscapeRail) {
        if (sidebarSide == SidebarSide.LEFT) sidebarWidth else 0
    } else if (isLandscape) {
        if (isControlOnLeft) topHeight else 0
    } else if (sidebarSide == SidebarSide.LEFT) sidebarWidth else 0) +
        (regionWidth - contentWidth + if (!railAtTop && isLandscape && !isControlOnLeft && !isNativeLandscapeRail) 1 else 0) / 2

    val contentRight: Int
        get() = contentLeft + contentWidth

    val contentTranslationX: Float
        get() = contentLeft + (contentWidth - screenWidth * contentScale) / 2f

    val contentTranslationY: Float
        get() = contentTop + (contentHeight - screenHeight * contentScale) / 2f

    /** Project a recorded layout; rotating must never recalibrate its thicknesses. */
    fun rotatedTo(rotation: Int): WorkspaceGeometry {
        require(rotation in 0..3)
        val turns = (rotation - displayRotation + 4) % 4
        if (turns == 0) return this
        val edges = listOf(navigationInsets.left, navigationInsets.top,
            navigationInsets.right, navigationInsets.bottom)
        return copy(
            screenWidth = if (turns % 2 == 0) screenWidth else screenHeight,
            screenHeight = if (turns % 2 == 0) screenHeight else screenWidth,
            navigationInsets = WorkspaceNavigationInsets(
                edges[turns], edges[(turns + 1) % 4],
                edges[(turns + 2) % 4], edges[(turns + 3) % 4],
            ),
            displayRotation = rotation,
        )
    }

    fun isRotationOf(previous: WorkspaceGeometry): Boolean =
        displayRotation != previous.displayRotation && this == previous.rotatedTo(displayRotation)

    companion object {
        const val TOP_HEIGHT_FRACTION = 0.265f
        const val SIDEBAR_WIDTH_FRACTION = 0.267f

        fun forDisplay(
            screenWidth: Int,
            screenHeight: Int,
            sidebarSide: SidebarSide = SidebarSide.RIGHT,
            density: Float,
            navigationInsets: WorkspaceNavigationInsets = WorkspaceNavigationInsets(),
            displayRotation: Int = 0,
        ): WorkspaceGeometry {
            require(screenWidth > 1 && screenHeight > 1) { "Display dimensions must be usable" }
            require(density.isFinite() && density > 0f) { "Display density must be positive and finite" }
            val left = navigationInsets.left.coerceIn(0, screenWidth - 2)
            val top = navigationInsets.top.coerceIn(0, screenHeight - 2)
            val safeInsets = WorkspaceNavigationInsets(
                left, top,
                navigationInsets.right.coerceIn(0, screenWidth - left - 2),
                navigationInsets.bottom.coerceIn(0, screenHeight - top - 2),
            )
            val availableWidth = screenWidth - safeInsets.left - safeInsets.right
            val availableHeight = screenHeight - safeInsets.top - safeInsets.bottom
            val landscape = screenWidth > screenHeight
            val longSide = if (landscape) availableWidth else availableHeight
            val shortSide = if (landscape) availableHeight else availableWidth
            val gap = density.toInt().coerceAtLeast(1)
            // Status-safe band plus a small main-menu grip; no application toolbar.
            val controlThickness = (64 * density).roundToInt().coerceIn(1, longSide - 1)
            // A wide tablet can afford a readable vertical side rail.
            // Do not change compact phone or near-square foldable thicknesses.
            val nativeTablet = StageDisplayPolicy.wideLandscapeTablet(screenWidth, screenHeight)
            val railThickness = (shortSide * if (nativeTablet) 0.33f else 0.25f)
                .roundToInt().coerceIn(gap + 1, shortSide - 1)
            return WorkspaceGeometry(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                topHeight = controlThickness,
                sidebarWidth = railThickness,
                sidebarSide = sidebarSide,
                navigationInsets = safeInsets,
                displayRotation = displayRotation,
            )
        }
    }
}
