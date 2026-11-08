package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.systemui.StageTopRowPolicy
import org.junit.Assert.*
import org.junit.Test

class MainWindowEnlargementTest {
    private fun profile(count:Int, expanded:Boolean=false, bottom:Int=96):WorkspaceLayoutProfile {
        val b=WorkspaceLayoutProfile.create(1264,2780,512,WorkspaceNavigationInsets(bottom=bottom))
        val g=b.geometry.copy(railAtTop=true,topHeight=((if(expanded)112 else 96)*3.2f).toInt())
        return StageAdaptiveLayout.mainPriority(b.copy(geometry=StageAdaptiveLayout.rail(g,count,3.2f),activeCount=count),count)
    }
    @Test fun largerRealMainDoesNotAlterAnyCardOrCanvas() {
        for(count in 1..4) for(expanded in listOf(false,true)) for(bottom in listOf(0,96)) {
            val p=profile(count,expanded,bottom);val enlarged=StageMainWindowMetrics.enlarge(p,true)
            assertTrue("count=$count expanded=$expanded bottom=$bottom",enlarged.geometry.contentWidth>p.geometry.contentWidth)
            assertTrue(enlarged.geometry.contentHeight>p.geometry.contentHeight)
            assertEquals(p.slots,enlarged.slots);assertEquals(p.canvas,enlarged.canvas);assertEquals(p.source,enlarged.source)
            assertEquals(p.geometry.sidebarTop,enlarged.geometry.sidebarTop)
            assertEquals(p.geometry.sidebarPhysicalHeight,enlarged.geometry.sidebarPhysicalHeight)
            val a=p.slots
            fun poses(q:WorkspaceLayoutProfile)=StageTopRowPolicy.positions(List(4){it<count},a.map{it.width},a.map{it.height},a.map{it.left},a.map{it.top},q.geometry.sidebarPhysicalWidth,q.geometry.sidebarPhysicalHeight,3.2f)
            assertEquals(poses(p),poses(enlarged))
            println("main count=$count quick=$expanded nav=$bottom before=${p.geometry.contentWidth}x${p.geometry.contentHeight} after=${enlarged.geometry.contentWidth}x${enlarged.geometry.contentHeight} cards=${a[0].width}x${a[0].height}")
        }
    }
    @Test fun entireAppIsUniformAndInputProjectionRoundTripsIncludingBottomButtons() {
        for(count in 1..4){
            val p=StageMainWindowMetrics.enlarge(profile(count),true);val g=p.geometry
            assertEquals(g.availableWidth*g.contentScale,g.contentWidth.toFloat(),.51f)
            assertEquals(g.availableHeight*g.contentScale,g.contentHeight.toFloat(),.51f)
            assertTrue(g.contentBottom<=g.availableBottom && g.contentLeft>=g.availableLeft && g.contentRight<=g.availableRight)
            for(x in listOf(g.availableLeft.toFloat(),g.availableWidth/2f,g.availableRight.toFloat()))
            for(y in listOf(g.availableTop.toFloat(),g.availableHeight/2f,g.availableBottom.toFloat())) {
                val displayX=g.contentTranslationX+x*g.contentScale;val displayY=g.contentTranslationY+y*g.contentScale
                assertEquals(x,(displayX-g.contentTranslationX)/g.contentScale,.001f)
                assertEquals(y,(displayY-g.contentTranslationY)/g.contentScale,.001f)
            }
            val lastCard=p.slots.first().let{it.top+it.height}+g.sidebarTop
            assertTrue(g.contentTop>=lastCard+24)
        }
    }
    @Test fun existingControlsNeverCoverMainAndMainMenuHas48dpTarget() {
        for(count in 1..4) for(bottom in listOf(0,96)) {
            val g=StageMainWindowMetrics.enlarge(profile(count,bottom=bottom),true).geometry
            val main=StageTopLayoutMetrics.Box(g.contentLeft,g.contentTop,g.contentWidth,g.contentHeight)
            val controls=StageTopLayoutMetrics.actions(g.sidebarPhysicalWidth,g.sidebarPhysicalHeight,3.2f)
            val menu=StageMainWindowMetrics.mainHandle(g,3.2f)
            val add=controls.add.copy(left=controls.add.left+g.sidebarLeft,top=controls.add.top+g.sidebarTop)
            val collapse=controls.collapse.copy(left=controls.collapse.left+g.sidebarLeft,top=controls.collapse.top+g.sidebarTop)
            assertTrue(menu.width>=153 && menu.height>=153)
            listOf(menu,add,collapse).forEach { assertFalse(it.overlaps(main));assertTrue(it.bottom<=g.availableBottom) }
            assertFalse(menu.overlaps(collapse));assertFalse(menu.overlaps(add))
        }
    }
    @Test fun missingNarrowInputRegionAndOtherLayoutsRetainOriginalSafePresentation() {
        val top=profile(4)
        assertEquals(top,StageMainWindowMetrics.enlarge(top,false))
        assertEquals(profile(0),StageMainWindowMetrics.enlarge(profile(0),true))
        val side=WorkspaceLayoutProfile.create(1264,2780,512,WorkspaceNavigationInsets())
        assertEquals(side,StageMainWindowMetrics.enlarge(side,true))
        val landscape=top.rotatedTo(1)
        assertEquals(landscape,StageMainWindowMetrics.enlarge(landscape,true))
    }
    @Test fun finalGeometrySurvivesCommitAndCollapseWithoutStaleSize() {
        val p=StageMainWindowMetrics.enlarge(profile(4),true)
        val restored=WorkspaceLayoutProfile.decode(p.encode())!!
        assertEquals(p,restored);assertEquals(p.geometry.contentWidth,restored.geometry.contentWidth)
        assertEquals(p.slots,restored.slots)
        val collapsed=StageCollapsedRailPolicy.geometry(p.geometry,3.2f)
        assertEquals(0,collapsed.mainTopOverlap)
        assertTrue(collapsed.contentWidth>p.geometry.contentWidth)
        assertEquals(p.geometry,p.geometry.rotatedTo(1).rotatedTo(0))
    }
    @Test fun fullPortraitCannotFitAt90PercentWidthUnderUnchangedTopCards() {
        val p=StageMainWindowMetrics.enlarge(profile(4),true);val g=p.geometry
        val requiredHeight=g.availableHeight*.90f
        val availableHeight=g.availableBottom-g.contentTop
        assertTrue(requiredHeight>availableHeight)
        // Do not hide or distort source pixels to report an unattainable 90% width.
        assertTrue(g.contentWidth.toFloat()/g.availableWidth<.90f)
    }
}
