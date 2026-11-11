package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageIssueReportPolicyTest {
    @Test fun sessionRecordsOnlyWithinThirtyMinutes() {
        val start=100L
        assertTrue(StageIssueReportPolicy.canMark(start,start,0))
        assertTrue(StageIssueReportPolicy.canMark(start,start+StageIssueReportPolicy.MAX_DURATION_MS,11))
        assertFalse(StageIssueReportPolicy.canMark(start,start+StageIssueReportPolicy.MAX_DURATION_MS+1,0))
        assertFalse(StageIssueReportPolicy.canMark(start,start-1,0))
        assertFalse(StageIssueReportPolicy.canMark(start,start,12))
    }
    @Test fun descriptionIsBoundedAndExcludesControlCharacters() {
        val input="hello\u0000" + "x".repeat(1000)
        val safe=StageIssueReportPolicy.note(input)
        assertFalse(safe.contains('\u0000'))
        assertEquals(StageIssueReportPolicy.MAX_DESCRIPTION_CHARS,safe.length)
        assertEquals("用户未填写",StageIssueReportPolicy.note(""))
    }
    @Test fun negativeAndRebootLikeElapsedAreUnknown() {
        assertNull(StageIssueReportPolicy.elapsedMs(-1,10))
        assertNull(StageIssueReportPolicy.elapsedMs(10,3))
        assertEquals(30L,StageIssueReportPolicy.elapsedMs(10,40))
    }
}
