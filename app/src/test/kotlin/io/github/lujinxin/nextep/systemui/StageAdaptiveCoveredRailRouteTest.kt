package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Box
import io.github.x647368maker.atomicworkbench.systemui.StageLiveSurfaceFlightGeometry.Crop
import org.junit.Assert.*
import org.junit.Test

class StageAdaptiveCoveredRailRouteTest {
    // Report gives screen, main transform and crop; rail positions are reconstructed
    // representative values from the portrait screenshot, not exact task metadata.
    private val screen=Box(0f,0f,1272f,2772f)
    private val main=Box(318f,460f,1272f,2539f)
    private val slots=(0..2).map{val y=539f+it*584f;Box(18f,y,300f,y+558f)}
    private val incoming=Crop(0,0,1272,2517)
    private val outgoing=Crop(0,0,954,2079)
    private val g=StageLiveSurfaceFlightGeometry

    @Test fun straightCrossingKeepsOneCenterLineAndRejectsCoveredNeighbors() {
        val viewport = Box(0f, 0f, 1280f, 800f)
        val small = Box(20f, 300f, 180f, 390f)
        val large = Box(260f, 140f, 1220f, 680f)
        val crop = Crop(0, 0, 960, 540)
        val plan = checkNotNull(StageAdaptiveCoveredRailRoute.straightCrossing(
            small, large, crop, crop, viewport, emptyList(), { true },
        ))
        assertEquals(small, plan.at(0f).incoming)
        assertEquals(large, plan.at(1f).incoming)
        assertEquals(small, plan.at(1f).outgoing)
        for (step in 0..120) {
            val p = step / 120f
            val pair = plan.at(p)
            val fraction = (pair.incoming.cx - small.cx) / (large.cx - small.cx)
            assertEquals(small.cy + fraction * (large.cy - small.cy), pair.incoming.cy, .001f)
            assertEquals(small.cx + large.cx, pair.incoming.cx + pair.outgoing.cx, .001f)
            assertEquals(small.cy + large.cy, pair.incoming.cy + pair.outgoing.cy, .001f)
        }
        val neighbor = Box(70f, 395f, 180f, 460f)
        assertNull(StageAdaptiveCoveredRailRoute.straightCrossing(
            small, large, crop, crop, viewport, listOf(neighbor), { true },
        ))
        assertNull(StageAdaptiveCoveredRailRoute.straightCrossing(
            small, large, crop, crop, viewport, emptyList(), { false },
        ))
    }

    @Test fun packedPortraitSlotsUseOneReversiblePathWithoutCoveringNeighbors() {
        val fourSlots = List(4) { index ->
            val top = 516f + index * 474f
            Box(18f, top, 248f, top + 455f)
        }
        for (rail in listOf(slots, fourSlots)) for (small in rail) {
            val neighbors = rail.filter { it != small }
            assertNull(StageAdaptiveCoveredRailRoute.straightCrossing(
                small, main, incoming, outgoing, screen, neighbors, { true },
            ))
            val plan = checkNotNull(StageAdaptiveCoveredRailRoute.railCrossing(
                small, main, incoming, outgoing, screen, neighbors, { true },
            ))
            var lastX = small.cx
            for (step in 0..240) {
                val p = step / 240f
                val pair = plan.at(p)
                val reverse = plan.at(1f - p).outgoing
                assertTrue(pair.incoming.cx >= lastX - .003f)
                assertTrue(g.endpointGapPx(pair.incoming, reverse) < .003f)
                assertTrue(neighbors.all {
                    g.peakSweptOverlap(pair.incoming, pair.incoming, it) <= 1.01 &&
                        g.peakSweptOverlap(pair.outgoing, pair.outgoing, it) <= 1.01
                })
                lastX = pair.incoming.cx
            }
        }
    }

