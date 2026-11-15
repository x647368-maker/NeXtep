package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class StageDisplayObservationWakeTest {
    private class Fixture {
        var time=0L
        val queued=linkedMapOf<Runnable,Long>()
        var reads=0
        val wake=StageDisplayObservationWake({time},{d,r->queued[r]=time+d},{r->queued.remove(r)})
        fun schedule()=wake.schedule(30){reads++}
        fun runAt(at:Long){
            time=at
            val due=queued.filterValues{it<=at}.keys.toList()
            for(r in due){queued.remove(r);r.run()}
        }
        fun signal(at:Long){time=at;wake.onTaskChanged()}
    }
    @Test fun noEventPreservesExistingPoll(){
        val f=Fixture();f.schedule();f.runAt(29);assertEquals(0,f.reads)
        f.runAt(30);assertEquals(1,f.reads);assertTrue(f.queued.isEmpty())
    }
    @Test fun eventMovesExistingPollWithoutInlineReadOrDuplicate(){
        val f=Fixture();f.schedule();f.signal(12);f.signal(13)
        assertEquals(0,f.reads);assertEquals(1,f.queued.size)
        f.runAt(13);assertEquals(1,f.reads);f.runAt(30);assertEquals(1,f.reads)
    }
    @Test fun oneAdvanceOnlyEvenWhenFirstQueryNeedsMorePolling(){
        val f=Fixture();f.schedule();f.signal(12);f.runAt(12);f.schedule()
        f.signal(24);f.runAt(24);assertEquals(1,f.reads)
        f.runAt(42);assertEquals(2,f.reads)
    }
    @Test fun ignoredEarlyAndAlmostDueEventsKeepExistingDelay(){
        val f=Fixture();f.schedule();f.signal(4);f.signal(24)
        f.runAt(29);assertEquals(0,f.reads);f.runAt(30);assertEquals(1,f.reads)
    }
    @Test fun resetDisarmsOldCallbackAndReenablesOneAdvance(){
        val f=Fixture();f.schedule();f.signal(12);val old=f.queued.keys.single()
        f.wake.reset();old.run();assertEquals(0,f.reads)
        f.schedule();f.signal(24);f.runAt(24);assertEquals(1,f.reads)
        assertTrue(f.queued.isEmpty())
    }
    @Test fun idleEventsDoNotScheduleAnything(){
        val f=Fixture();repeat(100){f.signal(it.toLong())};assertEquals(0,f.reads)
        assertTrue(f.queued.isEmpty())
    }
}
