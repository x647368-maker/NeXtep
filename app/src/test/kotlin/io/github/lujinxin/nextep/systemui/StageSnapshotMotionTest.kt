package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class StageSnapshotMotionTest {
    private fun b(l:Float,t:Float,r:Float,bt:Float)=StageSnapshotMotion.Box(l,t,r,bt)
    private fun verify(small:StageSnapshotMotion.Box,main:StageSnapshotMotion.Box,w:Int,h:Int){
        val up=checkNotNull(StageSnapshotMotion.flight(small,main,w,h))
        val down=checkNotNull(StageSnapshotMotion.flight(main,small,w,h))
        for((f,from,to) in listOf(Triple(up,small,main),Triple(down,main,small))){
            assertEquals(from.cx,f.start.cx,.01f);assertEquals(from.cy,f.start.cy,.01f)
            assertEquals(to.cx,f.end.cx,.01f);assertEquals(to.cy,f.end.cy,.01f)
            assertEquals(min(from.w/w,from.h/h),f.start.scale,.00001f)
            assertEquals(min(to.w/w,to.h/h),f.end.scale,.00001f)
            var old=f.start
            val dx=to.cx-from.cx; val dy=to.cy-from.cy
            // Every sample advances along the center line, even where a card
            // bends laterally. There is no stationary rail phase.
            for(i in 1..120){
                val next=f.pose(i/120f)
                assertTrue(next.cx.isFinite() && next.cy.isFinite() && next.scale>0f)
                assertTrue((next.cx-old.cx)*dx+(next.cy-old.cy)*dy>0f)
                if(f.growing)assertTrue(next.scale>=old.scale-.00001f)
                else assertTrue(next.scale<=old.scale+.00001f)
                old=next
            }
        }
        val u=up.pose(.5f);val d=down.pose(.5f)
        assertTrue(hypot(u.cx-d.cx,u.cy-d.cy)>1f)
    }
    @Test fun topRailCenterAlignedAndOffset(){
        verify(b(30f,145f,275f,629f),b(160f,680f,1113f,2759f),1272,2772)
        verify(b(535f,145f,735f,545f),b(135f,600f,1135f,2600f),1272,2772)
    }
    @Test fun bothSideRailsAndFoldableTablet(){
        verify(b(10f,500f,255f,989f),b(272f,410f,1271f,2587f),1272,2772)
        verify(b(1017f,1750f,1262f,2239f),b(1f,410f,1000f,2587f),1272,2772)
        verify(b(10f,200f,280f,800f),b(300f,160f,2020f,2200f),2048,2248)
        verify(b(30f,200f,350f,400f),b(400f,160f,2450f,1440f),2560,1600)
    }
    @Test fun equalAxisUsesSubpixelNotUnitScaleJump(){
        assertEquals(100.25f,StageSnapshotMotion.hardwareEnd(100f,100f,.25f),.00001f)
        assertEquals(.5001f,StageSnapshotMotion.hardwareEnd(.5f,.5f,.0001f),.00001f)
        assertEquals(.2f,StageSnapshotMotion.hardwareEnd(.8f,.2f,.0001f),.00001f)
    }
}
