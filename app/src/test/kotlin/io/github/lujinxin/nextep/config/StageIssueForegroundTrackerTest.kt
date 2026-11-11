package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageIssueForegroundTrackerTest {
    @Test fun oldHomePageStoppingMustNotInventBackground() {
        val home = Any()
        val diagnostic = Any()
        val tracker = StageIssueForegroundTracker()

        // Listener registers after diagnostic Activity.onStart/onResume.
        tracker.seed(diagnostic)
        assertTrue(tracker.isVisible())
        assertFalse(tracker.stop(home)) // Home was started before registration.
        assertTrue(tracker.isVisible())
        assertFalse(tracker.start(diagnostic)) // Duplicate event is ignored.
        assertTrue(tracker.stop(diagnostic))
        assertFalse(tracker.isVisible())
    }

    @Test fun handoffBetweenKnownPagesKeepsForeground() {
        val tracker = StageIssueForegroundTracker()
        val diagnostic = Any()
        val home = Any()
        tracker.seed(diagnostic)
        assertFalse(tracker.start(home))
        assertFalse(tracker.stop(diagnostic))
        assertTrue(tracker.isVisible())
        assertTrue(tracker.stop(home))
        assertFalse(tracker.isVisible())
    }

    @Test fun duplicateStopNeverInventsExtraTransitions() {
        val tracker = StageIssueForegroundTracker()
        val page = Any()
        assertTrue(tracker.start(page))
        assertFalse(tracker.start(page))
        assertTrue(tracker.stop(page))
        assertFalse(tracker.stop(page))
        assertFalse(tracker.stop(Any()))
        assertFalse(tracker.isVisible())
        tracker.seed(page)
        tracker.reset()
        assertFalse(tracker.isVisible())
    }

    @Test fun usesInstanceIdentityNotObjectEquality() {
        data class EqualPage(val name: String)
        val a = EqualPage("same")
        val b = EqualPage("same")
        val tracker = StageIssueForegroundTracker()
        tracker.seed(a)
        assertFalse(tracker.start(b))
        assertFalse(tracker.stop(a))
        assertTrue(tracker.stop(b))
    }
}
