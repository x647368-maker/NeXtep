package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class StagePhasedFinishGateTest {
    // Real ExchangeSession; controlled driver and coordinator-shaped callback
    // harness. Not an Android SlotTaskCoordinator instrumentation test.
    private class Fixture(private val finishVisualInReply:Boolean, private val legacy:Boolean=false) {
        val queue=ArrayDeque<()->Unit>()
        var current=true
        var inProgress=true
        var visualFinished=false
        var finalizations=0
        var postedReconciliations=0
        var deliveredEndActions=0
        var failDemotion=false
        var failRestore=false
        lateinit var session:ExchangeSession<Int>
        init {
            session=ExchangeSession(1,100L,
                ExchangeSession.Origin(11,0,"main",0,1),
                ExchangeSession.Origin(22,0,"slot",7,2),
                {0L},{queue.add(it)},object:ExchangeSession.Driver {
                    override fun promote()=Unit
                    override fun demoteAndVerify(){check(!failDemotion)}
                    override fun restoreAndVerify(){check(!failRestore)}
                }, {
                    try { if(finishVisualInReply) visualEnd() }
                    finally { finish() }
                })
        }
        fun finish(){
            if(legacy){
                // Exact v83 guard: generation, real session settled, visual complete.
                if(!current || !session.settled || !visualFinished)return
            }else if(!StagePhasedFinishGate.canFinish(current,inProgress,session.settled,visualFinished))return
            finalizations++
            inProgress=false
            // Reentrant caller after the coordinator has cleared its flag.
            if(finalizations==1 && !legacy)finish()
            deliveredEndActions++
            postedReconciliations++
        }
        fun visualEnd(){visualFinished=true;finish()}
        fun drain(){repeat(12){if(queue.isNotEmpty())queue.removeFirst().invoke()};check(queue.isEmpty())}
    }

    @Test fun pairedOriginalRepeatsReconcileOnSynchronousVisualCompletion(){
        val original=Fixture(true,legacy=true)
        val candidate=Fixture(true)
        original.session.start();original.drain()
        candidate.session.start();candidate.drain()
        assertEquals(2,original.postedReconciliations)
        assertEquals(1,candidate.postedReconciliations)
        assertEquals(ExchangeSession.Phase.COMMITTED,original.session.phase)
        assertEquals(ExchangeSession.Phase.COMMITTED,candidate.session.phase)
    }

    @Test fun synchronousVisualReplyAndFinallyFinalizeOnlyOnce(){
        val f=Fixture(true)
        f.session.start();f.drain()
        assertEquals(1,f.finalizations)
        assertEquals(1,f.postedReconciliations)
        assertEquals(1,f.deliveredEndActions)
        f.visualEnd()
        assertEquals(1,f.finalizations)
    }

    @Test fun visualFirstKeepsReservationUntilRealSessionSettles(){
        val f=Fixture(false)
        f.session.start();f.visualEnd()
        assertEquals(0,f.finalizations)
        f.drain()
        assertEquals(1,f.finalizations)
        assertEquals(1,f.postedReconciliations)
    }

    @Test fun sessionFirstWaitsForVisualEnd(){
        val f=Fixture(false)
        f.session.start();f.drain()
        assertEquals(0,f.finalizations)
        f.visualEnd();f.visualEnd()
        assertEquals(1,f.finalizations)
    }

    @Test fun failedRollbackCannotFinalizeUntilRecoveryActuallySettles(){
        val f=Fixture(true)
        f.failDemotion=true;f.failRestore=true
        f.visualEnd();f.session.start();f.drain()
        assertEquals(ExchangeSession.Phase.RECOVERY_REQUIRED,f.session.phase)
        assertEquals(0,f.finalizations)
        f.failRestore=false;f.session.cancel();f.drain()
        assertEquals(ExchangeSession.Phase.RESTORED,f.session.phase)
        assertEquals(1,f.finalizations)
    }

    @Test fun staleGenerationCannotFinalizeCurrentOwner(){
        val f=Fixture(true)
        f.current=false;f.session.start();f.drain()
        assertEquals(0,f.finalizations)
        assertEquals(0,f.postedReconciliations)
    }
}
