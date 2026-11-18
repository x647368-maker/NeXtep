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

    @Test fun onlyUncommittedOwnedMainMoveSuppressesSecondRepositionAnimation() {
        // Exhaust every boolean gate; a non-swap task, wrong leash, completed
        // prior destination frame, rotation or leash change must NEVER enter
        // the new one-writer path.
        for(mask in 0 until 128) {
            fun bit(n:Int)=(mask and (1 shl n))!=0
            val active=bit(0)
            val targetMain=bit(1)
            val owned=bit(2)
            val priorReceipt=bit(3)
            val geometryChanged=bit(4)
            val rotated=bit(5)
            val leashChanged=bit(6)
            val expected=active && targetMain && owned && !priorReceipt &&
                geometryChanged && !rotated && !leashChanged
            assertEquals("mask=$mask",expected,
                policy.commitMoveRepositionWithoutExtraAnimator(
                    active,targetMain.let{if(it)0 else 7},
                    owned,priorReceipt,geometryChanged,rotated,leashChanged))
        }
    }

    @Test fun expiredSchedulingDeadlineCannotReintroduceSecondZoomBeforeRealReceipt() {
        // The accepted reservation's absolute deadline is not a Surface
        // transaction receipt. A slow ColorOS migration can exceed it while
        // the identical owner/frame is still awaiting compositor commit.
        val expiredMoveRecord:Long?=1000L
        val uptimeNow=10000L
        assertTrue(checkNotNull(expiredMoveRecord)<uptimeNow)
        assertTrue(policy.commitMoveRepositionWithoutExtraAnimator(
            recordedPromotion=expiredMoveRecord!=null,
            destinationDisplay=0,
            verifiedMainLeash=true,
            previousFinalCommitted=false,
            geometryChanged=true,
            rotationChanged=false,
            leashChanged=false,
        ))
        assertFalse(policy.commitMoveRepositionWithoutExtraAnimator(
            recordedPromotion=false, // explicitly cancelled record
            destinationDisplay=0,
            verifiedMainLeash=true,
            previousFinalCommitted=false,
            geometryChanged=true,
            rotationChanged=false,
            leashChanged=false,
        ))
        assertFalse(policy.commitMoveRepositionWithoutExtraAnimator(
            recordedPromotion=expiredMoveRecord!=null,
            destinationDisplay=0,
            verifiedMainLeash=true,
            previousFinalCommitted=true, // committed real main frame
            geometryChanged=true,
            rotationChanged=false,
            leashChanged=false,
        ))
    }

    @Test fun delayedOrCrossTaskPromotionsCannotBlockNormalLayoutAnimations() {
        // No reservation, any other display, or unrelated task owner: normal
        // workspace reposition still uses the existing ValueAnimator.
        val p=policy
        assertFalse(p.commitMoveRepositionWithoutExtraAnimator(
            true,null,true,false,true,false,false))
        assertFalse(p.commitMoveRepositionWithoutExtraAnimator(
            true,5,true,false,true,false,false))
        assertFalse(p.commitMoveRepositionWithoutExtraAnimator(
            true,0,false,false,true,false,false))
        assertFalse(p.commitMoveRepositionWithoutExtraAnimator(
            false,0,true,false,true,false,false))
        assertFalse(p.commitMoveRepositionWithoutExtraAnimator(
            true,0,true,true,true,false,false))
        assertTrue(p.commitMoveRepositionWithoutExtraAnimator(
            true,0,true,false,true,false,false))
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
