package io.github.x647368maker.atomicworkbench.config
import org.junit.Assert.*
import org.junit.Test
class UpdateCheckScheduleTest {
    @Test fun firstOptInChecksNowButDisabledAndConcurrentDoNot() {
        assertTrue(UpdateCheckSchedule.due(true,false,100,0,true))
        assertFalse(UpdateCheckSchedule.due(false,false,100,0,true))
        assertFalse(UpdateCheckSchedule.due(true,true,100,0,true))
    }
    @Test fun successAndFailureHaveDifferentRetryWindows() {
        val hour=3_600_000L
        assertFalse(UpdateCheckSchedule.due(true,false,10+hour,10,true))
        assertTrue(UpdateCheckSchedule.due(true,false,10+hour,10,false))
        assertFalse(UpdateCheckSchedule.due(true,false,10+hour-1,10,false))
        assertTrue(UpdateCheckSchedule.due(true,false,10+12*hour,10,true))
    }
    @Test fun clockRollbackDoesNotDisableChecksIndefinitely() {
        assertTrue(UpdateCheckSchedule.due(true,false,100,1000,true))
    }
}
