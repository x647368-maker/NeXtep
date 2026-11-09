package io.github.x647368maker.atomicworkbench.config

internal object UpdateCheckSchedule {
    const val SUCCESS_INTERVAL = 12L * 60 * 60 * 1000
    const val FAILURE_INTERVAL = 60L * 60 * 1000
    fun due(enabled: Boolean, checking: Boolean, now: Long, previous: Long, succeeded: Boolean): Boolean {
        if (!enabled || checking) return false
        return previous == 0L || now < previous || now - previous >=
            if (succeeded) SUCCESS_INTERVAL else FAILURE_INTERVAL
    }
}
