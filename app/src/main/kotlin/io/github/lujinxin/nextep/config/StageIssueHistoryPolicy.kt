package io.github.x647368maker.atomicworkbench.config

/** Newest-first local reports; no time-based sorting, so clock changes cannot
 * evict the most recently generated report. The current report wins duplicates. */
internal object StageIssueHistoryPolicy {
    const val MAX_REPORTS = 10
    data class Record(val id: String, val startedAt: Long, val category: String, val text: String)

    fun retain(records: List<Record>): List<Record> = records.asSequence()
        .filter { it.id.isNotBlank() && it.text.isNotBlank() }
        .distinctBy { it.id }.take(MAX_REPORTS).toList()
}
