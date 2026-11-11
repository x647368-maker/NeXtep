package io.github.x647368maker.atomicworkbench.display

import org.junit.Assert.*
import org.junit.Test

class SlotDismissDecisionPolicyTest {
    @Test fun asynchronousWctMaySettleLaterWithoutSecondMove() {
        assertTrue(SlotDismissDecisionPolicy.nativeFallbackRequired(false, 14))
        assertFalse(SlotDismissDecisionPolicy.nativeFallbackRequired(true, 0))
        assertFalse(SlotDismissDecisionPolicy.nativeFallbackRequired(false, 0))
    }

    @Test fun missingObservationNeverFakesVacantSlot() {
        assertTrue(SlotDismissDecisionPolicy.nativeFallbackRequired(false, null))
        assertFalse(SlotDismissDecisionPolicy.mayPublishVacantSlot(null))
        assertFalse(SlotDismissDecisionPolicy.mayPublishVacantSlot(14))
        assertTrue(SlotDismissDecisionPolicy.mayPublishVacantSlot(0))
    }

    @Test fun onlyRestoreThePreviousRealMainTaskAfterBinderFallback() {
        assertTrue(SlotDismissDecisionPolicy.shouldRestoreMain(true, 123, 456, 0))
        assertFalse(SlotDismissDecisionPolicy.shouldRestoreMain(false, 123, 456, 0))
        assertFalse(SlotDismissDecisionPolicy.shouldRestoreMain(true, 123, 123, 0))
        assertFalse(SlotDismissDecisionPolicy.shouldRestoreMain(true, 123, 456, 3))
        assertFalse(SlotDismissDecisionPolicy.shouldRestoreMain(true, null, 456, 0))
    }
}
