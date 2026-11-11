package io.github.x647368maker.atomicworkbench.config

/** Pure rules; no background sampling or periodic timers. */
internal object StageIssueReportPolicy {
    const val MAX_DURATION_MS = 30 * 60 * 1000L
    const val MAX_MARKERS = 12
    const val MAX_DESCRIPTION_CHARS = 400
    const val MAX_REPORT_CHARS = 12_000

    val DURATION_OPTIONS_MS = listOf(5 * 60_000L, 15 * 60_000L, 30 * 60_000L)
    const val DEFAULT_DURATION_MS = 15 * 60_000L

    fun selectedDuration(durationMs: Long): Long =
        durationMs.takeIf { it in DURATION_OPTIONS_MS } ?: DEFAULT_DURATION_MS

    fun elapsedMs(start: Long, current: Long): Long? =
        if (start >= 0 && current >= start) current - start else null

    fun isDue(start: Long, current: Long, durationMs: Long): Boolean =
        elapsedMs(start, current)?.let { it >= selectedDuration(durationMs) } ?: true

    fun remainingMs(start: Long, current: Long, durationMs: Long): Long =
        (selectedDuration(durationMs) - (elapsedMs(start, current) ?: selectedDuration(durationMs))).coerceAtLeast(0)

    fun canMark(start: Long, current: Long, count: Int): Boolean =
        count < MAX_MARKERS && elapsedMs(start, current)?.let { it <= MAX_DURATION_MS } == true

    fun note(input: String): String = input.filter { it == '\n' || it == '\t' || it.code >= 32 }
        .take(MAX_DESCRIPTION_CHARS).ifBlank { "用户未填写" }
}
