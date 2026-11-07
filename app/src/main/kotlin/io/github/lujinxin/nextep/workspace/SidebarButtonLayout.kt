package io.github.x647368maker.atomicworkbench.workspace

/** The button diagram is the authority for rail placement in the control panel's axes. */
internal object SidebarButtonLayout {
    fun sideInPanel(selectedButton: SidebarSide): SidebarSide = selectedButton

    fun isAtScreenTop(selectedButton: SidebarSide, landscape: Boolean, controlOnLeft: Boolean): Boolean =
        landscape && ((sideInPanel(selectedButton) == SidebarSide.LEFT) != controlOnLeft)
}
