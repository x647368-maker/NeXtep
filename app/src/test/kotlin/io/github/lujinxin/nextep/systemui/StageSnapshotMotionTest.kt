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
    @Test fun samePhysicalSlotNeverReversesSnapshotArcAcrossOccupancyMasks() {
        // Four possible slots per device, including an actual landscape
        // tablet counterexample: its two middle rail slots previously changed
        // arc sign when an adjacent slot was opened or closed.
        data class Layout(
            val screen:StageSnapshotMotion.Box,
            val main:StageSnapshotMotion.Box,
            val slots:List<StageSnapshotMotion.Box>,
        )
        val phone=b(0f,0f,1080f,2354f)
        val layouts=listOf(
            Layout(phone,b(264f,390f,1074f,2136f),
                (0..3).map{val y=312f+444f*it;b(30f,y,246f,y+420f)}),
            Layout(phone,b(6f,390f,816f,2136f),
                (0..3).map{val y=312f+444f*it;b(834f,y,1050f,y+420f)}),
            Layout(phone,b(180f,714f,900f,2262f),
                (0..3).map{val x=126f+216f*it;b(x,312f,x+192f,672f)}),
            Layout(b(0f,0f,2048f,2248f),b(300f,160f,2020f,2200f),
                (0..3).map{val y=100f+510f*it;b(20f,y,280f,y+470f)}),
            Layout(b(0f,0f,2560f,1600f),b(400f,160f,2450f,1440f),
                (0..3).map{val y=180f+330f*it;b(30f,y,350f,y+290f)}),
        )
        for((device,layout) in layouts.withIndex())for((slot,small) in layout.slots.withIndex()) {
            val reference=checkNotNull(StageSnapshotMotion.pairedFlights(
                small,layout.main,1080,2354,1080,2354,layout.screen,emptyList()))
            val refSign=if(reference.up.arc>0f)1 else -1
            for(mask in 0 until 16) {
                if(mask and (1 shl slot)!=0)continue
                val neighbors=layout.slots.filterIndexed { index,_ -> mask and (1 shl index)!=0 }
                val got=checkNotNull(StageSnapshotMotion.pairedFlights(
                    small,layout.main,1080,2354,1080,2354,layout.screen,neighbors))
                assertEquals("device=$device slot=$slot mask=$mask",
                    refSign,if(got.up.arc>0f)1 else -1)
                assertEquals("opposite card uses same selected corridor",
                    got.up.arc,got.down.arc,0f)
                assertTrue(got.up.arc.isFinite() && got.down.arc.isFinite())
            }
        }
    }

    @Test fun boundedPairedCorridorNeverReturnsAPartialRoute(){
        val screen=b(0f,0f,1080f,2354f)
        val small=b(30f,312f,246f,732f)
        val main=b(264f,390f,1074f,2136f)
        val others=listOf(b(30f,756f,246f,1176f),b(30f,1200f,246f,1620f))
        val expected=checkNotNull(StageSnapshotMotion.pairedFlights(small,main,1080,2354,720,1600,screen,others))
        var probes=0
        assertNull(StageSnapshotMotion.pairedFlights(small,main,1080,2354,720,1600,screen,others){++probes<5})
        assertEquals(5,probes)
        assertNull(StageSnapshotMotion.pairedFlights(small,main,1080,2354,720,1600,screen,others){false})
        val bounded=checkNotNull(StageSnapshotMotion.pairedFlights(small,main,1080,2354,720,1600,screen,others){true})
        assertEquals(expected,bounded)
        for(i in 0..120){
            assertEquals(expected.up.pose(i/120f),bounded.up.pose(i/120f))
            assertEquals(expected.down.pose(i/120f),bounded.down.pose(i/120f))
        }
    }
    @Test fun snapshotRoundnessMatchesBothPhysicalEndpointPolicies(){
        val small=b(0f,0f,282f,557f)
        val main=b(304f,0f,1258f,2079f)
        val density=3.5f
        for((w,h) in listOf(1272 to 2772,2772 to 1272,1440 to 1440)){
            val up=checkNotNull(StageSnapshotMotion.flight(small,main,w,h))
            val down=checkNotNull(StageSnapshotMotion.flight(main,small,w,h))
            for((flight,smallFirst) in listOf(up to true,down to false)){
                val corners=StageSnapshotMotion.cornerTrack(flight,smallFirst,density)
                val liveSmall=io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.previewRadius(small.w,small.h,density)
                val liveMain=io.github.x647368maker.atomicworkbench.framework.WindowCornerPolicy.radius(main.w,main.h,density)
                fun expected(whichSmall:Boolean,scale:Float)=minOf(if(whichSmall)liveSmall else liveMain,minOf(w,h)*scale*.5f)
                assertEquals(expected(smallFirst,flight.start.scale),corners.sourceRadius(0f,flight.start.scale)*flight.start.scale,.001f)
                assertEquals(expected(!smallFirst,flight.end.scale),corners.sourceRadius(1f,flight.end.scale)*flight.end.scale,.001f)
                var last=corners.visibleRadius(0f,flight.start.scale)
                for(i in 1..120){
                    val p=i/120f
                    val scale=flight.pose(p).scale
                    val visible=corners.sourceRadius(p,scale)*scale
                    assertTrue(visible.isFinite() && visible>=0f && visible<=minOf(w,h)*scale*.5f+.001f)
                    assertEquals(corners.visibleRadius(p,scale),visible,.001f)
                    assertTrue(abs(visible-last)<5f)
                    last=visible
                }
            }
        }
    }
    @Test fun unpairedOuterCardCornersDoNotDependOnRouteAdmission(){
        for(density in listOf(1f,2f,3.5f,4f)){
            val small=StageSnapshotMotion.endpointRadius(282f,557f,density,true)
            val main=StageSnapshotMotion.endpointRadius(954f,2079f,density,false)
            assertEquals(minOf(16f*density,282f*.24f),small,.001f)
            assertEquals(minOf(16f*density,954f*.08f),main,.001f)
            for((a,z) in listOf(small to main,main to small,small to small)){
                assertEquals(a,StageSnapshotMotion.frameRadius(a,z,0f,282f,557f),.001f)
                assertEquals(z,StageSnapshotMotion.frameRadius(a,z,1f,282f,557f),.001f)
                assertEquals((a+z)*.5f,StageSnapshotMotion.frameRadius(a,z,.5f,282f,557f),.001f)
                assertEquals(2f,StageSnapshotMotion.frameRadius(a,z,.5f,4f,9f),.001f)
            }
        }
        // A short slot distinguishes the preview and main role.
        assertEquals(56f,StageSnapshotMotion.endpointRadius(282f,557f,3.5f,true),.001f)
        assertEquals(22.56f,StageSnapshotMotion.endpointRadius(282f,557f,3.5f,false),.001f)
    }
    @Test fun equalAxisUsesSubpixelNotUnitScaleJump(){
        assertEquals(100.25f,StageSnapshotMotion.hardwareEnd(100f,100f,.25f),.00001f)
        assertEquals(.5001f,StageSnapshotMotion.hardwareEnd(.5f,.5f,.0001f),.00001f)
        assertEquals(.2f,StageSnapshotMotion.hardwareEnd(.8f,.2f,.0001f),.00001f)
    }

    @Test fun boundedCrossingReducesOcclusionWithoutChangingCenterRouteOrEndpoints(){
        val random=kotlin.random.Random(20261115)
        var improved=0
        for(n in 0 until 1500){
            val w=listOf(1080f,1440f,2208f,2560f,1600f)[n%5]
            val h=listOf(2400f,3168f,1840f,1600f,2560f)[n%5]
            val screen=b(0f,0f,w,h)
            val rail=(n/5)%3
            val count=1+random.nextInt(4)
            val cards=(0 until count).map{j->
                val t=(j+.1f)/count;val t2=(j+.85f)/count
                when(rail){
                    0->b(w*.02f,h*t,w*.23f,h*t2)
                    1->b(w*.77f,h*t,w*.98f,h*t2)
                    else->b(w*t,h*.02f,w*t2,h*.22f)
                }
            }
            val main=when(rail){
                0->b(w*.25f,h*.06f,w*.98f,h*.94f)
                1->b(w*.02f,h*.06f,w*.75f,h*.94f)
                else->b(w*.03f,h*.24f,w*.97f,h*.96f)
            }
            val index=random.nextInt(count)
            val others=cards.filterIndexed{j,_->j!=index}
            val actual=checkNotNull(StageSnapshotMotion.pairedFlights(cards[index],main,
                random.nextInt(120,2500),random.nextInt(120,3000),random.nextInt(120,2500),
                random.nextInt(120,3000),screen,others))
            // Same 85 route and bias, but with crossing adjustment disabled.
            val prior=StageSnapshotMotion.PairFlight(actual.up.copy(crossingCompactness=0f),
                actual.down.copy(crossingCompactness=0f))
            var minOld=1f;var minNew=1f
            var lastUp=actual.up.pose(0f);var lastDown=actual.down.pose(0f)
            for(i in 0..128){
                val progress=i/128f
                val old=StageSnapshotMotion.visibleFractions(prior,progress,screen,others)
                val now=StageSnapshotMotion.visibleFractions(actual,progress,screen,others)
                assertTrue("up visibility n=$n i=$i",now.first+.005f>=old.first)
                assertTrue("down visibility n=$n i=$i",now.second+.005f>=old.second)
                minOld=minOf(minOld,old.first,old.second)
                minNew=minOf(minNew,now.first,now.second)
                val up=actual.up.pose(progress);val down=actual.down.pose(progress)
                for((a,z) in listOf(up to prior.up.pose(progress),down to prior.down.pose(progress))){
                    assertEquals(z.cx,a.cx,0f);assertEquals(z.cy,a.cy,0f)
                    if(i==0 || i==128)assertEquals(z.scale,a.scale,0f)
                }
                if(actual.up.growing)assertTrue(up.scale+.00001f>=lastUp.scale)
                else assertTrue(up.scale<=lastUp.scale+.00001f)
                if(actual.down.growing)assertTrue(down.scale+.00001f>=lastDown.scale)
                else assertTrue(down.scale<=lastDown.scale+.00001f)
                lastUp=up;lastDown=down
            }
            if(minNew>minOld+.01f)improved++
        }
        assertTrue("Too few useful crossing improvements: $improved",improved>=400)
    }
}
