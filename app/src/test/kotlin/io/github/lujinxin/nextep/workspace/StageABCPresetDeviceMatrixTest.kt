package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

/**
 * A/B share the existing source/slot layout engine. C changes only geometry
 * for occupied counts; task identity, virtual-display buffer and pins are not
 * part of the policy. No device-model whitelist and no extra runtime loop.
 */
class StageABCPresetDeviceMatrixTest {
    private data class Device(val w: Int, val h: Int, val dpi: Int)
    private val physicalStates = listOf(
        Device(1140,2616,480), Device(2616,1140,480),  // Ace 7 Pro
        Device(1140,2520,480), Device(2520,1140,480),  // Folded outer
        Device(2248,2480,420), Device(2480,2248,420), // N5 unfolded
        Device(2560,1600,480), Device(1600,2560,480), // Y700 ported ROM
    )

    @Test fun physicalCanvasChoosesNewPresetButRespectsLegacyManualChoice() {
        for(d in physicalStates) {
            val recommended=StageDisplayPolicy.initial(d.w,d.h,d.dpi,true,null,SidebarSide.RIGHT)
            assertNotNull(StageDisplayPolicy.Choice.decode(recommended.encode()))
            assertEquals(StageDisplayPolicy.Choice(true,SidebarSide.RIGHT),
                StageDisplayPolicy.initial(d.w,d.h,d.dpi,false,true,SidebarSide.RIGHT))
            assertEquals(StageDisplayPolicy.Choice(false,SidebarSide.LEFT),
                StageDisplayPolicy.initial(d.w,d.h,d.dpi,false,false,SidebarSide.LEFT))
        }
    }
    @Test fun abcComposesOnAllShapesWithoutAddingTaskOrBreakingMainViewport() {
        for(d in physicalStates) {
            val density=d.dpi/160f
            val source=WorkspaceGeometry.forDisplay(d.w,d.h,density=density)
            for(top in listOf(false,true)) {
                val base=source.copy(railAtTop=top)
                val original=WorkspaceLayoutProfile(base,d.dpi,2,activeCount=4).canvas
                for(count in 1..4) {
                    val selected=StageAdaptiveLayout.rail(base,count,density)
                    val profile=WorkspaceLayoutProfile(selected,d.dpi,2,activeCount=count)
                    assertEquals(original,profile.canvas)
                    assertTrue(selected.contentWidth>0)
                    assertTrue(selected.contentHeight>0)
                    assertTrue(selected.contentLeft >= selected.availableLeft)
                    assertTrue(selected.contentBottom <= selected.availableBottom)
                    assertEquals(4,profile.slots.size)
                    if(count==4 && top) assertTrue(selected.railAtTop)
                }
            }
        }
    }
    @Test fun foldStateAndRotationKeepIndependentSavedChoices() {
        val scopes=physicalStates.map { StageDisplayPolicy.scope(it.w,it.h,it.dpi) }
        assertEquals(scopes.size,scopes.toSet().size)
        val userSaved=scopes.associateWith { StageDisplayPolicy.Choice(false,SidebarSide.RIGHT).encode() }
        for(key in scopes) assertEquals(StageDisplayPolicy.Choice(false,SidebarSide.RIGHT),
            StageDisplayPolicy.Choice.decode(userSaved[key]))
    }
    @Test fun sparseCountNeverEnablesPhoneStretchOnN5InnerOrTablet() {
        val ace=WorkspaceGeometry.forDisplay(1140,2616,density=3f)
        val n5=WorkspaceGeometry.forDisplay(2248,2480,density=2.6f)
        val tab=WorkspaceGeometry.forDisplay(2560,1600,density=3f)
        assertTrue(StageSparseSideCardPolicy.enabled(ace,2))
        assertFalse(StageSparseSideCardPolicy.enabled(n5,2))
        assertFalse(StageSparseSideCardPolicy.enabled(tab,2))
        assertFalse(StageSparseSideCardPolicy.enabled(ace,4))
    }
}
