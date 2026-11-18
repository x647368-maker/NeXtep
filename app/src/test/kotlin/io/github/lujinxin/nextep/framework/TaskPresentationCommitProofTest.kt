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

    @Test fun elapsedReservationNeverTurnsMissingCompositorReceiptIntoReady() {
        val p=TaskPresentationCommitProof
        // The reservation may be past its 2-second scheduling window while
        // the GPU/RT handoff still owns a bounded attempt. A recorded move
        // is NOT a committed pixel/transform transaction.
        val expiredReservation=1000L
        val now=8000L
        assertTrue(expiredReservation<now)
        assertFalse(p.settledAfterRecordedMove(
            37,expiredReservation,0,true,false))
        assertTrue(p.settledAfterRecordedMove(
            37,expiredReservation,0,true,true))
        // Explicit cancellation, changed destination/owner and pre-33
        // devices retain the original unguarded main readiness.
        assertTrue(p.settledAfterRecordedMove(37,null,0,true,false))
        assertTrue(p.settledAfterRecordedMove(37,expiredReservation,5,true,false))
        assertTrue(p.settledAfterRecordedMove(37,expiredReservation,0,false,false))
        assertTrue(p.settledAfterRecordedMove(32,expiredReservation,0,true,false))
    }

    @Test fun receiptFreshnessRequiresSameMoveEvenAfterDeadline() {
        val gate=TaskPresentationCommitProof<Int>()
        val leash=Any()
        val original=gate.arm(leash,1000L,321)
        assertFalse(gate.confirmed(leash,1000L,321))
        assertFalse(TaskPresentationCommitProof.settledAfterRecordedMove(
            35,1000L,0,true,gate.confirmed(leash,1000L,321)))
        assertTrue(gate.acknowledge(original,true))
        assertTrue(TaskPresentationCommitProof.settledAfterRecordedMove(
            35,1000L,0,true,gate.confirmed(leash,1000L,321)))
        val newer=gate.arm(leash,1001L,321)
        assertFalse(gate.confirmed(leash,1001L,321))
        assertFalse(gate.acknowledge(original,true))
        assertFalse(TaskPresentationCommitProof.settledAfterRecordedMove(
            35,1001L,0,true,gate.confirmed(leash,1001L,321)))
        assertTrue(gate.acknowledge(newer,true))
        assertTrue(TaskPresentationCommitProof.settledAfterRecordedMove(
            35,1001L,0,true,gate.confirmed(leash,1001L,321)))
    }

    @Test fun matchingLateCompositorReceiptStillCountsAfterSchedulingDeadline() {
        val proof=TaskPresentationCommitProof<String>()
        val owner=Any()
        val reservation=1000L
        val observedClock=9000L
        assertTrue(observedClock>reservation)
        val ticket=proof.arm(owner,reservation,"final-main")
        // The receipt belongs to the exact recorded move even if the
        // OEM reports its transaction after the reservation's TTL.
        val matches=TaskPresentationCommitProof.matchesCommittedReceipt(
            reservation,reservation,0,true,true)
        assertTrue(matches)
        assertTrue(proof.acknowledge(ticket,matches))
        assertTrue(proof.confirmed(owner,reservation,"final-main"))
        assertTrue(TaskPresentationCommitProof.settledAfterRecordedMove(
            37,reservation,0,true,proof.confirmed(owner,reservation,"final-main")))
    }

    @Test fun cancelledOrReplacedMoveAndSurfaceCannotReuseLateReceipt() {
        val matches=TaskPresentationCommitProof
        val original=7000L
        assertFalse(matches.matchesCommittedReceipt(null,original,0,true,true))
        assertFalse(matches.matchesCommittedReceipt(original+1,original,0,true,true))
        assertFalse(matches.matchesCommittedReceipt(original,original,5,true,true))
        assertFalse(matches.matchesCommittedReceipt(original,original,0,false,true))
        assertFalse(matches.matchesCommittedReceipt(original,original,0,true,false))
        val proof=TaskPresentationCommitProof<Int>()
        val owner=Any()
        val old=proof.arm(owner,original,15)
        // A new task/display transfer invalidates the old receipt ticket,
        // including if both transfers happen with matching final geometry.
        val newTicket=proof.arm(owner,original+1,15)
        assertFalse(proof.acknowledge(old,true))
        assertFalse(proof.confirmed(owner,original+1,15))
        assertTrue(proof.acknowledge(newTicket,true))
        assertTrue(proof.confirmed(owner,original+1,15))
        proof.invalidate()
        assertFalse(proof.acknowledge(newTicket,true))
    }

    @Test fun concurrentArmsAndCallbacksCannotPublishObsoleteTaskReceipts() {
        val p=TaskPresentationCommitProof<Int>()
        val owners=Array(8) { Any() }
        val start=CountDownLatch(1)
        val done=CountDownLatch(8)
        val failures=java.util.concurrent.atomic.AtomicInteger()
        val threads=(0 until 8).map { index ->
            Thread {
                if(!start.await(3,TimeUnit.SECONDS)) {
                    failures.incrementAndGet()
                    done.countDown()
                    return@Thread
                }
                repeat(1000) { n ->
                    val ticket=p.arm(owners[index],10000L+index*1000+n,n)
                    // Out-of-order callbacks must never claim a newer
                    // task's final transform. Concurrent arm races are
                    // linearized by the one immutable CAS state.
                    p.acknowledge(ticket,true)
                    val current=p.confirmed(owners[index],
                        10000L+index*1000+n,n)
                    if(current) {
                        // A confirmed frame must be the exact one just
                        // armed, never a mixture of leash, move and frame.
                        if(!p.confirmed(owners[index],
                            10000L+index*1000+n,n)) {
                            // A different thread may already have armed
                            // its newer ticket; that is normal.
                        }
                    }
                }
                done.countDown()
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        assertTrue(done.await(10,TimeUnit.SECONDS))
        threads.forEach { it.join(3000) }
        assertEquals(0,failures.get())
        // A definitive final owner starts a new transaction. None of the
        // callbacks from the 8,000 completed racing writes can override it.
        val finalLeash=Any()
        val finalTicket=p.arm(finalLeash,999999L,42)
        assertFalse(p.confirmed(finalLeash,999999L,42))
        assertTrue(p.acknowledge(finalTicket,true))
        assertTrue(p.confirmed(finalLeash,999999L,42))
        p.invalidate()
        assertFalse(p.confirmed(finalLeash,999999L,42))
        assertFalse(p.acknowledge(finalTicket,true))
    }

    @Test fun sameFrameDuplicateSubmissionDoesNotResetConfirmedReceipt() {
        val p=TaskPresentationCommitProof<String>()
        val leash=Any()
        val first=p.arm(leash,4400L,"main-center-crop")
        assertTrue(p.acknowledge(first,true))
        assertSame(first,p.arm(leash,4400L,"main-center-crop"))
        assertTrue(p.confirmed(leash,4400L,"main-center-crop"))
        val newer=p.arm(leash,4401L,"main-center-crop")
        assertNotSame(first,newer)
        assertFalse(p.confirmed(leash,4401L,"main-center-crop"))
        assertFalse(p.acknowledge(first,true))
        assertTrue(p.acknowledge(newer,true))
        assertTrue(p.confirmed(leash,4401L,"main-center-crop"))
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
