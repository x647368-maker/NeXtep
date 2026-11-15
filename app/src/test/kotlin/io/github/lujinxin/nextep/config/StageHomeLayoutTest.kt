package io.github.x647368maker.atomicworkbench.config
import org.junit.Assert.*
import org.junit.Test

class StageHomeLayoutTest {
    @Test fun threeGroupsMatchRequestedOrderWithoutDuplicateDestinations() {
        assertEquals(listOf("工作台","故障诊断","支持与服务"),StageHomeLayout.sections.map{it.title})
        assertEquals(listOf("windows","core","groups","gestures",
            "tile","issue_all","issue_specialties","update","feedback","donate","about","help"),
            StageHomeLayout.sections.flatMap{it.items}.map{it.page})
        assertEquals(listOf(5,2,5),StageHomeLayout.sections.map{it.items.size})
        assertEquals(12,StageHomeLayout.sections.flatMap{it.items}.map{it.page}.toSet().size)
    }
    @Test fun updateChangelogAndDonateOnlyAppearUnderSupport() {
        val support=StageHomeLayout.sections.single{it.title=="支持与服务"}
        assertEquals(listOf("update"),support.items.filter { it.page=="update"||it.page=="changelog" }.map { it.page })
        assertEquals(listOf("软件更新","使用帮助与退出"),support.items.filter { it.page=="update"||it.page=="help" }.map { it.name })
        assertEquals(listOf("issue_all","issue_specialties"),StageHomeLayout.sections.single{it.title=="故障诊断"}.items.map{it.page})
        assertEquals(1,support.items.count { it.page=="donate" })
        assertFalse(StageHomeLayout.sections.filter{it.title!="支持与服务"}.flatMap{it.items}.any{it.page=="donate"})
    }
    @Test fun homeHasNeitherSearchNorDuplicateDestructiveExit() {
        val items=StageHomeLayout.sections.flatMap{it.items}
        assertFalse(items.any { it.name.contains("搜索")||it.name=="退出台前调度"||it.name=="选择主应用" })
        assertTrue(StageHomeLayout.ROW_DP>=48)
    }
    @Test fun expandedMenuScrollsInsteadOfShrinkingTouchRows() {
        val rows=StageHomeLayout.sections.sumOf{it.items.size}*StageHomeLayout.ROW_DP
        assertTrue(rows>600) // ScrollView, never cut touch areas to fit.
        assertTrue(StageHomeLayout.HEADER_DP>=48)
        assertTrue(StageHomeLayout.MAX_COLUMN_DP in 600..800)
    }
}
