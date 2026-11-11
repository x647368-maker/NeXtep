package io.github.x647368maker.atomicworkbench.config

import java.util.IdentityHashMap

/**
 * Tracks only activity instances for which the lifecycle start was observed.
 * Registration usually happens during onResume, after onStart. An older
 * activity's later onStop must not subtract from a seeded current page.
 *
 * No polling, timers, Android services or references retained after reset.
 */
internal class StageIssueForegroundTracker {
    private val started = IdentityHashMap<Any, Boolean>()

    fun reset() = started.clear()

    fun seed(activity: Any) {
        started[activity] = true
    }

    fun isVisible(): Boolean = started.isNotEmpty()

    /** Return true only for a real move from zero visible pages to one. */
    fun start(activity: Any): Boolean {
        val wasEmpty = started.isEmpty()
        val firstStart = started.put(activity, true) == null
        return wasEmpty && firstStart
    }

    /** Return true only if the LAST observed visible activity stops. */
    fun stop(activity: Any): Boolean {
        val wasTracked = started.remove(activity) != null
        return wasTracked && started.isEmpty()
    }
}
