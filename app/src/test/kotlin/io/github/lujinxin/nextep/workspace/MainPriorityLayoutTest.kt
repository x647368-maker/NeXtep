package io.github.x647368maker.atomicworkbench.workspace
import io.github.x647368maker.atomicworkbench.systemui.StageVisualOrder
import org.junit.Assert.*
import org.junit.Test
class MainPriorityLayoutTest {
    @Test fun zeroToFourKeepSourceCanvasAndWholeMainInsideSafeBounds() {
        val base=WorkspaceLayoutProfile.create(1264,2780,512,WorkspaceNavigationInsets(bottom=96))
        for(count in 0..4){
            val g=base.geometry.copy(railAtTop=true,topHeight=307,sidebarWidth=600)
            val normal=base.copy(geometry=StageAdaptiveLayout.rail(g,count,3.2f),activeCount=count)
            val preferred=StageAdaptiveLayout.mainPriority(normal,count)
            assertEquals(normal.canvas,preferred.canvas)
            assertTrue(preferred.geometry.contentWidth>=normal.geometry.contentWidth)
            assertTrue(preferred.geometry.contentTop>=preferred.geometry.sidebarTop+preferred.geometry.sidebarPhysicalHeight)
            assertTrue(preferred.geometry.contentRight<=preferred.geometry.availableRight)
            assertTrue(preferred.geometry.contentBottom<=preferred.geometry.availableBottom)
            assertEquals(preferred.geometry.screenWidth*preferred.geometry.contentScale,preferred.geometry.contentWidth.toFloat(),.51f)
            assertEquals(preferred.geometry.screenHeight*preferred.geometry.contentScale,preferred.geometry.contentHeight.toFloat(),.51f)
        }
    }
    @Test fun topFourMainGetsMoreActualPixelsWithoutCroppingOrStretching() {
        val b=WorkspaceLayoutProfile.create(1264,2780,512,WorkspaceNavigationInsets())
        val normal=b.copy(geometry=b.geometry.copy(topHeight=307,railAtTop=true,sidebarWidth=640))
        val larger=StageAdaptiveLayout.mainPriority(normal,4)
        assertTrue(larger.geometry.contentWidth>normal.geometry.contentWidth)
        assertTrue(larger.geometry.contentHeight>normal.geometry.contentHeight)
        assertEquals(normal.source,larger.source)
    }
    @Test fun sideLayoutAndManualCompleteChoiceAreNotRewritten() {
        val p=WorkspaceLayoutProfile.create(1264,2780,512,WorkspaceNavigationInsets())
        val priority=StageAdaptiveLayout.mainPriority(p,4)
        assertTrue(priority.geometry.contentWidth>=p.geometry.contentWidth)
        assertTrue(priority.geometry.contentHeight>=p.geometry.contentHeight)
        assertEquals(p.canvas,priority.canvas)
        assertTrue(priority.geometry.topHeight<p.geometry.topHeight)
    }
    @Test fun visualReorderKeepsSlotIdentityAndCanRoundTrip() {
        val order=listOf(0,1,2,3)
        assertEquals(listOf(2,1,0,3),StageVisualOrder.swap(order,0,2))
        assertEquals(order,StageVisualOrder.swap(StageVisualOrder.swap(order,0,2),0,2))
    }
}
