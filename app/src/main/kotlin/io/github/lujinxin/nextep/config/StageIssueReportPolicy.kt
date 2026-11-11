package io.github.x647368maker.atomicworkbench.config

/** Pure, no-timer boundaries for an opt-in, low-power bug report session. */
internal object StageIssueReportPolicy {
    const val MAX_DURATION_MS = 30 * 60 * 1000L
    const val MAX_MARKERS = 12
    const val MAX_DESCRIPTION_CHARS = 400
    const val MAX_REPORT_CHARS = 12_000

    fun elapsedMs(start: Long, current: Long): Long? =
        if (start >= 0 && current >= start) current - start else null

    fun canMark(start: Long, current: Long, count: Int): Boolean =
        count < MAX_MARKERS && elapsedMs(start, current)?.let { it <= MAX_DURATION_MS } == true

    fun note(input: String): String = input.filter { it == '\n' || it == '\t' || it.code >= 32 }
        .take(MAX_DESCRIPTION_CHARS)
        .ifBlank { "用户未填写" }
}
