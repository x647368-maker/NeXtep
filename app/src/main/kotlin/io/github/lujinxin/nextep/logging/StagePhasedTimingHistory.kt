package io.github.x647368maker.atomicworkbench.logging

import java.util.ArrayDeque

/** Bounded in-memory timings of real exchanges; no sampling or background work. */
internal class StagePhasedTimingHistory(private val now: () -> Long) {
    private data class Attempt(val id: Long, val label: String, val start: Long,
        var last: Long, val head: MutableList<String> = mutableListOf(),
        val tail: ArrayDeque<String> = ArrayDeque(), var omitted: Int = 0)
    private var serial = 0L
    private var active: Attempt? = null
    private val history = ArrayDeque<String>()

    @Synchronized fun begin(label: String) {
        finishLocked("superseded")
        val at = now()
        active = Attempt(++serial, label.take(28), at, at)
    }

    @Synchronized fun event(name: String) {
        val a = active ?: return
        val at = now()
        val item = "${name.take(36)}=${(at - a.last).coerceAtLeast(0)}ms@${(at - a.start).coerceAtLeast(0)}ms"
        a.last = at
        if (a.head.size < 6) a.head.add(item)
        else {
            if (a.tail.size == 14) { a.tail.removeFirst(); a.omitted++ }
            a.tail.addLast(item)
        }
    }

    @Synchronized fun finish(outcome: String) = finishLocked(outcome)

    private fun finishLocked(outcome: String) {
        val a = active ?: return
        history.addLast(render(a, outcome.take(24)))
        while (history.size > 5) history.removeFirst()
        active = null
    }

    private fun render(a: Attempt, outcome: String): String = buildString {
        append("#${a.id} ${a.label} $outcome total=${(now() - a.start).coerceAtLeast(0)}ms")
        a.head.forEach { append(" / ").append(it) }
        if (a.omitted > 0) append(" / omitted=${a.omitted}")
        a.tail.forEach { append(" / ").append(it) }
    }

    @Synchronized fun describe(): String = (history.toList() + listOfNotNull(
        active?.let { render(it, "ongoing") })).takeLast(5).joinToString("\n")
        .ifEmpty { "尚无异步任务交换样本" }
}
