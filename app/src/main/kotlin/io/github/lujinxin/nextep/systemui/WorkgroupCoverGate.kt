package io.github.x647368maker.atomicworkbench.systemui

/** Coordinates first rendered cover, native verification and visual handoff.
 * Stale render callbacks cannot start a later group's task operation. */
internal class WorkgroupCoverGate {
    enum class Reveal { WAIT, READY, LATE }
    private var generation = 0
    private var active = false
    private var startClaimed = false
    private var verifiedAt: Long? = null

    fun begin(): Int {
        generation++
        active = true
        startClaimed = false
        verifiedAt = null
        return generation
    }

    fun isCurrent(token: Int) = active && token == generation

    fun claimStart(token: Int): Boolean {
        if (!isCurrent(token) || startClaimed) return false
        startClaimed = true
        return true
    }

    fun verified(token: Int, now: Long) {
        if (isCurrent(token) && startClaimed && verifiedAt == null) verifiedAt = now
    }

    fun reveal(token: Int, now: Long, buffersReady: Boolean): Reveal {
        if (!isCurrent(token)) return Reveal.WAIT
        val verified = verifiedAt ?: return Reveal.WAIT
        val elapsed = now - verified
        return when {
            elapsed >= 120 && buffersReady -> Reveal.READY
            elapsed >= 2000 -> Reveal.LATE
            else -> Reveal.WAIT
        }
    }

    fun cancel() { generation++; active = false; verifiedAt = null }
}
