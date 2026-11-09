package io.github.x647368maker.atomicworkbench.logging

import android.os.Looper
import android.os.SystemClock
import android.os.Trace

/** Read-only timing. Never changes task order, polling deadlines, geometry or buffers. */
internal object StageExchangeTelemetry {
    private val recent = ArrayDeque<String>()
    fun <T> measure(phase: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val ui = Looper.myLooper() == Looper.getMainLooper()
        Trace.beginSection("AW:$phase")
        var ok = false
        try { return block().also { ok = true } }
        finally {
            Trace.endSection()
            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            val line = "%s %.1fms main=%s ok=%s".format(java.util.Locale.ROOT, phase, ms, ui, ok)
            synchronized(recent) { if (recent.size == 16) recent.removeFirst(); recent.addLast(line) }
            NeXtepLog.info("exchange_timing", line)
        }
    }
    fun describe(): String = synchronized(recent) {
        if (recent.isEmpty()) "尚无本次启动的任务迁移样本" else recent.joinToString("\n")
    }
}
