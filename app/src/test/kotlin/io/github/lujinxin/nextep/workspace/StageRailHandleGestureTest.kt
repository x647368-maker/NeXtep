package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageRailHandleGestureTest {
    @Test fun allRailAxesAndSigns() {
        val shapes = listOf(1140 to 2616,2616 to 1140,2248 to 2480,2480 to 2248,1600 to 2560,2560 to 1600)
        for ((w,h) in shapes) for (side in SidebarSide.entries) for (top in listOf(false,true))
            for (rotation in 0..3) {
                val g=WorkspaceGeometry(w,h,168,minOf(w,h)/4,side,displayRotation=rotation,railAtTop=top)
                val (dx,dy)=StageRailHandleGesture.collapseOffset(g)
                val result=StageRailHandleGesture.decision(g,dx,dy,8f,24f)
                assertEquals(StageRailHandleGesture.Decision.OUTWARD,result)
                assertEquals(StageRailHandleGesture.Decision.INWARD,StageRailHandleGesture.decision(g,-dx,-dy,8f,24f))
                assertEquals(StageRailHandleGesture.Decision.IGNORE,StageRailHandleGesture.decision(g,dy,dx,8f,24f))
                assertEquals(StageRailHandleGesture.Decision.IGNORE,StageRailHandleGesture.decision(g,60f,60f,8f,24f))
                assertEquals(StageRailHandleGesture.Decision.IGNORE,StageRailHandleGesture.decision(g,12f,12f,8f,24f))
                assertEquals(StageRailHandleGesture.Decision.TAP,StageRailHandleGesture.decision(g,3f,4f,8f,24f))
            }
    }
    @Test fun nativeTabletSideRailUsesPhysicalX() {
        for(side in SidebarSide.entries) {
            val g=WorkspaceGeometry(2560,1600,128,440,side)
            val (x,y)=StageRailHandleGesture.collapseOffset(g)
            assertEquals(0f,y,0f)
            assertEquals(if(side==SidebarSide.LEFT)-440f else 440f,x,0f)
        }
    }
}
