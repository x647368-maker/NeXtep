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
    // One-shot, in-memory async task timing. No timer, capture, frame loop or I/O.
    private var phasedStartedAt = 0L
    private var phasedLastAt = 0L
    private val phasedSegments = StringBuilder()
    @Volatile private var phasedReport = "尚无异步任务交换样本"
    fun beginPhased(label:String) {
        val t=SystemClock.uptimeMillis()
        phasedStartedAt=t
        phasedLastAt=t
        phasedSegments.setLength(0)
        phasedSegments.append(label.take(28))
    }
    fun phasedEvent(event:String) {
        if(phasedStartedAt==0L || phasedSegments.length>=850)return
        val t=SystemClock.uptimeMillis()
        phasedSegments.append(" / ").append(event.take(36))
            .append('=').append((t-phasedLastAt).coerceAtLeast(0L)).append("ms")
        phasedLastAt=t
    }
    fun endPhased(success:Boolean) {
        if(phasedStartedAt==0L)return
        val ms=(SystemClock.uptimeMillis()-phasedStartedAt).coerceAtLeast(0L)
        phasedReport="ok=$success total=${ms}ms | $phasedSegments"
        phasedStartedAt=0L
        phasedSegments.setLength(0)
    }
    // Last five bounded, in-memory exchange trails. Never sample app pixels,
    // read input text, poll in the background, upload data or write to disk.
    // Main SystemUI Looper only; new route archives the previous route.
    private val nativeTapId=AtomicLong()
    private val previousNative=java.util.ArrayDeque<String>(4)
    private var nativeStartedAt=0L
    @Volatile private var nativePath="尚无本次进程的窗口交换记录"
    @Volatile private var nativeHistory=""
    fun beginNativeTap(operation:String="tap_swap") {
        if (nativePath.startsWith("#")) {
            if (previousNative.size>=4) previousNative.removeFirst()
            previousNative.addLast(nativePath)
            nativeHistory=previousNative.joinToString("\n")
        }
        nativeStartedAt=SystemClock.uptimeMillis()
        nativePath="#${nativeTapId.incrementAndGet()} ${StageRuntimeIdentity.BUILD} op=${operation.take(20)}"
    }
    fun nativeEvent(stage:String) {
        val elapsed=(SystemClock.uptimeMillis()-nativeStartedAt).coerceAtLeast(0L)
        val event=" -> ${stage.take(150)}@${elapsed}ms"
        val next=nativePath+event
        // Preserve first route ID/capability decision and last events even
        // when the OEM emits a long sequence of transitions.
        nativePath=if (next.length<=1800) next else
            next.take(600)+" [middle omitted] "+next.takeLast(950)
    }
    fun nativeDescription():String=nativePath
    fun nativeRecent():String=if(nativeHistory.isEmpty()) nativePath
        else nativeHistory+"\n"+nativePath
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
    fun describe():String="测量代码：${StageRuntimeIdentity.BUILD}\n$completed\n$handoff\n异步任务交换：$phasedReport\n最近五次动画路径（仅时间与几何，不含屏幕内容）：\n${nativeRecent()}"
}
