package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class FullSourcePresentationTest {
    private fun profile(w:Int,h:Int,dpi:Int,count:Int,top:Boolean,priority:Boolean):WorkspaceLayoutProfile {
        val d=dpi/160f
        val b=WorkspaceLayoutProfile.create(w,h,dpi,WorkspaceNavigationInsets(24,108,24,96),if(w>h)1 else 0)
        val g=b.geometry.copy(railAtTop=top,topHeight=(StagePresentationPolicy.toolbarDp(priority,true)*d).toInt())
        val selected=StageAdaptiveLayout.rail(g,count,d)
        return StagePresentationPolicy.apply(b.copy(geometry=selected,activeCount=count),priority,true)
    }
    @Test fun fullPhysicalSourceCornersAndBottomNavigationStayVisibleForEveryMode() {
        for((w,h) in listOf(1272 to 2772,2772 to 1272,2248 to 2480,1140 to 2616))
        for(n in 0..4)for(top in listOf(false,true))for(priority in listOf(false,true)) {
            val p=profile(w,h,512,n,top,priority);val g=p.geometry
            val x0=g.contentTranslationX;val y0=g.contentTranslationY
            val x1=x0+w*g.contentScale;val y1=y0+h*g.contentScale
            assertEquals(g.contentLeft.toFloat(),x0,.51f);assertEquals(g.contentTop.toFloat(),y0,.51f)
            assertEquals(g.contentRight.toFloat(),x1,.51f);assertEquals(g.contentBottom.toFloat(),y1,.51f)
            assertTrue(x0>=g.availableLeft-.51f && y0>=g.availableTop-.51f)
            assertTrue(x1<=g.availableRight+.51f && y1<=g.availableBottom+.51f)
            assertTrue(g.contentTranslationX>=0 && g.contentTranslationY>=0)
            assertEquals(w.toFloat()/h,g.contentWidth.toFloat()/g.contentHeight,.002f)
            assertEquals(g,WorkspaceGeometryCodec.decode(WorkspaceGeometryCodec.encode(g)))
            assertEquals(p,WorkspaceLayoutProfile.decode(p.encode()))
        }
    }
    @Test fun twentyPolicyChangesHaveRealMeasurableDifferenceAndStableRoundTrips() {
        for(top in listOf(false,true))for(n in 0..4) {
            val complete=profile(1272,2772,512,n,top,false)
            val priority=profile(1272,2772,512,n,top,true)
            val c=complete.geometry;val p=priority.geometry
            assertTrue(p.contentWidth.toLong()*p.contentHeight > c.contentWidth.toLong()*c.contentHeight)
            if(n>0 && top) assertEquals(complete.slots.map{it.width to it.height},priority.slots.map{it.width to it.height})
            repeat(20) { i ->
                val selected=if(i%2==0)complete else priority
                assertEquals(selected,WorkspaceLayoutProfile.decode(selected.encode()))
                val g=selected.geometry
                for((sx,sy) in listOf(0f to 0f,1272f to 0f,0f to 2772f,1272f to 2772f,600f to 2600f)) {
                    val x=g.contentTranslationX+sx*g.contentScale;val y=g.contentTranslationY+sy*g.contentScale
                    assertEquals(sx,(x-g.contentTranslationX)/g.contentScale,.001f)
                    assertEquals(sy,(y-g.contentTranslationY)/g.contentScale,.001f)
                }
            }
            println("POLICY top=$top count=$n source=1272x2772 complete=${c.contentWidth}x${c.contentHeight}@${c.contentLeft},${c.contentTop} priority=${p.contentWidth}x${p.contentHeight}@${p.contentLeft},${p.contentTop} areaGain=${100.0*(p.contentWidth.toLong()*p.contentHeight-c.contentWidth.toLong()*c.contentHeight)/(c.contentWidth.toLong()*c.contentHeight)} card=${priority.slots[0].width}x${priority.slots[0].height}")
        }
    }
    @Test fun oldGeometryMessagesRemainReadableButNewMarginIsNeverLost() {
        val g=profile(1272,2772,512,4,false,false).geometry
        val message=WorkspaceGeometryCodec.encode(g)
        assertEquals(13,message.split(',').size)
        assertEquals(g,WorkspaceGeometryCodec.decode(message))
        assertEquals(0,WorkspaceGeometryCodec.decode(message.split(',').take(12).joinToString(","))!!.mainInset)
        assertNull(WorkspaceGeometryCodec.decode("bad"))
    }
}
