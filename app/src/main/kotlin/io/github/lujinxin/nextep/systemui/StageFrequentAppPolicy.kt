package io.github.x647368maker.atomicworkbench.systemui

import kotlin.math.exp
import kotlin.math.ln

/** Stable identity keys include the user/profile. No invented suggestions without usage evidence. */
internal object StageFrequentAppPolicy {
    data class Usage(val key: String, val lastUsed: Long, val foregroundMillis: Long = 0, val selections: Int = 0)
    fun select(availableKeys: List<String>, usage: List<Usage>, now: Long, limit: Int = 12): List<String> {
        val allowed = availableKeys.toSet()
        val merged = usage.filter { it.key in allowed && it.lastUsed > 0 }.groupBy { it.key }.map { (key, values) ->
            Usage(key, values.maxOf { it.lastUsed }, values.maxOf { it.foregroundMillis }, values.maxOf { it.selections })
        }
        fun score(u: Usage): Double {
            val age = (now - u.lastUsed).coerceAtLeast(0).toDouble()
            val freshness = exp(-age / (3.0 * 24 * 60 * 60 * 1000))
            return freshness * (4 + 1.5 * ln(1.0 + u.foregroundMillis.coerceAtLeast(0) / 60_000.0) +
                2 * ln(1.0 + u.selections.coerceAtLeast(0)))
        }
        return merged.sortedWith(compareByDescending<Usage> { score(it) }.thenByDescending { it.lastUsed }.thenBy { it.key })
            .take(limit.coerceIn(0, 12)).map { it.key }
    }
}
