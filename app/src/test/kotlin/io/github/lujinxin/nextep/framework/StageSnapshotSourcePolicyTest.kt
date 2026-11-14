package io.github.x647368maker.atomicworkbench.framework

import org.junit.Assert.*
import org.junit.Test

class StageSnapshotSourcePolicyTest {
    @Test fun settledDestinationAllowsRepeatedTapWithoutClearingReservation() {
        assertTrue(StageSnapshotSourcePolicy.reservationAllowsRead(0,true,0))
        assertTrue(StageSnapshotSourcePolicy.reservationAllowsRead(5,true,5))
        assertFalse(StageSnapshotSourcePolicy.reservationAllowsRead(0,true,5))
        assertFalse(StageSnapshotSourcePolicy.reservationAllowsRead(5,true,0))
        assertFalse(StageSnapshotSourcePolicy.reservationAllowsRead(0,true,null))
        assertTrue(StageSnapshotSourcePolicy.reservationAllowsRead(5,false,null))
    }
    @Test fun fitsNativeLocalExtentWithoutStretchOrOutOfBounds() {
        assertTrue(StageSnapshotSourcePolicy.localExtentMatches(1272,2772,1272,2772))
        assertTrue(StageSnapshotSourcePolicy.localExtentMatches(2560,1600,2400,1600))
        assertFalse(StageSnapshotSourcePolicy.localExtentMatches(1272,2772,2772,1272))
        assertFalse(StageSnapshotSourcePolicy.localExtentMatches(1272,2772,0,2772))
    }
}
