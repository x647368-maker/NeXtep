package io.github.x647368maker.atomicworkbench.logging
import org.junit.Assert.*
import org.junit.Test
import io.github.x647368maker.atomicworkbench.framework.TaskMemberCache
class ExchangeTimingTest {
    @Test fun nestedTotalsDoNotDoubleCountAndUntrackedWorkRemainsVisible() {
        var time=0L;val tree=ExchangeTimingTree{time}
        tree.span("exchange_move") {time+=10;tree.span("promote"){time+=20;tree.span("query"){time+=30};time+=40};time+=50}
        assertEquals(150,tree.stats["exchange_move"]!!.total)
        assertEquals(60,tree.stats["exchange_move"]!!.own)
        assertEquals(150,tree.stats.values.sumOf{it.own})
    }
    @Test fun exceptionsUnwindAndRepeatedPhasesAccumulate() {
        var time=0L;val tree=ExchangeTimingTree{time}
        try{tree.span("root"){tree.span("q"){time+=4;error("sentinel")}}}catch(_:IllegalStateException){}
        tree.span("q"){time+=6};assertEquals(2,tree.stats["q"]!!.calls);assertEquals(10,tree.stats["q"]!!.total)
    }
    @Test fun oneReadyObservationAtDeadlineIsTimeoutNotStable() {
        val gate=io.github.x647368maker.atomicworkbench.systemui.StageStableHandoff()
        assertEquals(io.github.x647368maker.atomicworkbench.systemui.StageStableHandoff.Decision.TIMEOUT,gate.decide(true,true))
        assertEquals(io.github.x647368maker.atomicworkbench.systemui.StageStableHandoff.Decision.READY,gate.decide(true,true))
        assertEquals(io.github.x647368maker.atomicworkbench.systemui.StageStableHandoff.Decision.WAIT,gate.decide(false,false))
    }
    class Mutable { @JvmField var value=1;fun read()=value }
    class Other { @JvmField var value="fresh" }
    @Test fun metadataCacheNeverCachesValuesAndSeparatesClasses() {
        val a=Mutable();val field=TaskMemberCache.field(Mutable::class.java,"value");val method=TaskMemberCache.method(Mutable::class.java,"read")
        assertEquals(1,field.get(a));a.value=7;assertEquals(7,field.get(a));assertEquals(7,method.invoke(a))
        assertEquals("fresh",TaskMemberCache.field(Other::class.java,"value").get(Other()))
        TaskMemberCache.invalidateField(Mutable::class.java,"value");assertEquals(7,TaskMemberCache.field(Mutable::class.java,"value").get(a))
    }
}
