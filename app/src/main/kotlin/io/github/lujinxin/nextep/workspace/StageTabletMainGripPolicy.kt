package io.github.x647368maker.atomicworkbench.workspace

/** The existing center grip remains the main menu on phones and N5.
 * On wide tablets its tap toggles the favorites row, while long-press still
 * opens the complete menu and left-swipe still closes the focused main app.
 */
internal object StageTabletMainGripPolicy {
    enum class Tap { MENU, QUICK_BAR }
    fun tap(width: Int, height: Int): Tap =
        if (StageDisplayPolicy.wideTablet(width, height)) Tap.QUICK_BAR else Tap.MENU
}
