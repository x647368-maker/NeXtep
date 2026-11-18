package io.github.x647368maker.atomicworkbench.framework

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class TaskPresentationCommitProofTest {
    @Test fun targetValueOrApplyCallIsNotACompositorReceipt() {
        val p=TaskPresentationCommitProof<String>()
        val leash=Any()
        val ticket=p.arm(leash,2000L,"scale=.75 crop=1272x2772")
        assertFalse(p.confirmed(leash,2000L,"scale=.75 crop=1272x2772"))
        assertTrue(p.acknowledge(ticket,true))
        assertTrue(p.confirmed(leash,2000L,"scale=.75 crop=1272x2772"))
        assertFalse(p.confirmed(leash,2000L,"scale=1 crop=1272x2772"))
    }

    @Test fun ownerReplacementMoveReservationAndFinalFrameRejectStaleCallbacks() {
        val p=TaskPresentationCommitProof<String>()
        val first=Any()
        val second=Any()
        val stale=p.arm(first,3000L,"final-main")
        val next=p.arm(second,3001L,"final-main")
        assertFalse(p.acknowledge(stale,true))
        assertFalse(p.confirmed(first,3000L,"final-main"))
        assertFalse(p.confirmed(second,3000L,"final-main"))
        assertFalse(p.confirmed(second,3001L,"not-final"))
        assertTrue(p.acknowledge(next,true))
        assertTrue(p.confirmed(second,3001L,"final-main"))
        p.invalidate()
        assertFalse(p.confirmed(second,3001L,"final-main"))
        assertFalse(p.acknowledge(next,true))
    }

    @Test fun currentTicketRejectsMissingOwnerAndDuplicateReceipts() {
        val p=TaskPresentationCommitProof<Int>()
        val leash=Any()
        val ticket=p.arm(leash,9000L,123)
        assertSame(ticket,p.arm(leash,9000L,123))
        assertFalse(p.acknowledge(ticket,false))
        assertFalse(p.confirmed(leash,9000L,123))
        assertTrue(p.acknowledge(ticket,true))
        assertFalse(p.acknowledge(ticket,true))
        assertTrue(p.confirmed(leash,9000L,123))
        val later=p.arm(leash,9000L,124)
        assertNotSame(ticket,later)
        assertFalse(p.confirmed(leash,9000L,123))
        assertTrue(p.acknowledge(later,true))
        assertTrue(p.confirmed(leash,9000L,124))
    }

    @Test fun onlyRecentMainPromotionOnSupportedApiCanRequireReceipt() {
        val policy=TaskPresentationCommitProof
        for(sdk in listOf(27,32,33,35,37)) {
            for(active in listOf(false,true)) {
                for(destination in listOf<Int?>(null,0,5)) {
                    for(owned in listOf(false,true)) {
                        val required=sdk>=33 && active && destination==0 && owned
                        assertEquals("sdk=$sdk active=$active dest=$destination owner=$owned",
                            required,policy.needsReceipt(sdk,active,destination,owned))
                    }
                }
            }
        }
    }

    @Test fun callbackRacingWithNextMoveCannotConfirmTheNewMainOwner() {
        val p=TaskPresentationCommitProof<Int>()
        val firstOwner=Any()
        val secondOwner=Any()
        val first=p.arm(firstOwner,5000L,10)
        val ready=CountDownLatch(1)
        val release=CountDownLatch(1)
        val acknowledged=AtomicBoolean(true)
        val thread=Thread {
            ready.countDown()
            release.await(3,TimeUnit.SECONDS)
            acknowledged.set(p.acknowledge(first,true))
        }
        thread.start()
        assertTrue(ready.await(3,TimeUnit.SECONDS))
        val second=p.arm(secondOwner,5100L,11)
        release.countDown()
        thread.join(3000)
        assertFalse(acknowledged.get())
        assertFalse(p.confirmed(secondOwner,5100L,11))
        assertTrue(p.acknowledge(second,true))
        assertTrue(p.confirmed(secondOwner,5100L,11))
    }
}
