package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.display.SlotGeometry
import org.junit.Assert.*
import org.junit.Test

class Pms110SlotLayoutTest {
    @Test fun actualPixelReferencesAndDisplayScalingPreserveAllOccupiedBounds() {
        // Physical pixel reference, NOT dp or a production model-name lookup.
        for((w,h) in listOf(1272 to 2772,2772 to 1272,1080 to 2354,2248 to 2480,1140 to 2616))
        for(dpi in listOf(448,512,560)) for(top in listOf(false,true)) for(count in 0..4) {
            val density=dpi/160f
            val inset=if(w>h) WorkspaceNavigationInsets(top=36,right=100) else WorkspaceNavigationInsets(top=108,bottom=96)
            val base=WorkspaceLayoutProfile.create(w,h,dpi,inset,if(w>h)1 else 0)
            val g0=base.geometry.copy(topHeight=(96*density).toInt(),railAtTop=top)
            val g=StageAdaptiveLayout.rail(if(top)g0.copy(sidebarWidth=StageTopLayoutMetrics.depth(g0,density,count)) else g0,count,density)
            val p=base.copy(geometry=g,activeCount=count)
            val occupied=List(4){it<count}
            val sources=List(4){if(it%2==0) SlotGeometry(720,1120,320) else p.canvas}
            val bounds=StageLiveCardLayout.bounds(p,sources,occupied,listOf(0,1,2,3))
            for(i in 0 until count) {
                val b=bounds[i]
                assertTrue("$w/$h/$dpi/$count/$top",b.width>1 && b.height>1)
                assertTrue(b.top>=0 && b.top+b.height<=g.sidebarLogicalHeight)
                if(top) assertTrue(b.left>=0 && b.left+b.width<=g.sidebarPhysicalWidth)
                else assertTrue(b.width<=g.sidebarWidth)
                assertEquals(sources[i].width.toFloat()/sources[i].height,b.width.toFloat()/b.height,.025f)
                if(i>0) {if(top)assertTrue(b.left>=bounds[i-1].left+bounds[i-1].width) else assertTrue(b.top>=bounds[i-1].top+bounds[i-1].height)}
                if(w==1272 && dpi==512) println("PMS110 count=$count top=$top slot=$i state=Occupied producer=${sources[i].width}x${sources[i].height} bounds=${b.width}x${b.height}@${b.left},${b.top} parent=${g.sidebarPhysicalWidth}x${g.sidebarPhysicalHeight} main=${g.contentWidth}x${g.contentHeight}")
            }
        }
    }
}
