package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.assertEquals
import org.junit.Test

class StageIssueStateLabelsTest {
    @Test fun screenAndLockAreSeparateFacts() {
        assertEquals("息屏", StageIssueStateLabels.screen(false, true, 1))
        assertEquals("亮屏但不可交互（状态切换中）", StageIssueStateLabels.screen(false, false, 2))
        assertEquals("息屏显示或低功耗显示",StageIssueStateLabels.screen(null,null,3))
        assertEquals("息屏显示或低功耗显示",StageIssueStateLabels.screen(false,true,4))
        assertEquals("不可交互（物理屏状态未知）",StageIssueStateLabels.screen(false,null,null))
        assertEquals("亮屏但锁屏",StageIssueStateLabels.screen(true,true,2))
        assertEquals("亮屏已解锁",StageIssueStateLabels.screen(true,false,2))
        assertEquals("状态未知",StageIssueStateLabels.screen(null,null,null))
    }
    @Test fun workbenchEnabledAndActualForegroundAreNotConfused() {
        assertEquals("工作台状态未知",StageIssueStateLabels.workspace(null,false))
        assertEquals("工作台未开启",StageIssueStateLabels.workspace(false,false))
        assertEquals("工作台已开启、处于挂起状态",StageIssueStateLabels.workspace(true,true))
        assertEquals("工作台已开启、未挂起（不代表每个小窗都在前台）",
            StageIssueStateLabels.workspace(true,false))
    }
}
