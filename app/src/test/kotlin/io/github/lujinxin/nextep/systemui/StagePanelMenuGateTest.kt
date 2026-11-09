package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StagePanelMenuGateTest {
    private class Fixture {
        var state=StagePanelMenuGate.State.CLOSED;var time=0L;var dismissed=0;var shown=0;var failed=0;var collapses=0;var alive=true
        val tasks=java.util.PriorityQueue<Pair<Long,()->Unit>>(compareBy{it.first})
        val gate=StagePanelMenuGate({state},{time},{delay,action->tasks.add(time+delay to action)},{dismissed++})
        fun request(success:Boolean=true)=gate.request({alive},{collapses++;success},{shown++},{failed++})
        fun advance(ms:Long){val end=time+ms;var n=0;while(tasks.peek()?.first?.let{it<=end}==true){check(n++<100);val t=tasks.remove();time=t.first;t.second()};time=end}
    }
    @Test fun closedPanelMenuRemainsImmediateAcrossTwentyOpenCloseCycles(){
        val f=Fixture();repeat(20){f.request();f.gate.cancel()};assertEquals(20,f.shown);assertEquals(0,f.collapses);assertEquals(0,f.failed)
    }
    @Test fun expandedPanelMustActuallyCloseAndSettleBeforeMenuAcrossTwentyCycles(){
        val f=Fixture();repeat(20){f.state=StagePanelMenuGate.State.OPEN;f.request();val before=f.shown;f.advance(180);assertEquals(before,f.shown);f.state=StagePanelMenuGate.State.CLOSED;f.advance(60);assertEquals(before,f.shown);f.advance(120);assertEquals(before+1,f.shown)};assertEquals(20,f.collapses);assertEquals(0,f.failed)
    }
    @Test fun rejectedCollapseAndUnknownTimeoutNeverDisplayMenu(){
        val f=Fixture();f.state=StagePanelMenuGate.State.OPEN;f.request(false);assertEquals(1,f.failed);assertEquals(0,f.shown)
        f.state=StagePanelMenuGate.State.UNKNOWN;f.request();f.advance(1300);assertEquals(2,f.failed);assertEquals(0,f.shown);assertTrue(f.tasks.isEmpty())
    }
    @Test fun staleCallbacksCannotShowAfterNewRequestOrExitOrDetachedAnchor(){
        val f=Fixture();f.state=StagePanelMenuGate.State.OPEN;f.request();f.gate.cancel();f.state=StagePanelMenuGate.State.CLOSED;f.advance(1300);assertEquals(0,f.shown)
        f.state=StagePanelMenuGate.State.OPEN;f.request();f.request();f.state=StagePanelMenuGate.State.CLOSED;f.advance(200);assertEquals(1,f.shown)
        f.state=StagePanelMenuGate.State.OPEN;f.request();f.alive=false;f.state=StagePanelMenuGate.State.CLOSED;f.advance(1300);assertEquals(1,f.shown)
    }
    @Test fun panelOpeningDismissesMenuAndReopeningResetsClosedSettleTimer(){
        val f=Fixture();f.request();val before=f.dismissed;f.state=StagePanelMenuGate.State.OPEN;f.gate.panelChanged();assertEquals(before+1,f.dismissed)
        f.request();f.state=StagePanelMenuGate.State.CLOSED;f.advance(60);f.state=StagePanelMenuGate.State.OPEN;f.advance(60);f.state=StagePanelMenuGate.State.CLOSED;f.advance(120);assertEquals(1,f.shown);f.advance(60);assertEquals(2,f.shown)
    }
}
