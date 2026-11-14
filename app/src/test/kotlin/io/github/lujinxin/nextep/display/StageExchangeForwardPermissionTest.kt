package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class StageExchangeForwardPermissionTest {
    private class Fixture {
        val queue=ArrayDeque<()->Unit>()
        var clock=0L
        var reply:((Throwable?)->Unit)?=null
        var rollback=0
        var demote=0
        var queries=0
        var result:Boolean?=null
        val session=ExchangeSession(
            21,100L,ExchangeSession.Origin(1,0,"m",0,"main"),
            ExchangeSession.Origin(2,0,"s",7,"slot"),{clock},{queue.add(it)},
            object:ExchangeSession.Driver{
                override val nonBlocking=true
                override fun promote()=error("sync")
                override fun demoteAndVerify()=error("sync")
                override fun restoreAndVerify(){rollback++}
                override fun promoteAsync(done:(Throwable?)->Unit){reply=done}
                override fun demoteAndVerifyAsync(done:(Throwable?)->Unit){demote++;done(null)}
            },{result=it})
        fun start(){session.start();queue.removeFirst().invoke()}
        fun realReply(){
            val error=runCatching{
                check(session.acceptsForwardWork)
                queries++
            }.exceptionOrNull()
            checkNotNull(reply)(error)
            repeat(5){if(queue.isNotEmpty())queue.removeFirst().invoke()}
        }
    }
    @Test fun canceledSessionWaitsForActualReplyButSkipsForwardQuery(){
        val f=Fixture()
        f.start();assertTrue(f.session.acceptsForwardWork)
        f.session.cancel()
        assertFalse(f.session.acceptsForwardWork);assertEquals(0,f.rollback);assertNull(f.result)
        f.realReply()
        assertEquals(0,f.queries);assertEquals(0,f.demote);assertEquals(1,f.rollback)
        assertEquals(false,f.result)
    }
    @Test fun expiredSessionSkipsForwardQueryAfterReply(){
        val f=Fixture()
        f.start();f.clock=100L
        assertFalse(f.session.acceptsForwardWork);assertNull(f.result)
        f.realReply()
        assertEquals(0,f.queries);assertEquals(1,f.rollback);assertEquals(false,f.result)
    }
    @Test fun validSessionRetainsForwardObservationAndCommit(){
        val f=Fixture()
        assertFalse(f.session.acceptsForwardWork)
        f.start();f.realReply()
        assertEquals(1,f.queries);assertEquals(1,f.demote);assertEquals(true,f.result)
        assertFalse(f.session.acceptsForwardWork)
    }
}
