package io.github.x647368maker.atomicworkbench.systemui
import org.junit.Assert.*
import org.junit.Test

class StageRefinementTest {
    @Test fun everyMainCommandRemainsReachableExactlyOnceIncludingFutureCommands(){
        val labels=listOf("切换全屏（保留工作区）","顶部横排","左侧排列","右侧排列","收起常用应用快捷栏","完整显示布局","✓ 主窗口优先布局","添加小窗","影音布局与自动模式","将当前微信任务移入小窗","替换主应用","关闭主窗（移回后台）","退出台前调度","未来功能")
        assertTrue(StageMenuPolicy.isMain(labels))
        val pages=(0..3).map{g->labels.filter{StageMenuPolicy.group(it)==g}}
        assertEquals(labels.size,pages.flatten().size)
        assertEquals(labels.toSet(),pages.flatten().toSet())
        assertEquals(0,StageMenuPolicy.group("✓ 完整显示布局"))
        assertEquals(2,StageMenuPolicy.group("展开常用应用快捷栏"))
        assertFalse(StageMenuPolicy.isMain(listOf("顶部横排")))
    }
    @Test fun placementUsesFreeSpaceAndStaysWithinFoldedBounds(){
        for(width in listOf(320,398,840)) {
            val safe=StageMenuPlacement.Box(8,24,width-8,850)
            val avoid=listOf(StageMenuPlacement.Box(110,300,width-16,840))
            val p=StageMenuPlacement.place(safe,StageMenuPlacement.Box(12,30,60,78),200,204,avoid,8)
            assertTrue(p.l>=safe.l && p.t>=safe.t && p.r<=safe.r && p.b<=safe.b)
            assertEquals(0L,avoid.sumOf{p.overlap(it)})
        }
    }
    @Test fun transientReadyIdentityDoesNotRevealAnUnsettledSurface(){
        val h=StageStableHandoff()
        assertFalse(h.observe(true));assertFalse(h.observe(false));assertFalse(h.observe(true));assertTrue(h.observe(true))
        assertFalse(h.observe(false))
    }
}
