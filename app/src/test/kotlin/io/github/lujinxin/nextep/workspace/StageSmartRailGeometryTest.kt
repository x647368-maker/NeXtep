package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StageSmartRailGeometryTest {
    private val shapes = listOf(
        Triple(1140, 2616, 3f), Triple(1080, 2520, 3f),
        Triple(2248, 2480, 3f), Triple(2268, 2440, 3f),
        Triple(1600, 2560, 2f), Triple(2560, 1600, 2f),
        Triple(2616, 1140, 3f)
    )

    @Test fun allPhysicalLayoutsKeepAnOnscreenNonOverlappingRestoreHandle() {
        for ((w,h,d) in shapes) for(side in SidebarSide.entries)
            for(top in listOf(false,true)) for(rotation in 0..3) {
                val base = WorkspaceGeometry(w,h, (56*d).toInt(),
                    minOf(w,h)/4,side,WorkspaceNavigationInsets(24,72,20,48),
                    rotation,top,0,12)
                val hidden = StageSmartRailGeometry.reclaim(base,d)
                val handle = StageSmartRailGeometry.handle(hidden,d)
                assertTrue(hidden.contentScale >= base.contentScale)
                assertTrue(abs(hidden.contentWidth.toFloat()/hidden.contentHeight - w.toFloat()/h) < .005f)
                assertTrue(handle.width > 0 && handle.height > 0)
                assertTrue(handle.left >= hidden.availableLeft && handle.top >= hidden.availableTop)
                assertTrue(handle.right <= hidden.availableRight && handle.bottom <= hidden.availableBottom)
                assertFalse(handle.overlaps(StageTopLayoutMetrics.Box(hidden.contentLeft,
                    hidden.contentTop,hidden.contentWidth,hidden.contentHeight)))
                if(StageSmartRailGeometry.horizontal(hidden))
                    assertTrue(handle.width >= handle.height)
                else assertTrue(handle.height >= handle.width)
                assertNotEquals(StageSmartRailGeometry.towardsContent(hidden,30f),
                    StageSmartRailGeometry.towardsContent(hidden,-30f))
            }
    }

    @Test fun existingTopOverlapDoesNotMakeMainTaskSmaller() {
        val base = WorkspaceGeometry(1140,2616,168,520,SidebarSide.LEFT,
            railAtTop=true,mainTopOverlap=500)
        val hidden = StageSmartRailGeometry.reclaim(base,3f)
        assertTrue(hidden.contentScale >= base.contentScale)
        val button = StageSmartRailGeometry.handle(hidden,3f)
        assertTrue(button.bottom <= hidden.sidebarTop + hidden.sidebarWidth)
    }
}
