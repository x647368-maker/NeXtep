package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLayoutProfileTest {
    @Test
    fun everySlotFillsItsRecordedBoundsInBothNavigationModesAndRotations() {
        for ((width, height, densityDpi) in listOf(
            Triple(1080, 2354, 480), Triple(2354, 1080, 480),
            Triple(1600, 2560, 320), Triple(2560, 1600, 320),
        )) {
            for (insets in listOf(WorkspaceNavigationInsets(),
                if (width < height) WorkspaceNavigationInsets(bottom = 132)
                else WorkspaceNavigationInsets(right = 132))) {
                val profile = WorkspaceLayoutProfile.create(width, height, densityDpi, insets)
                for (side in SidebarSide.entries) {
                    val positioned = profile.copy(geometry = profile.geometry(side))
                    positioned.slots.forEachIndexed { index, slot ->
                        val transform = positioned.previewTransform(slot.width, slot.height)
                        val drawnWidth = slot.width * transform.scaleX
                        val drawnHeight = slot.height * transform.scaleY
                        val message = "$width x $height navigation=$insets side=$side slot=$index"
                        // Whole-buffer fitting may leave at most integer-pixel rounding.
                        assertEquals(message, slot.width.toFloat(), drawnWidth, 1.5f)
                        assertEquals(message, slot.height.toFloat(), drawnHeight, 1.5f)
                        assertEquals(message, slot.width / positioned.canvas.width.toFloat() * transform.scaleX,
                            slot.height / positioned.canvas.height.toFloat() * transform.scaleY, .000001f)
                    }
                    assertEquals(positioned.geometry.sidebarLogicalHeight,
                        positioned.slots.last().top + positioned.slots.last().height)
                    assertEquals(positioned.geometry.sidebarWidth,
                        positioned.slots.first().width + positioned.gapPixels)
                }
            }
        }
    }

    @Test
    fun savedProfileRoundTripsBothNavigationModesAndRotations() {
        for ((width, height) in listOf(1080 to 2354, 2354 to 1080)) {
            for (insets in listOf(WorkspaceNavigationInsets(),
                if (width < height) WorkspaceNavigationInsets(bottom = 132)
                else WorkspaceNavigationInsets(right = 132))) {
                for (rotation in if (width > height) listOf(1, 3) else listOf(0)) {
                    val profile = WorkspaceLayoutProfile.create(width, height, 480, insets, rotation)
                    val restored = checkNotNull(WorkspaceLayoutProfile.decode(profile.encode()))
                    assertEquals(profile, restored)
                    assertEquals(profile.slots, restored.slots)
                    assertEquals(profile.canvas, restored.canvas)
                    assertEquals(profile.source, restored.source)
                    assertEquals(profile.geometry(SidebarSide.LEFT), restored.geometry(SidebarSide.LEFT))
                    assertEquals(profile.geometry(SidebarSide.RIGHT), restored.geometry(SidebarSide.RIGHT))
                }
            }
        }
    }

    @Test
    fun actualPixelsUseTheSameScaleOnBothAxesAndFitInsideEverySlot() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, insets)
            profile.slots.forEach { slot ->
                val transform = profile.previewTransform(slot.width, slot.height)
                val sx = slot.width * transform.scaleX / profile.canvas.width
                val sy = slot.height * transform.scaleY / profile.canvas.height
                assertEquals(sx, sy, .000001f)
                val source = profile.source
                val left = source.left * sx + transform.x
                val right = source.right * sx + transform.x
                val top = source.top * sy + transform.y
                val bottom = source.bottom * sy + transform.y
                assertTrue(left >= -.01f && top >= -.01f)
                assertTrue(right <= slot.width + .01f && bottom <= slot.height + .01f)
            }
        }
    }

    @Test
    fun canvasUsesTheRecordedUsableSizeAndDoesNotDependOnTheSlotOrSide() {
        val full = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets())
        val buttons = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132))
        assertEquals(1080, buttons.canvas.width)
        assertEquals(2222, buttons.canvas.height)
        assertEquals(2354, full.source.height)
        assertEquals(2222, buttons.source.height)
        assertEquals(2222, buttons.geometry.availableHeight)
        assertNotEquals(full.slots, buttons.slots)
        assertEquals(buttons.canvas, buttons.copy(geometry = buttons.geometry(SidebarSide.LEFT)).canvas)
    }

    @Test
    fun previewKeepsTheWholeAppIncludingItsBottomControlsInButtonMode() {
        val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets(bottom = 132))
        // A virtual display has no physical navigation bar. Cropping 132 pixels
        // off its app would remove the app's own bottom controls.
        assertEquals(2222, profile.geometry.availableHeight)
        assertEquals(0, profile.source.top)
        assertEquals(profile.canvas.height, profile.source.bottom)
    }

    @Test
    fun independentFourSlotProtocolRejectsThreeSlotProfiles() {
        val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets())
        assertEquals(4, profile.slots.size)
        val legacy = profile.encode().split(',').toMutableList()
        repeat(3) { legacy.removeAt(29) }
        legacy[0] = "5"
        assertNull(WorkspaceLayoutProfile.decode(legacy.joinToString(",")))
        assertNull(WorkspaceLayoutProfile.decode(profile.encode().replaceFirst("6,", "5,")))
    }

    @Test
    fun inconsistentSavedDimensionsAreRejected() {
        val profile = WorkspaceLayoutProfile.create(1080, 2354, 480, WorkspaceNavigationInsets())
        val parts = profile.encode().split(',').toMutableList()
        parts[14] = "300" // stale native canvas width must not be combined with this profile.
        assertNull(WorkspaceLayoutProfile.decode(parts.joinToString(",")))
        assertNull(WorkspaceLayoutProfile.decode("1080,2354,3,0,0,0,0"))
    }
}
