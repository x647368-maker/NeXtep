package io.github.x647368maker.atomicworkbench.display

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Candidate only: authorizes one speculative read, never a mutation or publication. */
private class GuardedPrefetch(private val deadline: Long, private val now: () -> Long) {
    private enum class State { READY, READING, REVOKED }
    private val state = AtomicReference(State.READY)

    fun tryBeginRead(): Boolean {
        if (now() >= deadline) return false
        if (!state.compareAndSet(State.READY, State.READING)) return false
        // A reserved read may race cancellation and finish. Its result is
        // never trusted without the complete owner validation.
        return state.get() == State.READING && now() < deadline
    }

    fun revoke() { state.set(State.REVOKED) }

    // Additional full owner-only session/geometry/identity checks mandatory.
    fun canConsiderResult(): Boolean = state.get() == State.READING && now() < deadline
}

class StageExchangeGuardedPrefetchCandidateTest {
    private fun exercise(case: Int) {
        val clock=AtomicLong(0)
        val ticket=GuardedPrefetch(100,clock::get)
        val owner=Thread.currentThread()
        val replies=LinkedBlockingQueue<()->Unit>()
        val lane=StageExchangeBinderLane{replies.add(it)}
        val moving=CountDownLatch(1); val moveDone=CountDownLatch(1)
        val reading=CountDownLatch(1); val readDone=CountDownLatch(1)
        var reads=0; var publications=0; var acknowledged=0
        var ownerCurrent=true; var matches=true
        check(lane.submit({
            check(Thread.currentThread()!==owner)
            moving.countDown();check(moveDone.await(5,TimeUnit.SECONDS))
            if(ticket.tryBeginRead()){
                reads++;reading.countDown();check(readDone.await(5,TimeUnit.SECONDS))
            }
        }){ result ->
            check(Thread.currentThread()===owner);result.getOrThrow();acknowledged++
            if(ticket.canConsiderResult() && ownerCurrent && matches)publications++
        })
        try {
            check(moving.await(5,TimeUnit.SECONDS))
            when(case){0->ticket.revoke();1->clock.set(100)}
            check(!lane.submit({error("overlap")}){})
            moveDone.countDown()
            if(case>=2){
                check(reading.await(5,TimeUnit.SECONDS));check(!lane.submit({error("overlap")}){})
                when(case){2->ticket.revoke();3->clock.set(100);4->ownerCurrent=false;5->matches=false}
            }
            readDone.countDown()
            val reply=checkNotNull(replies.poll(5,TimeUnit.SECONDS))
            if(case==6)ticket.revoke()
            check(acknowledged==0 && publications==0);check(!lane.submit({error("overlap")}){})
            reply();check(acknowledged==1)
            check(reads==if(case<2)0 else 1)
            check(publications==if(case==7)1 else 0)
            check(!ticket.tryBeginRead())
        }finally{moveDone.countDown();readDone.countDown()}
    }

    @Test fun revokedDuringMoveSkipsRead()=exercise(0)
    @Test fun expiredDuringMoveSkipsRead()=exercise(1)
    @Test fun revokedDuringReadDiscardsResult()=exercise(2)
    @Test fun expiredDuringReadDiscardsResult()=exercise(3)
    @Test fun ownerInvalidatedDiscardsResult()=exercise(4)
    @Test fun identityMismatchDiscardsResult()=exercise(5)
    @Test fun revokedBeforeOwnerConsumesReplyDiscardsResult()=exercise(6)
    @Test fun validOwnerConsidersOneResultAfterWorkerReply()=exercise(7)
}
