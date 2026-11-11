package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StageY700CompactRailAndNativeWallpaperTest {
    private fun portrait(count: Int, side: SidebarSide = SidebarSide.LEFT): WorkspaceLayoutProfile {
        val base = WorkspaceGeometry.forDisplay(1600, 2560, side, density = 2f,
            navigationInsets = WorkspaceNavigationInsets(top=96, bottom=40))
        val rail = StageAdaptiveLayout.rail(base,count,2f)
        return WorkspaceLayoutProfile(rail,320,2,activeCount=count)
    }

    @Test fun physicalTabletUsesSingleNativeWallpaperRatherThanDuplicatedCrops() {
        assertTrue(StageTabletWallpaperPolicy.useLiveSystemWallpaper(portrait(2).geometry))
        val wide = WorkspaceGeometry.forDisplay(2560, 1600, SidebarSide.LEFT, density=2f,
            displayRotation=1)
        assertTrue(StageTabletWallpaperPolicy.useLiveSystemWallpaper(wide))
        assertFalse(StageTabletWallpaperPolicy.useLiveSystemWallpaper(
            WorkspaceGeometry.forDisplay(1140, 2616, density=3f)))
        assertFalse(StageTabletWallpaperPolicy.useLiveSystemWallpaper(
            WorkspaceGeometry.forDisplay(2248, 2480, density=2.6f)))
    }

    @Test fun compactRailPlacesCardsTogetherAtTopAndAddButtonJustBelow() {
        for (side in SidebarSide.entries) for (count in 1..4) {
            val profile=portrait(count,side)
            val g=profile.geometry
            val occupied=List(4) { it<count }
            val order=listOf(0,1,2,3)
            val cards=StageLiveCardLayout.bounds(profile,List(4){profile.canvas},occupied,order)
            val gap=(8*profile.densityDpi/160f).toInt()
            val shown=cards.take(count)
            assertEquals(gap,shown.first().top)
            assertTrue(shown.zipWithNext().all{(a,b)->a.top+a.height<b.top})
            assertTrue(shown.all{it.width>0 && it.height>0 &&
                it.width<=g.sidebarPhysicalWidth && it.top+it.height<g.sidebarPhysicalHeight})
            assertTrue(shown.all{
                abs(it.width.toFloat()/it.height -
                    profile.canvas.width.toFloat()/profile.canvas.height)<0.025f
            })
            val addTop=StageTabletRailVisualPolicy.addTop(g,cards,occupied,88,16)
            if(count<4){
                assertNotNull(addTop)
                val bottom=shown.maxOf{it.top+it.height}
                assertTrue(addTop!!>=bottom)
                assertTrue(addTop+88<=g.sidebarPhysicalHeight)
                // Leave the remainder as continuous native wallpaper, not an
                // artificial tinted backdrop running to the navigation bar.
                if(count<=2)assertTrue(addTop+88<g.sidebarPhysicalHeight*0.85f)
            }else assertNull(addTop)
        }
    }

    @Test fun portraitLockedAndLandscapeProducersRemainDistinctWithoutStretch() {
        for(count in 1..4){
            val profile=portrait(count)
            val occupied=List(4){it<count}
            val canvases=listOf(
                SlotGeometry(1600,2560,320),
                SlotGeometry(2560,1600,320),
                SlotGeometry(1600,2560,320),
                SlotGeometry(2560,1600,320))
            val cards=StageLiveCardLayout.bounds(profile,canvases,occupied,(0..3).toList())
            for(i in 0 until count){
                val expected=canvases[i].width.toFloat()/canvases[i].height
                assertEquals(expected,cards[i].width.toFloat()/cards[i].height,.025f)
            }
        }
    }

    @Test fun nonTabletAndLandscapeKeepOldLayoutRules() {
        val n5=WorkspaceGeometry.forDisplay(2248,2480,density=2.6f)
        val phone=WorkspaceGeometry.forDisplay(1140,2616,density=3f)
        val landscape=WorkspaceGeometry.forDisplay(2560,1600,density=2f,displayRotation=1)
        val top=portrait(2).geometry.copy(railAtTop=true)
        for(g in listOf(n5,phone,landscape,top)){
            assertFalse(StageTabletRailVisualPolicy.enabled(g))
            val p=WorkspaceLayoutProfile(g,320,2,activeCount=2)
            val occupied=listOf(true,true,false,false)
            assertNull(StageTabletRailVisualPolicy.compactCards(p,List(4){p.canvas},occupied,(0..3).toList()))
            assertNull(StageTabletRailVisualPolicy.addTop(g,p.slots,occupied,88,16))
        }
    }
}
