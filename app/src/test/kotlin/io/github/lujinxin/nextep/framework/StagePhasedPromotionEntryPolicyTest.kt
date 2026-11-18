package io.github.x647368maker.atomicworkbench.framework

import org.junit.Assert.*
import org.junit.Test

class StagePhasedPromotionEntryPolicyTest {
    private val policy=StagePhasedPromotionEntryPolicy

    @Test fun onlyUnexpiredCurrentMainPromotionWithoutPreparedFitSuppressesEntryZoom() {
        for(mask in 0 until 32) {
            fun bit(n:Int)=mask and (1 shl n)!=0
            val active=bit(0)
            val targetMain=bit(1)
            val verified=bit(2)
            val prepared=bit(3)
            val explicit=bit(4)
            val expected=active && targetMain && verified && !prepared && !explicit
            assertEquals("mask=$mask",expected,policy.suppressDuplicateEntrance(
                active,targetMain.let { if(it)0 else 5 },verified,prepared,explicit))
        }
    }

    @Test fun neverClaimPromotionWithoutActualDestinationDisplayReservation() {
        val p=policy
        assertFalse(p.suppressDuplicateEntrance(true,null,true,false,false))
        assertFalse(p.suppressDuplicateEntrance(true,5,true,false,false))
        assertFalse(p.suppressDuplicateEntrance(true,-1,true,false,false))
        assertFalse(p.suppressDuplicateEntrance(false,0,true,false,false))
        assertFalse(p.suppressDuplicateEntrance(true,0,false,false,false))
        // These two established visual paths remain the owners of their own
        // animation. In particular explicit full-screen enter stays enabled.
        assertFalse(p.suppressDuplicateEntrance(true,0,true,true,false))
        assertFalse(p.suppressDuplicateEntrance(true,0,true,false,true))
        assertTrue(p.suppressDuplicateEntrance(true,0,true,false,false))
    }
}
