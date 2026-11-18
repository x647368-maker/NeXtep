package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageHeldEndpointPolicyTest {
    private val p=StageHeldEndpointPolicy
    private fun inside(f:StageHeldEndpointPolicy.Fit,l:Float,t:Float,r:Float,b:Float) {
        val x1=f.x+f.left*f.scale
        val x2=f.x+f.right*f.scale
        val y1=f.y+f.top*f.scale
        val y2=f.y+f.bottom*f.scale
        assertTrue(x1>=l-.01f && x2<=r+.01f)
        assertTrue(y1>=t-.01f && y2<=b+.01f)
        assertEquals((l+r)*.5f,(x1+x2)*.5f,.01f)
        assertEquals((t+b)*.5f,(y1+y2)*.5f,.01f)
    }
    @Test fun promotedRetainedRootUsesNewPhysicalMainCoordinates() {
        val f=checkNotNull(p.fit(0f,460f,954f,2539f,
            0,0,1272,2772,.75f,0f,460f,false))
        assertEquals(0,f.left)
        assertEquals(460,f.top)
        assertEquals(954,f.right)
        assertEquals(2539,f.bottom)
        assertEquals(1f,f.scale,.001f)
        inside(f,0f,460f,954f,2539f)
    }
    @Test fun detachedRootKeepsLocalCoordinates() {
        val f=checkNotNull(p.fit(214f,821f,1058f,2660f,
            0,0,1272,2772,.6634199f,214f,821f,true))
        assertEquals(0,f.left)
        assertEquals(0,f.top)
        assertEquals(1272,f.right)
        assertEquals(2772,f.bottom)
        inside(f,214f,821f,1058f,2660f)
    }
    @Test fun demotedProducerUsesNewLocalCanvasAcrossDeviceShapes() {
        for (size in listOf(1272 to 2517,720 to 1120,1800 to 1200,1200 to 1800)) {
            for (box in listOf(floatArrayOf(17f,200f,267f,695f),
                floatArrayOf(300f,122f,650f,422f),floatArrayOf(1400f,210f,1720f,670f))) {
                val f=checkNotNull(p.fit(box[0],box[1],box[2],box[3],
                    0,0,size.first,size.second,1f,0f,0f,false))
                assertEquals(0,f.left)
                assertEquals(0,f.top)
                assertEquals(size.first,f.right)
                assertEquals(size.second,f.bottom)
                inside(f,box[0],box[1],box[2],box[3])
            }
        }
    }
    private fun coveredInside(
        fit:StageHeldEndpointPolicy.Fit,
        l:Float,t:Float,r:Float,b:Float,
    ) {
        val left=fit.x+fit.left*fit.scale
        val right=fit.x+fit.right*fit.scale
        val top=fit.y+fit.top*fit.scale
        val bottom=fit.y+fit.bottom*fit.scale
        // No stretching: one uniform scale and no letterbox gap.
        assertTrue("left gap: $left > $l",left<=l+.02f)
        assertTrue("top gap: $top > $t",top<=t+.02f)
        assertTrue("right gap: $right < $r",right>=r-.02f)
        assertTrue("bottom gap: $bottom < $b",bottom>=b-.02f)
        // Integer crop rounding cannot expose neighbors beyond two pixels.
        assertTrue("overshoot left",l-left<=2.05f)
        assertTrue("overshoot right",right-r<=2.05f)
        assertTrue("overshoot top",t-top<=2.05f)
        assertTrue("overshoot bottom",bottom-b<=2.05f)
        assertEquals((l+r)*.5f,(left+right)*.5f,.05f)
        assertEquals((t+b)*.5f,(top+bottom)*.5f,.05f)
    }

    @Test fun coveredGpuEndpointDoesNotSwitchToContainedLetterboxOnProducerRebase() {
        // OnePlus API37 source/target dimensions recorded during v89 task
        // exchange. A 271x535 source into 973x2120 main needs an actual
        // center crop. Old fit() would leave almost 200px of uncovered height.
        val old=checkNotNull(p.fit(0f,0f,973f,2120f,
            0,0,271,535,1f,0f,0f,true))
        val containedHeight=(old.bottom-old.top)*old.scale
        assertTrue(containedHeight<2000f)
        val cover=checkNotNull(p.coveredFit(0f,0f,973f,2120f,
            0,0,271,535,1f,0f,0f,true))
        assertTrue(cover.right-cover.left<271)
        coveredInside(cover,0f,0f,973f,2120f)
        // Reverse direction: no sudden side bars when the former main
        // producer lands in the small rail.
        val reverse=checkNotNull(p.coveredFit(13f,420f,284f,955f,
            0,0,973,2120,1f,0f,0f,true))
        assertTrue(reverse.bottom-reverse.top<2120)
        coveredInside(reverse,13f,420f,284f,955f)
    }

    @Test fun coveredFitLeavesExactRetainedAndDetachedEndpointUnmodified() {
        for(detached in listOf(true,false)) {
            val exact=checkNotNull(p.fit(0f,460f,954f,2539f,
                0,0,1272,2772,.75f,0f,460f,detached))
            val cover=checkNotNull(p.coveredFit(0f,460f,954f,2539f,
                0,0,1272,2772,.75f,0f,460f,detached))
            // Both calls must preserve a genuinely already matched target.
            assertEquals(exact,cover)
        }
    }

    @Test fun coveredFitIsFailClosedForUnsafeOrNonFiniteGeometry() {
        assertNull(p.coveredFit(0f,0f,973f,2120f,0,0,1,535,
            1f,0f,0f,true))
        assertNull(p.coveredFit(0f,0f,973f,2120f,0,0,271,535,
            Float.NaN,0f,0f,true))
        assertNull(p.coveredFit(0f,0f,973f,2120f,0,0,271,535,
            0f,0f,0f,false))
        assertNull(p.coveredFit(0f,0f,Float.POSITIVE_INFINITY,2120f,
            0,0,271,535,1f,0f,0f,true))
        assertNull(p.coveredFit(0f,0f,973f,2120f,0,0,271,535,
            1f,Float.NaN,0f,true))
    }

    @Test fun invalidGeometryDoesNotWriteAnEndpoint() {
        assertNull(p.fit(0f,0f,0f,100f,0,0,720,1120,1f,0f,0f,false))
        assertNull(p.fit(0f,0f,100f,100f,0,0,1,1120,1f,0f,0f,false))
        assertNull(p.fit(0f,0f,100f,100f,0,0,720,1120,Float.NaN,0f,0f,false))
        assertNull(p.fit(0f,0f,100f,100f,0,0,720,1120,0f,0f,0f,false))
        assertNull(p.fit(0f,0f,100f,100f,0,0,720,1120,1f,Float.POSITIVE_INFINITY,0f,false))
    }

    @Test fun randomizedRebaseRespectsSourceBoundsAndUniformCenteredCover() {
        val random=kotlin.random.Random(89)
        var admitted=0
        var safeFallback=0
        repeat(100_000) {
            val l=random.nextInt(-50,1800).toFloat()
            val t=random.nextInt(-50,1800).toFloat()
            val w=random.nextInt(120,1800).toFloat()
            val h=random.nextInt(150,2800).toFloat()
            val cl=random.nextInt(0,80); val ct=random.nextInt(0,120)
            val cr=cl+random.nextInt(200,1800); val cb=ct+random.nextInt(300,2800)
            val scale=random.nextDouble(.35,1.6).toFloat()
            val px=random.nextInt(-50,400).toFloat(); val py=random.nextInt(-50,400).toFloat()
            val detached=random.nextBoolean()
            val before=p.fit(l,t,l+w,t+h,cl,ct,cr,cb,scale,px,py,detached)
            val cover=p.coveredFit(l,t,l+w,t+h,cl,ct,cr,cb,scale,px,py,detached)
            if(before==null) { assertNull(cover); return@repeat }
            if(cover==null) { safeFallback++; return@repeat }
            admitted++
            assertTrue(cover.left>=before.left && cover.top>=before.top &&
                cover.right<=before.right && cover.bottom<=before.bottom)
            val x1=cover.left.toDouble()*cover.scale+cover.x
            val y1=cover.top.toDouble()*cover.scale+cover.y
            val x2=cover.right.toDouble()*cover.scale+cover.x
            val y2=cover.bottom.toDouble()*cover.scale+cover.y
            val overflow=(l-x1).coerceAtLeast(0.0)+(t-y1).coerceAtLeast(0.0)+
                (x2-l-w).coerceAtLeast(0.0)+(y2-t-h).coerceAtLeast(0.0)
            val gap=(x1-l).coerceAtLeast(0.0)+(y1-t).coerceAtLeast(0.0)+
                (l+w-x2).coerceAtLeast(0.0)+(t+h-y2).coerceAtLeast(0.0)
            assertTrue("rounded overflow="+overflow,overflow<=2.005)
            assertTrue("target gap="+gap,gap<=.03)
            assertTrue(kotlin.math.abs((x1+x2)/2-l-w/2)<.005)
            assertTrue(kotlin.math.abs((y1+y2)/2-t-h/2)<.005)
        }
        assertTrue(admitted>90_000)
        assertTrue(safeFallback>0)
    }
}
