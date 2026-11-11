package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Y700 uses a ported phone ROM: physical width/height, not Build.MODEL. */
class StageY700UxRegressionTest {
    private fun tablet(side: SidebarSide = SidebarSide.LEFT, top: Boolean = false,
        rotation: Int = 1): WorkspaceLayoutProfile {
        val g = WorkspaceGeometry.forDisplay(2560,1600,side,density=2f,
            navigationInsets=WorkspaceNavigationInsets(top=64,bottom=48),displayRotation=rotation)
        return WorkspaceLayoutProfile(if(top) g.copy(railAtTop=true,sidebarWidth=410) else g,
            densityDpi=320,gapPixels=2,activeCount=3)
    }

    @Test fun topFirstCreationRemainsLandscapeAfterRepeatedLayoutSelections() {
        for (rotation in 0..3) {
            val initial = tablet(top=true,rotation=rotation)
            val producer=StageTabletCanvasPolicy.proposed(initial,null)
            assertEquals(SlotGeometry(initial.geometry.availableWidth,
                initial.geometry.availableHeight,320),producer)
            var retained=producer
            repeat(5) {
                for (profile in listOf(tablet(SidebarSide.LEFT,rotation=rotation),
                    tablet(SidebarSide.RIGHT,rotation=rotation), initial)) {
                    val proposed=StageTabletCanvasPolicy.proposed(profile,retained)
                    assertEquals(producer,proposed)
                    retained=proposed
                }
            }
        }
    }

    @Test fun y700SideMainUsesSymmetricInsetsOnBothEdgesOnly() {
        for (side in SidebarSide.entries) for (priority in listOf(true,false)) {
            val p=tablet(side)
            val ready=StagePresentationPolicy.apply(p,priority,inputReady=true)
            val g=ready.geometry
            assertEquals(24,g.mainInset)
            assertTrue(g.contentLeft>=g.availableLeft+g.mainInset)
            assertTrue(g.contentRight<=g.availableRight-g.mainInset)
            assertTrue(g.contentTop>=g.availableTop+g.topHeight+g.mainInset)
            assertTrue(g.contentBottom<=g.availableBottom-g.mainInset)
        }
        // Normal OnePlus and near-square N5 must not get the tablet margin.
        for ((w,h,d) in listOf(Triple(1140,2616,3f), Triple(2248,2480,2.6f))) {
            val p=WorkspaceLayoutProfile(
                WorkspaceGeometry.forDisplay(w,h,density=d), (d*160).toInt(),2)
            assertEquals(0,StagePresentationPolicy.apply(p,true,true).geometry.mainInset)
        }
        val top=tablet(top=true)
        assertEquals(0,StagePresentationPolicy.apply(top,true,true).geometry.mainInset)
    }

    @Test fun centerHandleTogglesFavoritesOnlyOnWideTablet() {
        for ((w,h) in listOf(2560 to 1600,1600 to 2560)) {
            assertEquals(StageTabletMainGripPolicy.Tap.QUICK_BAR,
                StageTabletMainGripPolicy.tap(w,h))
        }
        for ((w,h) in listOf(1140 to 2616,2616 to 1140,
            2248 to 2480,2480 to 2248)) {
            assertEquals(StageTabletMainGripPolicy.Tap.MENU,
                StageTabletMainGripPolicy.tap(w,h))
        }
    }

    @Test fun onlyPhysicalWideY700GetsShortLivePanelEntryMotion() {
        for (rotation in 0..3) {
            val left=tablet(SidebarSide.LEFT,rotation=rotation).geometry
            val right=tablet(SidebarSide.RIGHT,rotation=rotation).geometry
            val top=tablet(top=true,rotation=rotation).geometry
            val l=StageTabletLayoutMotionPolicy.entry(left,2f)!!
            val r=StageTabletLayoutMotionPolicy.entry(right,2f)!!
            val t=StageTabletLayoutMotionPolicy.entry(top,2f)!!
            assertEquals(-20f,l.dx,0f)
            assertEquals(20f,r.dx,0f)
            assertEquals(0f,t.dx,0f)
            assertEquals(-20f,t.dy,0f)
            assertEquals(200L,l.durationMs)
            assertEquals(200L,r.durationMs)
        }
        assertNull(StageTabletLayoutMotionPolicy.entry(
            WorkspaceGeometry.forDisplay(1140,2616,density=3f),3f))
        assertNull(StageTabletLayoutMotionPolicy.entry(
            WorkspaceGeometry.forDisplay(2248,2480,density=2.6f),2.6f))
    }
}
