package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import kotlin.math.roundToInt

/**
 * Layout-only Y700 portrait improvement.
 * The rail itself stays mapped to the same physical input area, but live
 * cards and the '+' action form a compact cluster under the toolbar. Empty
 * rail pixels never need an overlay wallpaper copy.
 *
 * The existing real VirtualDisplay, task, texture and input mapping are
 * unchanged. Phone/N5/top rail and all wide-landscape behavior are excluded.
 */
internal object StageTabletRailVisualPolicy {
    fun enabled(g: WorkspaceGeometry): Boolean =
        !g.isLandscape && !g.railAtTop &&
            StageDisplayPolicy.wideTablet(g.screenWidth, g.screenHeight)

    fun compactCards(
        profile: WorkspaceLayoutProfile,
        canvases: List<SlotGeometry?>,
        occupied: List<Boolean>,
        order: List<Int>,
    ): List<WorkspaceLayoutProfile.Slot>? {
        val g = profile.geometry
        if (!enabled(g)) return null
        val shown = order.filter { occupied[it] }
        if (shown.isEmpty()) return null
        val gap = (8 * profile.densityDpi / 160f).roundToInt().coerceAtLeast(1)
        val addReserve = if (shown.size < 4) (64 * profile.densityDpi / 160f).roundToInt() else gap
        val availableHeight = (g.sidebarLogicalHeight - gap * (shown.size + 1) - addReserve)
            .coerceAtLeast(shown.size)
        val maxHeight = (availableHeight / shown.size).coerceAtLeast(1)
        // Keep the vertical spacing and '+' anchor unchanged. When fewer
        // than four cards are open, reclaim just a few pixels at the sides.
        val horizontalInset = ((if (shown.size <= 2) 4f else if (shown.size == 3) 5f else 8f) *
            profile.densityDpi / 160f).roundToInt().coerceAtLeast(1)
        val maxWidth = (g.sidebarPhysicalWidth - 2 * horizontalInset).coerceAtLeast(1)
        val fitted = shown.associateWith { index ->
            val canvas = canvases[index] ?: profile.canvas
            val scale = minOf(maxWidth.toFloat() / canvas.width, maxHeight.toFloat() / canvas.height)
            val width = (canvas.width * scale).roundToInt().coerceIn(1, maxWidth)
            val height = (canvas.height * scale).roundToInt().coerceIn(1, maxHeight)
            WorkspaceLayoutProfile.Slot(width,height,0)
        }
        var y = gap
        return profile.slots.toMutableList().also { slots ->
            shown.forEach { index ->
                val card = fitted.getValue(index)
                slots[index] = card.copy(top=y)
                y += card.height + gap
            }
        }
    }

    /**
     * Position add immediately below the last occupied card instead of at
     * the bottom of an almost-empty 2560px portrait rail.
     */
    fun addTop(
        g: WorkspaceGeometry,
        cards: List<WorkspaceLayoutProfile.Slot>,
        occupied: List<Boolean>,
        buttonPx: Int,
        gapPx: Int,
    ): Int? {
        if (!enabled(g) || occupied.none { it } || occupied.all { it }) return null
        val lastBottom = cards.indices.asSequence().filter { occupied[it] }
            .maxOfOrNull { cards[it].top + cards[it].height } ?: return null
        return (lastBottom + gapPx).coerceIn(0,
            (g.sidebarPhysicalHeight - buttonPx - gapPx).coerceAtLeast(0))
    }
}
