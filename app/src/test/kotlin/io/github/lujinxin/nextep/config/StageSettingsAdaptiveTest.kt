package io.github.x647368maker.atomicworkbench.config
import org.junit.Assert.*
import org.junit.Test

class StageSettingsAdaptiveTest {
    @Test fun exactHomeArchitectureHasElevenLiveDestinations() {
        assertEquals(listOf("工作台","故障诊断","支持与服务"),StageHomeLayout.sections.map{it.title})
        assertEquals(listOf("窗口布局","影音与快捷栏","工作组","启动手势",
            "快捷开关","一键综合诊断","专项测试与报告","软件更新",
            "建议与反馈","打赏作者","关于与使用帮助"),
            StageHomeLayout.sections.flatMap{it.items}.map{it.name})
        assertEquals(listOf("windows","core","groups","gestures","tile","issue_all","issue_specialties",
            "update","feedback","donate","about"),
            StageHomeLayout.sections.flatMap{it.items}.map{it.page})
    }
    @Test fun phoneAndFoldedColumnsUseAvailableWidthExpandedIsCapped() {
        assertEquals(1264,StageHomeLayout.contentWidth(1264,3.2f))
        assertEquals(1140,StageHomeLayout.contentWidth(1140,3f))
        assertEquals((680*2.625f).toInt(),StageHomeLayout.contentWidth(2248,2.625f))
        assertTrue(StageHomeLayout.contentWidth(2480,2.625f)<2480)
    }
    @Test fun twoPaneUsesMeasuredWidthAndShapeNotModel() {
        assertFalse(StageHomeLayout.useTwoPane(1264,2780,3.2f))
        assertFalse(StageHomeLayout.useTwoPane(1140,2520,3f))
        assertTrue(StageHomeLayout.useTwoPane(2248,2268,2.625f))
        assertTrue(StageHomeLayout.useTwoPane(2480,2268,3f))
        assertFalse(StageHomeLayout.useTwoPane(1600,2560,2.5f))
        assertTrue(StageHomeLayout.useTwoPane(2560,1600,2.5f))
        assertFalse(StageHomeLayout.useTwoPane(1500,1500,3f))
        assertEquals(520,StageHomeLayout.navigationWidth(1500,2.5f))
        assertEquals(900,StageHomeLayout.navigationWidth(4000,3f))
    }
    @Test fun rowsAndHeadersRemainTouchableAndSupportIsOptIn() {
        assertTrue(StageHomeLayout.ROW_DP in 56..64)
        assertTrue(StageHomeLayout.HEADER_DP>=48)
        assertEquals(3,StageHomeLayout.sections.size)
        assertEquals(1,StageHomeLayout.sections.flatMap{it.items}.count{it.page=="feedback"})
        assertEquals(1,StageHomeLayout.sections.flatMap{it.items}.count{it.page=="donate"})
    }
}
