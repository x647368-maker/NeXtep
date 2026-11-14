package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageIssueReportPolicyTest {
    @Test fun onlySupportedDurationsAndDefault() {
        assertEquals(listOf(300000L,900000L,1800000L),StageIssueReportPolicy.DURATION_OPTIONS_MS)
        assertEquals(900000L,StageIssueReportPolicy.selectedDuration(1L))
        assertEquals(300000L,StageIssueReportPolicy.selectedDuration(300000L))
        assertEquals(1800000L,StageIssueReportPolicy.selectedDuration(1800000L))
    }
    @Test fun expiryUsesMonotonicClockAndHandlesRebootLikeReset() {
        val start=123456L
        assertFalse(StageIssueReportPolicy.isDue(start,start+299999,300000))
        assertTrue(StageIssueReportPolicy.isDue(start,start+300000,300000))
        assertTrue(StageIssueReportPolicy.isDue(start,start-1,300000))
        assertEquals(1L,StageIssueReportPolicy.remainingMs(start,start+299999,300000))
        assertEquals(0L,StageIssueReportPolicy.remainingMs(start,start+300000,300000))
    }
    @Test fun markerAndDescriptionBoundaries() {
        val start=100L
        assertTrue(StageIssueReportPolicy.canMark(start,start,0))
        assertTrue(StageIssueReportPolicy.canMark(start,start+StageIssueReportPolicy.MAX_DURATION_MS,11))
        assertFalse(StageIssueReportPolicy.canMark(start,start+StageIssueReportPolicy.MAX_DURATION_MS+1,0))
        assertFalse(StageIssueReportPolicy.canMark(start,start-1,0))
        assertFalse(StageIssueReportPolicy.canMark(start,start,12))
        val text=StageIssueReportPolicy.note("hello\u0000" + "x".repeat(1000))
        assertFalse(text.contains('\u0000'))
        assertEquals(StageIssueReportPolicy.MAX_DESCRIPTION_CHARS,text.length)
        assertEquals("用户未填写",StageIssueReportPolicy.note(""))
    }
    @Test fun faultReportContainsFullTraceAndLastOutcome() {
        val diag="测量代码：0.9.46-dev/61\n最近5次动画路径：\n" +
            (1..5).joinToString("\n") { "#$it tap_swap " + "phase@10ms > ".repeat(200) + "end:timeout@480ms" }
        val section=StageIssueReportPolicy.exchangeSection(11,2L,11,diag)
        assertEquals(5L,StageIssueReportPolicy.lastSwapId(diag))
        assertTrue(section.contains("起始快照最后交换编号：2"))
        assertTrue(section.contains(diag))
        assertTrue(section.contains("end:timeout@480ms"))
        assertTrue(section.length+12_000+StageIssueReportPolicy.MAX_DESCRIPTION_CHARS < StageIssueReportPolicy.MAX_REPORT_CHARS)
    }

    @Test fun reportDistinguishesUnavailableOrReplacedSystemUi() {
        assertTrue(StageIssueReportPolicy.exchangeSection(11,null,null,null).contains("不可用"))
        assertTrue(StageIssueReportPolicy.exchangeSection(11,5L,12,"最近5次动画路径：\n#1 tap_swap").contains("进程号已变化"))
        assertTrue(StageIssueReportPolicy.exchangeSection(11,null,11,null).contains("未提供交换记录"))
        assertNull(StageIssueReportPolicy.lastSwapId("测量代码：0.9.46-dev/61\n#99 unrelated"))
        assertTrue(StageIssueReportPolicy.exchangeSection(11,null,11,"x".repeat(24_001)).contains("未完整导出"))
    }

    @Test fun negativeAndResetElapsedUnusable() {
        assertNull(StageIssueReportPolicy.elapsedMs(-1,10))
        assertNull(StageIssueReportPolicy.elapsedMs(10,3))
        assertEquals(30L,StageIssueReportPolicy.elapsedMs(10,40))
    }
}
