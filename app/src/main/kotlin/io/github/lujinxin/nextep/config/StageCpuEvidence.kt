package io.github.x647368maker.atomicworkbench.config

/** CPU-time equivalent for one whole SystemUI process across two snapshots.
 *  Does not estimate GPU use, watts, or the contribution of this module.
 */
internal object StageCpuEvidence {
    fun averageCoreEquivalents(cpuStartMs: Long, cpuEndMs: Long,
        elapsedStartMs: Long, elapsedEndMs: Long): Double? {
        if (cpuStartMs < 0L || cpuEndMs < cpuStartMs ||
            elapsedStartMs < 0L || elapsedEndMs <= elapsedStartMs) return null
        return (cpuEndMs - cpuStartMs).toDouble() /
            (elapsedEndMs - elapsedStartMs).toDouble()
    }
}
