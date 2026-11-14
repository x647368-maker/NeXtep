package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageAckOutputBaselineTest {
    @Test fun missingOutputMarkStillUsesAckTimeSequenceAsStrictBaseline(){
        // When OutputFrameMark is temporarily unavailable at task ACK, the
        // controller snapshots contentFrameSequence at that exact ACK.
        val sequenceAtAck=73L
        assertFalse(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(
            sequenceAtAck,sequenceAtAck))
        assertFalse(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(
            sequenceAtAck,sequenceAtAck-1))
        assertTrue(StageConcurrentNativeTrialPolicy.freshFrameAfterTaskAck(
            sequenceAtAck,sequenceAtAck+1))
    }
}
