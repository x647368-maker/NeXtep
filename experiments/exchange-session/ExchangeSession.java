import java.util.Objects;

/** Main-looper confined. A driver owns the Binder calls and verifies the live tasks. */
final class ExchangeSession<L> {
    enum Phase { PREPARED, PROMOTING, BETWEEN_MOVES, DEMOTING, VERIFYING, ROLLBACK_PENDING, ROLLING_BACK, COMMITTED, RESTORED, RECOVERY_REQUIRED }
    record Origin<L>(int taskId, int userId, int displayId, Object token, L layout) {
        Origin {
            if (taskId < 0 || displayId < 0) throw new IllegalArgumentException("Unknown task or display");
            Objects.requireNonNull(token);
            Objects.requireNonNull(layout);
        }
    }
    interface Driver<L> {
        boolean atOrigins(Origin<L> main, Origin<L> slot);
        void promote(Origin<L> slot);
        boolean readyForDemotion(Origin<L> main, Origin<L> slot);
        void demoteAndFit(Origin<L> main, Origin<L> slot);
        boolean committed(Origin<L> main, Origin<L> slot);
        // Re-read both tokens/displays, including when Binder threw after changing state.
        boolean restoreAndVerify(Origin<L> main, Origin<L> slot);
    }
    private final Thread owner = Thread.currentThread();
    final long generation, deadline;
    final Origin<L> main, slot;
    private Phase phase = Phase.PREPARED;
    private boolean cancelled;
    ExchangeSession(long generation, long deadline, Origin<L> main, Origin<L> slot) {
        if (main.taskId == slot.taskId || main.displayId == slot.displayId) throw new IllegalArgumentException("Expected two distinct tasks and displays");
        this.generation = generation;
        this.deadline = deadline;
        this.main = main;
        this.slot = slot;
    }
    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong transaction thread");
    }
    Phase phase() { checkThread(); return phase; }
    boolean canRelease() { return phase() == Phase.COMMITTED || phase == Phase.RESTORED; }
    void cancel() { checkThread(); cancelled = true; }
    // Call once per main-loop turn. A stale callback can never start another move.
    Phase advance(long callbackGeneration, Phase expected, long now, Driver<L> driver) {
        checkThread();
        if (callbackGeneration != generation || phase != expected || canRelease() || phase == Phase.RECOVERY_REQUIRED) return phase;
        if (phase != Phase.PREPARED && phase != Phase.BETWEEN_MOVES && phase != Phase.ROLLBACK_PENDING) return phase;
        if (phase == Phase.PREPARED && (cancelled || now >= deadline)) return phase = Phase.RESTORED;
        if (cancelled || now >= deadline) phase = Phase.ROLLBACK_PENDING;
        try {
            switch (phase) {
                case PREPARED -> {
                    if (!driver.atOrigins(main, slot)) return phase = Phase.RESTORED;
                    phase = Phase.PROMOTING;
                    driver.promote(slot);
                    phase = Phase.BETWEEN_MOVES;
                }
                case BETWEEN_MOVES -> {
                    if (!driver.readyForDemotion(main, slot)) return phase = Phase.ROLLBACK_PENDING;
                    phase = Phase.DEMOTING;
                    driver.demoteAndFit(main, slot);
                    phase = Phase.VERIFYING;
                    phase = driver.committed(main, slot) ? Phase.COMMITTED : Phase.ROLLBACK_PENDING;
                }
                case ROLLBACK_PENDING -> {
                    phase = Phase.ROLLING_BACK;
                    phase = driver.restoreAndVerify(main, slot) ? Phase.RESTORED : Phase.RECOVERY_REQUIRED;
                }
                default -> throw new IllegalStateException("Unexpected phase");
            }
        } catch (RuntimeException failure) {
            phase = phase == Phase.ROLLING_BACK ? Phase.RECOVERY_REQUIRED : Phase.ROLLBACK_PENDING;
        }
        return phase;
    }
}
