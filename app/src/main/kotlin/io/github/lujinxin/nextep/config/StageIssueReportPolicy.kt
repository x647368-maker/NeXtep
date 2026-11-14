package io.github.x647368maker.atomicworkbench.config

/** Pure rules; no background sampling or periodic timers. */
internal object StageIssueReportPolicy {
    const val MAX_DURATION_MS = 30 * 60 * 1000L
    const val MAX_MARKERS = 12
    const val MAX_DESCRIPTION_CHARS = 400
    const val MAX_REPORT_CHARS = 48_000
    const val MAX_EXCHANGE_DIAGNOSTICS_CHARS = 24_000

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

    fun exchangeSection(startPid: Int, startLastId: Long?, endPid: Int?, diagnostics: String?): String = buildString {
        appendLine("【动画运行诊断｜结束快照】")
        if (endPid == null || diagnostics.isNullOrBlank()) {
            appendLine("不可用：SystemUI 未响应或当前加载模块未提供交换记录。不能将缺失记录视为无故障。")
        } else {
            appendLine("SystemUI 进程：${if (startPid < 0) "起始快照不可用" else if (startPid == endPid) "起止进程号相同" else "进程号已变化，历史记录可能丢失"}")
            appendLine("起始快照最后交换编号：${startLastId ?: "未知或无记录"}")
            appendLine("以下为结束时本进程最近五次交换；可能含开始前的操作，按编号辨别。GPU commit 不等于像素呈现，回调间隔不等于实际 FPS。")
            if (diagnostics.length <= MAX_EXCHANGE_DIAGNOSTICS_CHARS) appendLine(diagnostics)
            else appendLine("诊断超出安全上限，未完整导出；请勿当作完整记录。")
        }
    }

    fun lastSwapId(diagnostics: String?): Long? = diagnostics?.lineSequence()
        ?.dropWhile { it != "最近5次动画路径：" }?.drop(1)
        ?.mapNotNull { Regex("^#(\\d+) ").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        ?.maxOrNull()

    fun note(input: String): String = input.filter { it == '\n' || it == '\t' || it.code >= 32 }
        .take(MAX_DESCRIPTION_CHARS).ifBlank { "用户未填写" }
}
