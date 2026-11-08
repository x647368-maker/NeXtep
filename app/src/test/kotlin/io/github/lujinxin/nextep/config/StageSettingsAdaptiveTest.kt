package io.github.x647368maker.atomicworkbench.config
import org.junit.Assert.*
import org.junit.Test

class StageSettingsAdaptiveTest {
    @Test fun exactHomeArchitectureHasNineLiveDestinations() {
        assertEquals(listOf("工作台","系统","支持"),StageHomeLayout.sections.map{it.title})
        assertEquals(listOf("窗口布局","影音与快捷栏","工作组","启动手势","快捷开关","运行诊断","使用与退出","建议与反馈","关于"),StageHomeLayout.sections.flatMap{it.items}.map{it.name})
        assertEquals(listOf("windows","core","groups","gestures","tile","diagnostics","help","feedback","about"),StageHomeLayout.sections.flatMap{it.items}.map{it.page})
    }
    @Test fun phoneAndFoldedColumnsUseAvailableWidthExpandedIsCapped() {
        assertEquals(1264,StageHomeLayout.contentWidth(1264,3.2f))
        assertEquals(1140,StageHomeLayout.contentWidth(1140,3f))
        assertEquals((680*2.625f).toInt(),StageHomeLayout.contentWidth(2248,2.625f))
        assertTrue(StageHomeLayout.contentWidth(2480,2.625f)<2480)
    }
    @Test fun rowsAndHeadersAreMinimumSizesNotFixedHeightForLargeFont() {
        assertTrue(StageHomeLayout.ROW_DP in 56..64)
        assertTrue(StageHomeLayout.HEADER_DP>=48)
        assertEquals(3,StageHomeLayout.sections.size)
        assertEquals(1,StageHomeLayout.sections.flatMap{it.items}.count{it.page=="feedback"})
    }
}
