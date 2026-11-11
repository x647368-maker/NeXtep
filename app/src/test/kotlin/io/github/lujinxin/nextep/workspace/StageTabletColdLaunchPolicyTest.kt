package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageTabletColdLaunchPolicyTest {
    @Test fun onlyY700WideLandscapeCanArm() {
        assertTrue(StageTabletColdLaunchPolicy.arm(2560,1600,"com.tencent.mm",0))
        assertFalse(StageTabletColdLaunchPolicy.arm(1600,2560,"com.tencent.mm",0))
        assertFalse(StageTabletColdLaunchPolicy.arm(1140,2616,"com.tencent.mm",0))
        assertFalse(StageTabletColdLaunchPolicy.arm(2248,2480,"com.tencent.mm",0))
        assertFalse(StageTabletColdLaunchPolicy.arm(2560,1600,"",0))
        assertFalse(StageTabletColdLaunchPolicy.arm(2560,1600,"com.tencent.mm",-1))
    }

    @Test fun onlyExactNewDefaultDisplayFullscreenTaskMayFitBeforeFirstFrame() {
        val pkg="com.tencent.mm"
        val expires=2500L
        fun allowed(name:String?=pkg,user:Int?=0,now:Long=40,
                    display:Int=0,mode:Int=1,vendor:Boolean=false) =
            StageTabletColdLaunchPolicy.matches(pkg,0,expires,
                name,user,now,display,mode,vendor)
        assertTrue(allowed())
        assertFalse(allowed(name="com.other.app"))
        assertFalse(allowed(user=10))
        assertFalse(allowed(user=null))
        assertFalse(allowed(now=2501L))
        assertFalse(allowed(now=-1L))
        assertFalse(allowed(display=3))
        assertFalse(allowed(mode=5))
        assertFalse(allowed(vendor=true)) // ColorOS double-window must stay native.
    }

    @Test fun deadlineIsFiniteAndDoesNotRunBackgroundTimers() {
        assertEquals(2500L,StageTabletColdLaunchPolicy.HANDOFF_MS)
        assertTrue(StageTabletColdLaunchPolicy.matches("com.test",0,3200L,
            "com.test",0,700L,0,1,false))
        assertFalse(StageTabletColdLaunchPolicy.matches("com.test",0,3200L,
            "com.test",0,699L,0,1,false))
    }
}
