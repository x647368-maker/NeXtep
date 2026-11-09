package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class SlotAvailabilityPolicyTest {
    private fun empty()=SlotAvailabilityPolicy.Fact(SlotAvailabilityPolicy.State.EMPTY,null,null,null,emptyList(),false,false)
    private fun ready(i: Int)=SlotAvailabilityPolicy.Fact(SlotAvailabilityPolicy.State.READY,10+i,null,null,emptyList(),true,false)
    private fun occupied(i: Int)=SlotAvailabilityPolicy.Fact(SlotAvailabilityPolicy.State.OCCUPIED,10+i,100+i,10+i,listOf(100+i),true,true)
    @Test fun coldEmptyIsRecoverNotFull() { assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(List(4){empty()})) }
    @Test fun failedInitializationIsNotCapacity() {
        assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(List(4){empty().copy(state=SlotAvailabilityPolicy.State.FAILED)}))
    }
    @Test fun addZeroThroughFourThenFifthOnlyVerifiedFull() {
        val state=MutableList(4){ready(it)}
        for(i in 0..3) {
            assertEquals(SlotAvailabilityPolicy.Decision.Available(i),SlotAvailabilityPolicy.decide(state))
            state[i]=occupied(i)
        }
        assertEquals(SlotAvailabilityPolicy.Decision.Full,SlotAvailabilityPolicy.decide(state))
    }
    @Test fun closeMiddleThenAddUsesSameVacatedDisplay() {
        val state=MutableList(4){occupied(it)};state[1]=ready(1)
        assertEquals(SlotAvailabilityPolicy.Decision.Available(1),SlotAvailabilityPolicy.decide(state))
        state[1]=occupied(1).copy(task=900,tasksOnDisplay=listOf(900))
        assertEquals(SlotAvailabilityPolicy.Decision.Full,SlotAvailabilityPolicy.decide(state))
    }
    @Test fun occupiedButHiddenNeverReportsFull() {
        assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(List(4){occupied(it).copy(visible=false)}))
    }
    @Test fun surfaceLossRequiresRepairWithoutFreeingTheTask() {
        val facts=MutableList(4){occupied(it)};facts[2]=facts[2].copy(surface=false)
        assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(facts));assertEquals(102,facts[2].task)
    }
    @Test fun restoredLabelsAfterRebootAreNotRealTasks() {
        assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(List(4){occupied(it).copy(actualDisplay=null,tasksOnDisplay=emptyList())}))
    }
    @Test fun readyLabelCannotOverwriteAnActualTaskOnDisplay() {
        val facts=List(4){ready(it).copy(tasksOnDisplay=listOf(200+it))}
        assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(facts))
    }
    @Test fun duplicateTaskCannotCountAsFour() {
        assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(List(4){occupied(it).copy(task=100,tasksOnDisplay=listOf(100))}))
    }
    @Test fun wrongDisplayIdentityNeverEstablishesCapacity() {
        assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(List(4){occupied(it).copy(actualDisplay=0)}))
    }
    @Test fun lostProducerDoesNotReserveAReadySlot() {
        assertEquals(SlotAvailabilityPolicy.Decision.Recover,SlotAvailabilityPolicy.decide(List(4){ready(it).copy(surface=false)}))
    }
}