    @Test fun mixedAspectPortraitCanUseCertifiedCoveredRoute() {
        for(small in slots) {
            val obstacles=slots.filter{it!=small}
            assertTrue(g.canUseCoveredFlight(small,main,incoming,outgoing,obstacles))
            assertNull(StageCertifiedRailRoute.choose(small,main,incoming,outgoing,screen,obstacles,true))
            val plan=checkNotNull(StageAdaptiveCoveredRailRoute.choose(small,main,incoming,outgoing,screen,obstacles))
            assertTrue(plan.bound<=.35)
            for(i in 1..2000) {
                val k=plan.at(i/2000f)
                assertTrue(StageCertifiedRailRoute.overlapFraction(k.incoming,k.outgoing)<=.3501)
                for((v,c) in listOf(k.incoming to incoming,k.outgoing to outgoing)) {
                    val f=checkNotNull(StageAdaptiveCoveredRailRoute.frame(v,c))
                    assertTrue(f.crop.left>=c.left&&f.crop.top>=c.top&&f.crop.right<=c.right&&f.crop.bottom<=c.bottom)
                    assertEquals((f.crop.right-f.crop.left)*f.scale,f.box.width,.001f)
                    assertEquals((f.crop.bottom-f.crop.top)*f.scale,f.box.height,.001f)
                    assertTrue(f.box.left>=v.left-.001f&&f.box.top>=v.top-.001f&&f.box.right<=v.right+.001f&&f.box.bottom<=v.bottom+.001f)
                    assertTrue(f.box.left>=-.001f&&f.box.top>=-.001f&&f.box.right<=screen.right+.001f&&f.box.bottom<=screen.bottom+.001f)
                    for(o in obstacles)assertTrue(g.peakSweptOverlap(f.box,f.box,o)<=1.01)
                }
            }
            // Against the actual reconstructed rail positions, verify a single
            // forward horizontal direction for both roles across the rounded
            // trajectory, independent of the distinct source aspect ratios.
            var prior=plan.at(0f)
            for(i in 1..2000) {
                val now=plan.at(i/2000f)
                assertTrue("incoming reverses rail direction at $i",now.incoming.cx+.003f>=prior.incoming.cx)
                assertTrue("outgoing reverses rail direction at $i",now.outgoing.cx-.003f<=prior.outgoing.cx)
                prior=now
            }
            val end=plan.at(1f)
            val up=checkNotNull(StageAdaptiveCoveredRailRoute.frame(end.incoming,incoming))
            val down=checkNotNull(StageAdaptiveCoveredRailRoute.frame(end.outgoing,outgoing))
            assertTrue(up.crop==g.mirrorFrame(small,main,incoming,1f,true)!!.crop)
            assertTrue(down.crop==g.mirrorFrame(main,small,outgoing,1f,true)!!.crop)
            for(t in listOf(.34f,.66f)) {
                val a=plan.at(t-.001f);val b=plan.at(t+.001f)
                assertTrue(g.endpointGapPx(a.incoming,b.incoming)>.05f)
                assertTrue(g.endpointGapPx(a.outgoing,b.outgoing)>.05f)
            }
        }
    }
    @Test fun invalidSourceAndBudgetAndObstacleRejectRatherThanStretch() {
        assertNull(StageAdaptiveCoveredRailRoute.frame(main,Crop(0,0,0,0)))
        assertNull(StageAdaptiveCoveredRailRoute.choose(slots[1],main,incoming,outgoing,screen,emptyList()){false})
        assertNull(StageAdaptiveCoveredRailRoute.choose(slots[1],main,incoming,outgoing,screen,listOf(Box(302f,0f,317f,2772f))))
        for(limit in listOf(1,3,10,30)) {
            var calls=0
            assertNull(StageAdaptiveCoveredRailRoute.choose(slots[0],main,incoming,outgoing,screen,slots.drop(1)){++calls<=limit})
        }
    }
    @Test fun invalidNeighborGeometryCannotSilentlyEnterDirectCrossing() {
        val viewport=Box(0f,0f,1280f,800f)
        val small=Box(20f,300f,180f,390f)
        val large=Box(260f,140f,1220f,680f)
        val crop=Crop(0,0,960,540)
        for(obstacle in listOf(
            Box(Float.NaN,395f,180f,460f),
            Box(70f,395f,70f,460f),
            Box(70f,Float.POSITIVE_INFINITY,180f,460f),
        )) {
            assertNull(StageAdaptiveCoveredRailRoute.straightCrossing(
                small,large,crop,crop,viewport,listOf(obstacle),{true},
            ))
        }
    }

    @Test fun fallbackAdmissionProvesDirectionWithoutFrameSampling() {
        val viewport=Box(0f,0f,1280f,800f)
        val small=Box(20f,300f,180f,390f)
        val large=Box(260f,140f,1220f,680f)
        val crop=Crop(0,0,960,540)
        val good=checkNotNull(StageAdaptiveCoveredRailRoute.straightCrossing(
            small,large,crop,crop,viewport,emptyList(),{true},
        ))
        assertTrue(SmoothPairedRailCandidate.preservesPrimaryDirection(good,small,large))
        val bad=SmoothPairedRailCandidate.Plan(listOf(
            SmoothPairedRailCandidate.Segment(0f,4f,SmoothPairedRailCandidate.Curve(
                PairedRailCandidate.PairBox(small,large),
                PairedRailCandidate.PairBox(
                    Box(1500f,300f,1660f,390f),
                    Box(-400f,140f,-200f,680f),
                ),
                PairedRailCandidate.PairBox(large,small),
            )),
        ),1.0,0f)
        assertFalse(SmoothPairedRailCandidate.preservesPrimaryDirection(bad,small,large))
    }

    @Test fun fallbackAdmissionRejectsSecondaryAxisAndResizeSnapAtCurveSeam() {
        val small=Box(20f,300f,180f,390f)
        val large=Box(260f,140f,1220f,680f)
        val middle=PairedRailCandidate.PairBox(
            Box(340f,300f,500f,390f),Box(340f,140f,500f,680f),
        )
        val shifted=PairedRailCandidate.PairBox(
            Box(340f,312f,500f,402f),Box(340f,152f,500f,692f),
        )
        val start=PairedRailCandidate.PairBox(small,large)
        val end=PairedRailCandidate.PairBox(large,small)
        val segments=listOf(
            SmoothPairedRailCandidate.Segment(0f,2f,
                SmoothPairedRailCandidate.Curve(start,middle,middle)),
            SmoothPairedRailCandidate.Segment(2f,4f,
                SmoothPairedRailCandidate.Curve(shifted,shifted,end)),
        )
        assertFalse(SmoothPairedRailCandidate.preservesPrimaryDirection(
            SmoothPairedRailCandidate.Plan(segments,1.0,0f),small,large,
        ))
    }

    @Test fun nonzeroCropOriginsArePreserved() {
        val c=Crop(23,47,1295,2564)
        val f=checkNotNull(StageAdaptiveCoveredRailRoute.frame(main,c))
        assertTrue(f.crop.left>=c.left&&f.crop.top>=c.top&&f.crop.right<=c.right&&f.crop.bottom<=c.bottom)
        assertEquals(f.box.left,f.x+f.crop.left*f.scale,.001f)
        assertEquals(f.box.top,f.y+f.crop.top*f.scale,.001f)
    }
}
