package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageTabletColdLaunchPolicyTest {
    @Test fun onlyPhysicalLandscapeY700EnablesNativeFirstFramePlacement() {
        assertTrue(StageTabletColdLaunchPolicy.eligible(2560,1600,"com.example.reader"))
        assertFalse(StageTabletColdLaunchPolicy.eligible(1600,2560,"com.example.reader"))
        assertFalse(StageTabletColdLaunchPolicy.eligible(1140,2616,"com.example.reader"))
        assertFalse(StageTabletColdLaunchPolicy.eligible(2248,2480,"com.example.reader"))
        assertFalse(StageTabletColdLaunchPolicy.eligible(2480,2248,"com.example.reader"))
    }
    @Test fun protectedAppsAndUnresolvedWechatNeverReceiveExperimentalReservation() {
        for(pkg in listOf("","com.android.systemui","io.github.x647368maker.atomicworkbench","com.tencent.mm")) {
            assertFalse(StageTabletColdLaunchPolicy.eligible(2560,1600,pkg))
        }
    }
    @Test fun matchingRequiresExactPackageUserAndShortUserLaunchWindow() {
        assertTrue(StageTabletColdLaunchPolicy.matches("com.example.app",0,
            "com.example.app",0,10000,10001))
        assertTrue(StageTabletColdLaunchPolicy.matches("com.example.app",0,
            "com.example.app",0,10000,12200))
        assertFalse(StageTabletColdLaunchPolicy.matches("com.example.app",0,
            "com.example.app",0,10000,12201))
        assertFalse(StageTabletColdLaunchPolicy.matches("com.example.app",0,
            "com.example.app",0,10000,9999))
        assertFalse(StageTabletColdLaunchPolicy.matches("com.example.app",10,
            "com.example.app",0,10000,11000))
        assertFalse(StageTabletColdLaunchPolicy.matches("com.other.app",0,
            "com.example.app",0,10000,11000))
    }
}
