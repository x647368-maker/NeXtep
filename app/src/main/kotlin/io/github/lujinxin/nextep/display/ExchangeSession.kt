package io.github.x647368maker.atomicworkbench.display

/** All driver calls and callbacks belong to the same main Looper. */
internal class ExchangeSession<L>(
    val generation: Int,
    val deadline: Long,
    val main: Origin<L>,
    val slot: Origin<L>,
    private val now: () -> Long,
    private val postTurn: (() -> Unit) -> Unit,
    private val driver: Driver,
    private val completed: (Boolean) -> Unit,
) {
    data class Origin<L>(val taskId: Int, val userId: Int?, val token: Any?, val displayId: Int, val layout: L)
    enum class Phase { PREPARED, PROMOTING, DEMOTE_PENDING, DEMOTING, ROLLBACK_PENDING, ROLLING_BACK, COMMITTED, RESTORED, RECOVERY_REQUIRED }
    interface Driver {
        fun promote()
        fun demoteAndVerify()
        fun restoreAndVerify()
    }
    private val owner = Thread.currentThread()
    var phase = Phase.PREPARED
        private set
    var failure: Throwable? = null
        private set
    private var cancelled = false
    private var notified = false
    val settled get() = phase == Phase.COMMITTED || phase == Phase.RESTORED

    fun start() {
        checkThread()
        if (phase != Phase.PREPARED) return
        schedule(Phase.PREPARED)
    }

    fun cancel() {
        checkThread()
        cancelled = true
        if (phase == Phase.RECOVERY_REQUIRED) {
            phase = Phase.ROLLBACK_PENDING
            schedule(phase)
        }
    }

    private fun schedule(expected: Phase) {
        val token = generation
        postTurn {
            checkThread()
            if (token != generation || phase != expected || settled) return@postTurn
            if ((cancelled || now() >= deadline) && phase != Phase.ROLLBACK_PENDING) {
                if (phase == Phase.PREPARED) {
                    // No mutation was requested by this session, but another actor may
                    // have moved either task while the preview was being prepared.
                    // Never release a task-owning display without verifying origins.
                    try {
                        driver.restoreAndVerify()
                        phase = Phase.RESTORED
                        notifyComplete()
                    } catch (error: Throwable) {
                        failure = error
                        phase = Phase.RECOVERY_REQUIRED
                    }
                    return@postTurn
                }
                phase = Phase.ROLLBACK_PENDING
            }
            try {
                when (phase) {
                    Phase.PREPARED -> {
                        phase = Phase.PROMOTING
                        driver.promote()
                        phase = Phase.DEMOTE_PENDING
                        schedule(phase)
                    }
                    Phase.DEMOTE_PENDING -> {
                        phase = Phase.DEMOTING
                        driver.demoteAndVerify()
                        phase = Phase.COMMITTED
                    }
                    Phase.ROLLBACK_PENDING -> {
                        phase = Phase.ROLLING_BACK
                        driver.restoreAndVerify()
                        phase = Phase.RESTORED
                    }
                    else -> Unit
                }
            } catch (error: Throwable) {
                failure = error
                if (phase == Phase.ROLLING_BACK) {
                    // Hold task reservations and displays until an explicit recovery succeeds.
                    phase = Phase.RECOVERY_REQUIRED
                } else {
                    phase = Phase.ROLLBACK_PENDING
                    schedule(phase)
                }
            }
            if (settled) notifyComplete()
        }
    }

    private fun notifyComplete() {
        if (notified) return
        notified = true
        // UI/animation listeners are external to the transaction. A callback
        // failure must not crash the owning SystemUI Looper or restart a move.
        try {
            completed(phase == Phase.COMMITTED)
        } catch (error: Throwable) {
            failure = error
        }
    }

    private fun checkThread() {
        check(Thread.currentThread() === owner) { "Exchange must use its owning Looper" }
    }
}
