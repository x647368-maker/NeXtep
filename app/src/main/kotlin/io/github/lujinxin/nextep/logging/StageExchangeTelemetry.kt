package io.github.x647368maker.atomicworkbench.logging

import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import io.github.x647368maker.atomicworkbench.systemui.StageRuntimeIdentity
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Timing only; one completed tree instead of interleaved, versionless nested log lines. */
internal object StageExchangeTelemetry {
    private val serial=AtomicLong()
    private val active=ThreadLocal<ExchangeTimingTree?>()
    @Volatile private var completed="尚无本次加载代码的交换样本"
    @Volatile private var handoff=""
    fun <T> phase(name:String,block:()->T):T = active.get()?.span(name,block) ?: block()
    fun <T> measure(name:String,block:()->T):T {
        val existing=active.get()
        if(existing!=null)return existing.span(name,block)
        if(name!="exchange_move")return block()
        val id=serial.incrementAndGet()
        val tree=ExchangeTimingTree(SystemClock::elapsedRealtimeNanos)
        val main=Looper.myLooper()==Looper.getMainLooper()
        var ok=false
        active.set(tree);Trace.beginSection("AW:exchange_move")
        try{return tree.span(name,block).also{ok=true}}finally{
            Trace.endSection();active.remove()
            fun ms(n:Long)=String.format(Locale.ROOT,"%.1f",n/1_000_000.0)
            val report=buildString{
                append("#${id} ${StageRuntimeIdentity.BUILD} main=$main ok=$ok\n")
                append("总计/自身 ms（自身相加=总计；总计不可重复相加）\n")
                tree.stats.forEach{(phase,s)->append("$phase ${ms(s.total)}/${ms(s.own)} ×${s.calls}\n")}
                append("exchange_move 自身=未细分代码及测量开销；不含动画与真实缓冲呈现等待")
            }
            completed=report;handoff=""
            runCatching { NeXtepLog.info("exchange_profile",report) }
        }
    }
    fun handoff(stable:Boolean,elapsed:Long){
        handoff="#${serial.get()} 交接：${if(stable)"连续就绪" else "超时回退（不能当作 Surface 已稳定）"} ${elapsed}ms"
        runCatching { NeXtepLog.info("exchange_handoff","${StageRuntimeIdentity.BUILD} $handoff") }
    }
    fun describe():String="测量代码：${StageRuntimeIdentity.BUILD}\n$completed\n$handoff"
}
