package io.github.x647368maker.atomicworkbench.config
import org.junit.Assert.*
import org.junit.Test

class StageHomeLayoutTest {
    @Test fun threeGroupsMatchRequestedOrderWithoutDuplicateDestinations() {
        assertEquals(listOf("工作台","系统","支持"),StageHomeLayout.sections.map{it.title})
        assertEquals(listOf("windows","core","groups","gestures",
            "tile","diagnostics","update","help","feedback","donate","about"),
            StageHomeLayout.sections.flatMap{it.items}.map{it.page})
        assertEquals(listOf(4,4,3),StageHomeLayout.sections.map{it.items.size})
        assertEquals(11,StageHomeLayout.sections.flatMap{it.items}.map{it.page}.toSet().size)
    }
    @Test fun updateChangelogOnlyAppearUnderSystemAndDonateOnlyUnderSupport() {
        val system=StageHomeLayout.sections.single{it.title=="系统"}
        val support=StageHomeLayout.sections.single{it.title=="支持"}
        assertEquals(listOf("update"),system.items.filter { it.page=="update"||it.page=="changelog" }.map { it.page })
        assertEquals(listOf("软件更新","使用帮助与退出"),system.items.filter { it.page=="update"||it.page=="help" }.map { it.name })
        assertEquals(1,support.items.count { it.page=="donate" })
        assertFalse(StageHomeLayout.sections.filter{it.title!="支持"}.flatMap{it.items}.any{it.page=="donate"})
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
