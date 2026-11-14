package io.github.x647368maker.atomicworkbench.logging

/**
 * Bounded, local-only trace of the last five user-triggered exchanges.
 * Holds only stage names and uptime deltas: no screenshots, app names, text,
 * packages, personal content, files, background polling or network access.
 *
 * Synchronized for late shell/compositor callbacks. A new tap explicitly
 * closes an unfinished attempt as superseded, not successful.
 */
internal class StageSwapTraceBuffer(
    private val now: () -> Long,
    private val maxRecords: Int = 5,
    private val maxEvents: Int = 28,
) {
    private data class Attempt(
        val id: Long,
        val operation: String,
        val startMs: Long,
        val events: MutableList<String> = mutableListOf(),
    )
    private val completed = ArrayDeque<String>()
    private var serial = 0L
    private var active: Attempt? = null

    @Synchronized fun begin(operation: String): Long {
        finishLocked("superseded")
        serial++
        active = Attempt(serial, operation.take(24), now())
        eventLocked("begin")
        return serial
    }

    @Synchronized fun activeId(): Long? = active?.id

    // Late OEM compositor callbacks must never be attributed to a newer tap.
    @Synchronized fun event(name: String, expectedId: Long? = null): Boolean {
        val attempt = active ?: return false
        if (expectedId != null && attempt.id != expectedId) return false
        eventLocked(name)
        return true
    }

    private fun eventLocked(name: String) {
        val attempt = active ?: return
        if (attempt.events.size >= maxEvents) return
        val elapsed = (now() - attempt.startMs).coerceAtLeast(0)
        attempt.events.add("${name.take(88)}@${elapsed}ms")
    }

    @Synchronized fun finish(result: String, expectedId: Long? = null) {
        if (expectedId == null || active?.id == expectedId) finishLocked(result)
    }

    private fun finishLocked(result: String) {
        val attempt = active ?: return
        // Even after a very noisy OEM callback sequence, the final outcome
        // must never be silently lost to the per-attempt event cap.
        if (attempt.events.size >= maxEvents && attempt.events.isNotEmpty())
            attempt.events.removeAt(attempt.events.lastIndex)
        eventLocked("end:${result.take(40)}")
        val report = "#${attempt.id} ${attempt.operation} " + attempt.events.joinToString(" > ")
        // The event count and individual event length already bound this record.
        // Do not cut at 1000 characters: that can erase the final failure reason.
        completed.addLast(report)
        while (completed.size > maxRecords.coerceAtLeast(1)) completed.removeFirst()
        active = null
    }

    @Synchronized fun describe(): String {
        val old = completed.toList()
        val ongoing = active?.let {
            "#${it.id} ${it.operation} " + it.events.joinToString(" > ") + " [ongoing]"
        }
        return (old + listOfNotNull(ongoing)).takeLast(maxRecords.coerceAtLeast(1))
            .joinToString("\n").ifEmpty { "尚无本次 SystemUI 进程的交换记录" }
    }
}
