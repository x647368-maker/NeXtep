package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

/** Regression coverage: an empty/unrelated update index must not suppress GitHub API fallback. */
class UpdateFeedAuthorityTest {
    @Test fun emptyFeedNeverClaimsNoUpdate() {
        assertFalse(UpdateFeedAuthority.validSize(0))
        assertFalse(UpdateFeedAuthority.hasApplicableRecords(0, 0))
    }

    @Test fun invalidCountsAreNotTrusted() {
        assertFalse(UpdateFeedAuthority.validSize(-1))
        assertFalse(UpdateFeedAuthority.validSize(41))
        assertFalse(UpdateFeedAuthority.hasApplicableRecords(1, 2))
        assertFalse(UpdateFeedAuthority.hasApplicableRecords(1, -1))
    }

    @Test fun stableChannelDoesNotTrustDevOnlyFeed() {
        // One development release exists, but zero published releases apply to STABLE.
        assertTrue(UpdateFeedAuthority.validSize(1))
        assertFalse(UpdateFeedAuthority.hasApplicableRecords(1, 0))
    }

    @Test fun populatedChannelRelevantFeedCanAnswerNoUpdate() {
        // The caller validates each entry's package, cert, tag and download before counting it.
        assertTrue(UpdateFeedAuthority.hasApplicableRecords(1, 1))
        assertTrue(UpdateFeedAuthority.hasApplicableRecords(2, 1))
        assertTrue(UpdateFeedAuthority.hasApplicableRecords(40, 40))
    }
}
