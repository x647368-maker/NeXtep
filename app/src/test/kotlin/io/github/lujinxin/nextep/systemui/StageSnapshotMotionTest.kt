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
    @Test fun pairedCorridorImprovesVisibilityWithoutMovingEndpoints(){
        val screen=b(0f,0f,180f,392f)
        val sideMain=b(44f,65f,179f,356f)
        val topMain=b(30f,119f,150f,377f)
        val cases=listOf(
            Triple(b(5f,52f,41f,122f),sideMain,listOf(b(5f,126f,41f,196f),b(5f,199f,41f,269f),b(5f,273f,41f,344f))),
            Triple(b(58f,52f,90f,112f),topMain,listOf(b(21f,52f,55f,112f),b(93f,52f,127f,112f),b(130f,52f,164f,112f))),
            Triple(b(5f,273f,41f,344f),sideMain,listOf(b(5f,52f,41f,122f),b(5f,126f,41f,196f),b(5f,199f,41f,269f))),
        )
        for((small,main,excluded) in cases){
            val old=StageSnapshotMotion.PairFlight(
                checkNotNull(StageSnapshotMotion.flight(small,main,1080,2354)),
                checkNotNull(StageSnapshotMotion.flight(main,small,1080,2354)))
            val candidate=checkNotNull(StageSnapshotMotion.pairedFlights(small,main,1080,2354,1080,2354,screen,excluded))
            fun minOutgoing(pair:StageSnapshotMotion.PairFlight)=
                (1..15).minOf{StageSnapshotMotion.visibleFractions(pair,it/16f,screen,excluded).second}
            assertTrue("Old=${minOutgoing(old)}, new=${minOutgoing(candidate)}",
                minOutgoing(candidate)>minOutgoing(old)+.12f)
            assertTrue(minOutgoing(candidate)>.25f)
            for((now,prior) in listOf(candidate.up to old.up,candidate.down to old.down)){
                assertEquals(prior.start,now.start);assertEquals(prior.end,now.end)
                var last=now.start
                for(i in 1..120){
                    val next=now.pose(i/120f)
                    assertTrue((next.cx-last.cx)*(now.to.cx-now.from.cx)+(next.cy-last.cy)*(now.to.cy-now.from.cy)>0f)
                    if(now.growing)assertTrue(next.scale>=last.scale-.00001f)
                    else assertTrue(next.scale<=last.scale+.00001f)
                    last=next
                }
            }
        }
    }
    @Test fun pairedCorridorFoldTabletAndRightRailKeepExactEndpoints(){
        val cases=listOf(
            Triple(b(1017f,1750f,1262f,2239f),b(1f,410f,1000f,2587f),b(0f,0f,1272f,2772f)),
            Triple(b(10f,200f,280f,800f),b(300f,160f,2020f,2200f),b(0f,0f,2048f,2248f)),
            Triple(b(30f,200f,350f,400f),b(400f,160f,2450f,1440f),b(0f,0f,2560f,1600f)))
        for((small,main,screen) in cases){
            val pair=checkNotNull(StageSnapshotMotion.pairedFlights(small,main,screen.w.toInt(),screen.h.toInt(),
                screen.w.toInt(),screen.h.toInt(),screen,emptyList()))
            assertEquals(small.cx,pair.up.start.cx,.001f)
            assertEquals(main.cy,pair.up.end.cy,.001f)
            assertEquals(main.cx,pair.down.start.cx,.001f)
            assertEquals(small.cy,pair.down.end.cy,.001f)
            for(i in 0..120){
                val (u,d)=StageSnapshotMotion.visibleFractions(pair,i/120f,screen,emptyList())
                assertTrue(u in 0f..1f && d in 0f..1f)
            }
        }
    }
    @Test fun sameSlotKeepsExactCenterRouteAcrossTaskAspectChanges(){
        val phone=b(0f,0f,1080f,2354f)
        val leftMain=b(264f,390f,1074f,2136f)
        val rightMain=b(6f,390f,816f,2136f)
        val topMain=b(180f,714f,900f,2262f)
        val left=(0..3).map { i->b(30f,312f+444*i,246f,732f+444*i) }
        val right=(0..3).map { i->b(834f,312f+444*i,1050f,732f+444*i) }
        val top=(0..3).map { i->b(126f+216*i,312f,318f+216*i,672f) }
        val fold=b(0f,0f,2048f,2248f)
        val foldSlots=(0..3).map { i->b(20f,100f+510*i,280f,570f+510*i) }
        val tablet=b(0f,0f,2560f,1600f)
        val tabletSlots=(0..3).map { i->b(30f,180f+330*i,350f,470f+330*i) }
        data class Layout(val screen:StageSnapshotMotion.Box,val main:StageSnapshotMotion.Box,
            val slots:List<StageSnapshotMotion.Box>)
        val layouts=listOf(Layout(phone,leftMain,left),Layout(phone,rightMain,right),
            Layout(phone,topMain,top),Layout(fold,b(300f,160f,2020f,2200f),foldSlots),
            Layout(tablet,b(400f,160f,2450f,1440f),tabletSlots))
        val dimensions=listOf(1080 to 2354,2354 to 1080,1440 to 1440,720 to 1600)
        for(layout in layouts) for(small in layout.slots){
            val excluded=layout.slots.filter{it!=small}
            val reference=checkNotNull(StageSnapshotMotion.pairedFlights(small,layout.main,
                1080,2354,1080,2354,layout.screen,excluded))
            for((iw,ih) in dimensions) for((ow,oh) in dimensions){
                val actual=checkNotNull(StageSnapshotMotion.pairedFlights(small,layout.main,
                    iw,ih,ow,oh,layout.screen,excluded))
                assertEquals(reference.up.arc,actual.up.arc,0f)
                assertEquals(reference.down.arc,actual.down.arc,0f)
                assertEquals(reference.up.sizeBias,actual.up.sizeBias,0f)
                assertEquals(iw,actual.up.width);assertEquals(ih,actual.up.height)
                assertEquals(ow,actual.down.width);assertEquals(oh,actual.down.height)
                for((now,prior) in listOf(actual.up to reference.up,actual.down to reference.down)){
                    assertEquals(min(now.from.w/now.width,now.from.h/now.height),now.start.scale,.00001f)
                    assertEquals(min(now.to.w/now.width,now.to.h/now.height),now.end.scale,.00001f)
                    var last=now.start
                    for(i in 0..60){
                        val p=i/60f
                        val pose=now.pose(p)
                        val target=prior.pose(p)
                        assertEquals(target.cx,pose.cx,.001f)
                        assertEquals(target.cy,pose.cy,.001f)
                        if(i>0)assertTrue((pose.cx-last.cx)*(now.to.cx-now.from.cx)+
                            (pose.cy-last.cy)*(now.to.cy-now.from.cy)>0f)
                        last=pose
                    }
                }
            }
        }
    }
    @Test fun equalAxisUsesSubpixelNotUnitScaleJump(){
        assertEquals(100.25f,StageSnapshotMotion.hardwareEnd(100f,100f,.25f),.00001f)
        assertEquals(.5001f,StageSnapshotMotion.hardwareEnd(.5f,.5f,.0001f),.00001f)
        assertEquals(.2f,StageSnapshotMotion.hardwareEnd(.8f,.2f,.0001f),.00001f)
    }
}
