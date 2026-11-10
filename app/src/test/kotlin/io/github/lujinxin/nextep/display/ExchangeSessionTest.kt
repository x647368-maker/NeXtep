package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque

class ExchangeSessionTest {
    private class Fixture {
        val queue = ArrayDeque<() -> Unit>()
        var clock = 0L
        var mainDisplay = 0
        var slotDisplay = 7
        var promotions = 0
        var completions = 0
        var result: Boolean? = null
        var failPromotionAfterMove = false
        var failRestore = false
        var disappear = false
        var failCompletion = false
        val session = ExchangeSession(
            3, 100L,
            ExchangeSession.Origin(11, 0, "main token", 0, "main layout"),
            ExchangeSession.Origin(22, 0, "slot token", 7, "slot layout"),
            { clock }, { queue.add(it) },
            object : ExchangeSession.Driver {
                override fun promote() {
                    promotions++
                    slotDisplay = 0
                    if (failPromotionAfterMove) error("Binder moved before observation failed")
                }
                override fun demoteAndVerify() {
                    check(!disappear)
                    check(mainDisplay == 0 && slotDisplay == 0)
                    mainDisplay = 7
                }
                override fun restoreAndVerify() {
                    check(!failRestore && !disappear)
                    if (promotions == 0) {
                        // Pre-mutation abort must verify, never silently reset ownership.
                        check(mainDisplay == 0 && slotDisplay == 7)
                        return
                    }
                    mainDisplay = 0
                    slotDisplay = 7
                }
            },
            { completions++; result = it; if (failCompletion) error("UI completion failed") },
        )
        fun tick() = queue.removeFirst().invoke()
        fun drain() { repeat(8) { if (queue.isNotEmpty()) tick() }; check(queue.isEmpty()) }
    }

    @Test fun yieldsBetweenRealMutationsAndCompletesOnce() {
        val f = Fixture()
        f.session.start()
        assertEquals(0, f.promotions)
        f.tick()
        assertEquals(ExchangeSession.Phase.DEMOTE_PENDING, f.session.phase)
        assertEquals(0, f.mainDisplay)
        assertFalse(f.session.settled)
        f.tick()
        assertEquals(true, f.result)
        assertEquals(1, f.completions)
        assertEquals(7, f.mainDisplay)
    }

    @Test fun duplicateStartCannotPromoteTwice() {
        val f = Fixture()
        f.session.start(); f.session.start(); f.drain()
        assertEquals(1, f.promotions)
        assertEquals(1, f.completions)
    }

    @Test fun lockCancellationAfterPromotionRestoresBeforeRelease() {
        val f = Fixture()
        f.session.start(); f.tick()
        f.session.cancel()
        assertFalse(f.session.settled)
        f.drain()
        assertEquals(false, f.result)
        assertEquals(7, f.slotDisplay)
        assertEquals(0, f.mainDisplay)
    }

    @Test fun cancellationBeforeMutationNeedsNoRollback() {
        val f = Fixture()
        f.session.start(); f.session.cancel(); f.drain()
        assertEquals(0, f.promotions)
        assertEquals(false, f.result)
    }

    @Test fun externalMoveBeforeCancellationCannotBeFalselyRestored() {
        val f = Fixture()
        f.session.start()
        f.mainDisplay = 5 // System move occurred independently of this session
        f.session.cancel()
        f.drain()
        assertEquals(ExchangeSession.Phase.RECOVERY_REQUIRED, f.session.phase)
        assertFalse(f.session.settled)
        assertEquals(0, f.completions)
        assertEquals(0, f.promotions)
        f.mainDisplay = 0
        f.session.cancel()
        f.drain()
        assertEquals(ExchangeSession.Phase.RESTORED, f.session.phase)
        assertEquals(false, f.result)
        assertEquals(1, f.completions)
    }

    @Test fun deadlineBeforeMutationRequiresVerifiedOrigins() {
        val f = Fixture()
        f.session.start()
        f.clock = 101
        f.slotDisplay = 0
        f.drain()
        assertEquals(ExchangeSession.Phase.RECOVERY_REQUIRED, f.session.phase)
        assertNull(f.result)
    }

    @Test fun timeoutAfterPromotionRestoresIdentity() {
        val f = Fixture()
        f.session.start(); f.tick(); f.clock = 101; f.drain()
        assertEquals(ExchangeSession.Phase.RESTORED, f.session.phase)
        assertEquals(7, f.slotDisplay)
    }

    @Test fun binderThenThrowStillRollsBack() {
        val f = Fixture()
        f.failPromotionAfterMove = true
        f.session.start(); f.drain()
        assertEquals(7, f.slotDisplay)
        assertEquals(false, f.result)
    }

    @Test fun missingTaskProtectsReservationAndDisplays() {
        val f = Fixture()
        f.session.start(); f.tick(); f.disappear = true; f.drain()
        assertEquals(ExchangeSession.Phase.RECOVERY_REQUIRED, f.session.phase)
        assertFalse(f.session.settled)
        assertNull(f.result)
    }

    @Test fun failedRollbackCanRetryWithoutAnotherPromotion() {
        val f = Fixture()
        f.session.start(); f.tick()
        f.failRestore = true; f.session.cancel(); f.drain()
        assertFalse(f.session.settled)
        f.failRestore = false; f.session.cancel(); f.drain()
        assertEquals(1, f.promotions)
        assertEquals(1, f.completions)
        assertEquals(false, f.result)
    }

    @Test fun completionFailureDoesNotCrashOrRepeatMigrations() {
        val f = Fixture()
        f.failCompletion = true
        f.session.start(); f.drain()
        assertEquals(ExchangeSession.Phase.COMMITTED, f.session.phase)
        assertEquals(1, f.completions)
        assertEquals(1, f.promotions)
        assertEquals(7, f.mainDisplay)
        assertTrue(f.session.failure != null)
        f.session.start(); f.session.cancel(); f.drain()
        assertEquals(1, f.completions)
    }

    @Test fun completionCannotBeNotifiedTwice() {
        val f = Fixture()
        f.session.start(); f.drain()
        f.session.cancel(); f.session.start(); f.drain()
        assertEquals(1, f.completions)
    }
}
