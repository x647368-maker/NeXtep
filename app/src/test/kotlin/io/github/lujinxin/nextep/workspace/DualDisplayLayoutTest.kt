package io.github.x647368maker.atomicworkbench.workspace

import io.github.x647368maker.atomicworkbench.systemui.StageTopRowPolicy
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class DualDisplayLayoutTest {
    // Pixel sizes are physical reference canvases; Android logical densities/insets are simulations.
    data class Device(val name:String,val w:Int,val h:Int,val dpi:Int,val inset:Int)
    private val devices=listOf(Device("PMS110-portrait",1264,2780,512,96),Device("PMS110-landscape",2780,1264,512,96),
        Device("N5-inner-portrait",2248,2480,420,80),Device("N5-inner-landscape",2480,2248,420,80),Device("N5-outer",1140,2616,480,80))
    private fun profile(device:Device,count:Int,top:Boolean=true):WorkspaceLayoutProfile {
        val d=device.dpi/160f
        val insets=if(device.w>device.h) WorkspaceNavigationInsets(right=device.inset) else WorkspaceNavigationInsets(bottom=device.inset)
        val b=WorkspaceLayoutProfile.create(device.w,device.h,device.dpi,insets,if(device.w>device.h)1 else 0)
        val toolbar=b.geometry.copy(topHeight=(96*d).toInt())
        val g=if(top) toolbar.copy(railAtTop=true,sidebarWidth=StageTopLayoutMetrics.depth(toolbar,d,count)) else toolbar
        val normal=b.copy(geometry=StageAdaptiveLayout.rail(g,count,d),activeCount=count)
        return StageAdaptiveLayout.mainPriority(normal,count)
    }
    /** Frozen 0.9.4 presentation for numerical before/after comparisons, not a device assertion. */
    private fun baseline094(p:WorkspaceLayoutProfile):WorkspaceLayoutProfile {
        val g=p.geometry;if(!g.railAtTop||g.isLandscape||p.activeCount==0)return p
        val d=p.densityDpi/160f;val body=p.slots.first().let{it.top+it.height}
        val overlap=(g.sidebarPhysicalHeight-body-(8*d).roundToInt()).coerceAtLeast(0)
        if(overlap==0)return p
        val large=g.copy(mainTopOverlap=overlap);val margin=(56*d).roundToInt()
        return if(large.contentLeft-large.availableLeft<margin||large.availableRight-large.contentRight<margin)p else p.copy(geometry=large)
    }
    @Test fun fiveDisplayStatesKeepCardsAndImproveOrPreserveActualContent() {
        for(device in devices)for(count in 0..4)for(top in listOf(true,false)) {
            val p=profile(device,count,top);val old=baseline094(p);val next=StageMainWindowMetrics.enlarge(p,true);val g=next.geometry
            assertEquals(p.slots,next.slots);assertEquals(p.canvas,next.canvas);assertEquals(p.source,next.source)
            assertEquals(p.geometry.sidebarTop,g.sidebarTop);assertEquals(p.geometry.sidebarPhysicalWidth,g.sidebarPhysicalWidth)
            assertTrue("${device.name}/$count/$top",g.contentWidth>=old.geometry.contentWidth && g.contentHeight>=old.geometry.contentHeight)
            assertTrue(g.contentLeft>=g.availableLeft && g.contentRight<=g.availableRight && g.contentBottom<=g.availableBottom)
            assertEquals(g.availableWidth*g.contentScale,g.contentWidth.toFloat(),.51f)
            assertEquals(g.availableHeight*g.contentScale,g.contentHeight.toFloat(),.51f)
            assertEquals(next,WorkspaceLayoutProfile.decode(next.encode()))
            if(top && count==4) {
                val area=g.contentWidth.toLong()*g.contentHeight
                val safe=g.availableWidth.toLong()*g.availableHeight
                println("DISPLAY ${device.name} physical=${device.w}x${device.h} dpi=${device.dpi} safe=${g.availableWidth}x${g.availableHeight} card=${p.slots[0].width}x${p.slots[0].height} before=${old.geometry.contentWidth}x${old.geometry.contentHeight} after=${g.contentWidth}x${g.contentHeight} widthPercent=${100f*g.contentWidth/g.availableWidth} screenAreaPercent=${100.0*area/safe} contentRect=${g.contentLeft},${g.contentTop},${g.contentRight},${g.contentBottom}")
            }
        }
    }
    @Test fun topCardOrderSpacingAndPreviewScaleAreExactlyUnchanged() {
        for(device in devices)for(count in 1..4){
            val p=profile(device,count);val next=StageMainWindowMetrics.enlarge(p,true);val b=p.slots;val d=device.dpi/160f
            fun row(q:WorkspaceLayoutProfile)=StageTopRowPolicy.positions(List(4){it<count},b.map{it.width},b.map{it.height},b.map{it.left},b.map{it.top},q.geometry.sidebarPhysicalWidth,q.geometry.sidebarPhysicalHeight,d,listOf(3,1,0,2))
            assertEquals(row(p),row(next));assertEquals(p.slots,next.slots)
            b.forEach { assertEquals(p.previewTransform(it.width,it.height),next.previewTransform(it.width,it.height)) }
        }
    }
    @Test fun everyEnlargedMainHasClear48dpControlsAndNoClippedSourceCorners() {
        for(device in devices)for(count in 1..4){
            val p=StageMainWindowMetrics.enlarge(profile(device,count),true);val g=p.geometry;val d=device.dpi/160f
            if(g.mainTopOverlap==0)continue
            val app=StageTopLayoutMetrics.Box(g.contentLeft,g.contentTop,g.contentWidth,g.contentHeight)
            val controls=StageTopLayoutMetrics.actions(g.sidebarPhysicalWidth,g.sidebarPhysicalHeight,d)
            val menu=StageMainWindowMetrics.mainHandle(g,d)
            listOf(menu,controls.add.copy(left=controls.add.left+g.sidebarLeft,top=controls.add.top+g.sidebarTop),
                controls.collapse.copy(left=controls.collapse.left+g.sidebarLeft,top=controls.collapse.top+g.sidebarTop)).forEach {
                assertFalse("${device.name}: $it vs $app",it.overlaps(app));assertTrue(it.top>=g.availableTop && it.bottom<=g.availableBottom)
            }
            val x=g.availableRight*g.contentScale+g.contentTranslationX
            val y=g.availableBottom*g.contentScale+g.contentTranslationY
            assertEquals(g.contentRight.toFloat(),x,.51f);assertEquals(g.contentBottom.toFloat(),y,.51f)
            assertEquals(g.availableRight.toFloat(),(x-g.contentTranslationX)/g.contentScale,.001f)
            assertEquals(g.availableBottom.toFloat(),(y-g.contentTranslationY)/g.contentScale,.001f)
        }
    }
    @Test fun foldRoundTripRestoresGeometryWithoutReusingAbsolutePixels() {
        val inner=profile(devices[2],4);val outer=profile(devices[4],4)
        assertNotEquals(inner.slots,outer.slots)
        assertNotEquals(inner.geometry.contentWidth,outer.geometry.contentWidth)
        assertEquals(inner,WorkspaceLayoutProfile.decode(inner.encode()))
        assertEquals(outer,WorkspaceLayoutProfile.decode(outer.encode()))
        assertEquals(inner.slots,profile(devices[2],4).slots)
    }
    @Test fun noConfirmedInputRegionFallsBackForEveryDisplay() {
        devices.forEach { val p=profile(it,4);assertEquals(p,StageMainWindowMetrics.enlarge(p,false)) }
    }
}
