package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageCorePoliciesTest {
    @Test fun fixedPinsAreUniqueLimitedAndOrdered() {
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), StageQuickPolicy.normalize(listOf("a", "", "a", "b", "c", "d", "e", "f", "g")))
    }
    @Test fun unpinRetainsRemainingOrder() {
        assertEquals(listOf("a", "c"), StageQuickPolicy.toggle(listOf("a", "b", "c"), "b"))
    }
    @Test fun pinDoesNotEvictExistingSix() {
        assertEquals((1..6).map { "$it" }, StageQuickPolicy.toggle((1..6).map { "$it" }, "7"))
    }
    @Test fun pinSortingClampsAtBothEnds() {
        assertEquals(listOf("b", "a", "c"), StageQuickPolicy.move(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "c", "b"), StageQuickPolicy.move(listOf("a", "b", "c"), "b", 9))
        assertEquals(listOf("a"), StageQuickPolicy.move(listOf("a"), "missing", 1))
    }
    @Test fun autoShowsFiveRealAvailableApps() {
        assertEquals(listOf("a", "b", "c", "d", "e"), StageQuickPolicy.select(false, listOf("f"), listOf("a", "b", "c", "d", "e", "f"), ('a'..'f').map { "$it" }.toSet()))
    }
    @Test fun fixedModeFiltersUninstalledWithoutChangingStoredPins() {
        val pins = listOf("gone", "b", "a")
        assertEquals(listOf("b", "a"), StageQuickPolicy.select(true, pins, listOf("a"), setOf("a", "b")))
        assertEquals("gone", pins.first())
    }
    @Test fun quickStateSurvivesCollapseProcessRestartAndReentry() {
        val state = StageQuickState(listOf("10:wechat", "0:browser"), true, true, true)
        assertEquals(state, StageQuickState.decode(state.encode()))
        val collapsed = state.copy(expanded = false)
        assertEquals(collapsed, StageQuickState.decode(collapsed.encode()))
        assertEquals(state.pins, StageQuickState.decode(collapsed.encode()).pins)
    }
    @Test fun malformedQuickStateHasSafeHiddenDefault() {
        assertEquals(StageQuickState(), StageQuickState.decode(null))
        assertEquals(StageQuickState(), StageQuickState.decode("2|bad|true|true|x"))
    }
    @Test fun taskReuseIsUserScopedAndDoesNotInventTask() {
        val tasks = mapOf("0:com.tencent.mm" to 40, "10:com.tencent.mm" to 80)
        assertEquals(40, StageQuickPolicy.existing("0:com.tencent.mm", tasks))
        assertEquals(80, StageQuickPolicy.existing("10:com.tencent.mm", tasks))
        assertNull(StageQuickPolicy.existing("0:new", tasks))
    }
    @Test fun autoRequiresTrustedSignalAndDebounces() {
        val p = StageCinemaPolicy()
        assertNull(p.update(null, setOf(1), setOf(1), 0))
        assertNull(p.update(1, setOf(1), setOf(1), 100))
        assertNull(p.update(1, setOf(1), setOf(1), 799))
        assertEquals(1, p.update(1, setOf(1), setOf(1), 800))
    }
    @Test fun transientDirectionDoesNotSwitchLayout() {
        val p = StageCinemaPolicy()
        p.update(1, setOf(1, 2), setOf(1, 2), 0)
        p.update(2, setOf(1, 2), setOf(1, 2), 500)
        assertNull(p.update(2, setOf(1, 2), setOf(1, 2), 900))
        assertEquals(2, p.update(2, setOf(1, 2), setOf(1, 2), 1200))
    }
    @Test fun manualExitBlocksSamePlaybackUntilSessionEnds() {
        val p = StageCinemaPolicy(); p.enter(7); p.leave()
        assertNull(p.update(7, setOf(7), setOf(7), 0))
        assertNull(p.update(7, setOf(7), setOf(7), 1000))
        p.update(null, setOf(7), emptySet(), 1500)
        p.update(7, setOf(7), setOf(7), 2000)
        assertEquals(7, p.update(7, setOf(7), setOf(7), 2700))
    }
    @Test fun manualSelectionHasPriorityOverAutoAndPause() {
        val p = StageCinemaPolicy(); p.enter(3)
        assertEquals(3, p.update(2, setOf(2, 3), setOf(2), 1000))
        assertNull(p.update(2, setOf(2), setOf(2), 2000))
    }
    @Test fun autoPauseAndBufferDoNotDestroyLayout() {
        val p = StageCinemaPolicy(); p.update(1, setOf(1), setOf(1), 0)
        assertEquals(1, p.update(1, setOf(1), setOf(1), 700))
        assertEquals(1, p.update(null, setOf(1), emptySet(), 1000, present = setOf(1)))
        assertNull(p.update(null, setOf(1), emptySet(), 2000))
    }
    @Test fun offAlwaysRestoresOrdinaryLayout() {
        val p = StageCinemaPolicy(); p.enter(1); p.mode = StageCinemaPolicy.Mode.OFF
        assertNull(p.update(1, setOf(1), setOf(1), 1000))
    }
    @Test fun manualModeNeverAutomaticallyEnters() {
        val p = StageCinemaPolicy(); p.mode = StageCinemaPolicy.Mode.MANUAL
        p.update(1, setOf(1), setOf(1), 0)
        assertNull(p.update(1, setOf(1), setOf(1), 2000))
    }
    @Test fun lockRetainsPolicyButExitResetsSessionIdentity() {
        val p = StageCinemaPolicy(); p.enter(9)
        // No observations occur during lock; retained tasks remain owned by the engine.
        assertEquals(9, p.task)
        assertEquals(9, p.update(null, setOf(9), emptySet(), 2000))
        p.reset(); assertNull(p.task)
    }
    @Test fun migrationRejectsChangedTaskUserWrongAppOrBusyState() {
        assertTrue(StageTaskMigrationPolicy.eligible("com.tencent.mm", 10, 10, 5, 5, true, true))
        assertFalse(StageTaskMigrationPolicy.eligible("com.tencent.mm", 10, 0, 5, 5, true, true))
        assertFalse(StageTaskMigrationPolicy.eligible("com.tencent.mm", 10, 10, 6, 5, true, true))
        assertFalse(StageTaskMigrationPolicy.eligible("other", 10, 10, 5, 5, true, true))
        assertFalse(StageTaskMigrationPolicy.eligible("com.tencent.mm", 10, 10, 5, 5, false, true))
        assertFalse(StageTaskMigrationPolicy.eligible("com.tencent.mm", 10, 10, 5, 5, true, false))
    }
    @Test fun migrationRollbackRestoresTaskBeforePresentation() {
        val steps = mutableListOf<String>()
        assertTrue(StageTaskMigrationPolicy.rollback({ steps += "original task/display/bounds" }, { steps += "presentation" }).isEmpty())
        assertEquals(listOf("original task/display/bounds", "presentation"), steps)
    }
    @Test fun rollbackStillAttemptsPresentationAfterRestoreFailureAndReportsBoth() {
        var attempted = false
        val errors = StageTaskMigrationPolicy.rollback({ error("restore failed") }, { attempted = true; error("fit failed") })
        assertTrue(attempted); assertEquals(2, errors.size)
    }
}
