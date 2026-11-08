package io.github.x647368maker.atomicworkbench.config
import org.junit.Assert.*
import org.junit.Test

class StageHomeLayoutTest {
    @Test fun threeGroupsMatchRequestedOrderWithoutLosingDestinations() {
        assertEquals(listOf("工作台","系统","支持"),StageHomeLayout.sections.map{it.title})
        assertEquals(listOf("windows","core","groups","gestures","tile","diagnostics","help","feedback","about"),
            StageHomeLayout.sections.flatMap{it.items}.map{it.page})
        assertEquals(listOf(4,3,2),StageHomeLayout.sections.map{it.items.size})
    }
    @Test fun homeHasNeitherSearchNorDuplicateDestructiveExit() {
        val items=StageHomeLayout.sections.flatMap{it.items}
        assertEquals(9,items.size);assertEquals(9,items.map{it.page}.toSet().size)
        assertFalse(items.any{it.name.contains("搜索")||it.name=="退出台前调度"||it.name=="选择主应用"})
    }
    @Test fun normalTextHomeFitsReferenceViewportAndRowsRemainTouchable() {
        assertTrue(StageHomeLayout.ROW_DP>=48)
        val rows=9*StageHomeLayout.ROW_DP
        val groups=3*(StageHomeLayout.SECTION_TOP_DP+14+StageHomeLayout.GROUP_GAP_DP)+6
        val total=12+StageHomeLayout.HEADER_DP+rows+groups+16+48
        assertTrue("Normal font content height $total dp",total<=760)
    }
}
