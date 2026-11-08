package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class WorkgroupCoverGateTest {
    @Test fun renderedCallbackAndFallbackCanStartNativeMovesOnlyOnce() {
        val gate = WorkgroupCoverGate()
        val token = gate.begin()
        assertTrue(gate.claimStart(token))
        assertFalse(gate.claimStart(token))
    }

    @Test fun cancelOrNewGroupRejectsTheOldRenderCallback() {
        val gate = WorkgroupCoverGate()
        val previous = gate.begin()
        gate.cancel()
        assertFalse(gate.claimStart(previous))
        val current = gate.begin()
        assertFalse(gate.claimStart(previous))
        assertTrue(gate.claimStart(current))
    }

    @Test fun aFreshBufferCannotRevealAnUnverifiedNativeGroup() {
        val gate = WorkgroupCoverGate()
        val token = gate.begin()
        gate.claimStart(token)
        assertEquals(WorkgroupCoverGate.Reveal.WAIT, gate.reveal(token, 99999, true))
        gate.verified(token, 100)
        assertEquals(WorkgroupCoverGate.Reveal.WAIT, gate.reveal(token, 219, true))
        assertEquals(WorkgroupCoverGate.Reveal.READY, gate.reveal(token, 220, true))
    }

    @Test fun lateBuffersHaveABoundedWaitAndCannotReviveAfterCancellation() {
        val gate = WorkgroupCoverGate()
        val token = gate.begin()
        gate.claimStart(token)
        gate.verified(token, 100)
        assertEquals(WorkgroupCoverGate.Reveal.WAIT, gate.reveal(token, 2099, false))
        assertEquals(WorkgroupCoverGate.Reveal.LATE, gate.reveal(token, 2100, false))
        gate.cancel()
        assertEquals(WorkgroupCoverGate.Reveal.WAIT, gate.reveal(token, 2200, true))
    }

    @Test fun directReorderRequiresTheSameLiveIdentitiesAndSupportsEmptySlots() {
        val a = StageTaskIdentity(10, "a", 0)
        val b = StageTaskIdentity(11, "b", 0)
        val c = StageTaskIdentity(12, "c", 0)
        assertTrue(StageGroupSwitchPolicy.canReorder(listOf(a,b,c,null,null), listOf(b,null,a,c,null)))
        assertFalse(StageGroupSwitchPolicy.canReorder(listOf(a,b,c,null,null), listOf(b,null,a,a,null)))
        assertFalse(StageGroupSwitchPolicy.canReorder(listOf(a,b,c,null,null), listOf(b,null,a,StageTaskIdentity(12,"c",999),null)))
        assertFalse(StageGroupSwitchPolicy.canReorder(listOf(a,b,c,null,null), listOf(b,null,a,StageTaskIdentity(13,"new",0),null)))
        assertFalse(StageGroupSwitchPolicy.canReorder(listOf(a,b,c,null,null), listOf(null,b,a,c,null)))
    }
    @Test fun reorderPlansReachEveryValidArrangementWithoutParkingOrLosingTasks() {
        val all = (1..5).map { StageTaskIdentity(it, "app$it", 0) }
        fun permutations(values: List<StageTaskIdentity?>): Set<List<StageTaskIdentity?>> {
            if (values.isEmpty()) return setOf(emptyList())
            return values.indices.flatMap { i ->
                permutations(values.filterIndexed { j, _ -> j != i }).map { listOf(values[i]) + it }
            }.toSet()
        }
        for (current in listOf(all, listOf(all[0], all[1], all[2], null, null))) {
            for (target in permutations(current).filter { it[0] != null }) {
                val moves = checkNotNull(StageGroupSwitchPolicy.planReorder(current, target))
                val simulated = current.toMutableList()
                moves.forEach { move ->
                    assertNotNull(simulated[move.from])
                    val saved = simulated[move.from]
                    simulated[move.from] = simulated[move.to]
                    simulated[move.to] = saved
                }
                assertEquals(target, simulated)
                assertTrue(moves.size <= 4)
            }
        }
    }
}
