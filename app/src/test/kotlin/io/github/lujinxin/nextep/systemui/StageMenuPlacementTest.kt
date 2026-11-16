package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageMenuPlacementTest {
    private fun b(l:Int,t:Int,r:Int,bt:Int)=StageMenuPlacement.Box(l,t,r,bt)
    private val safe=b(0,0,400,800)
    private val anchor=b(8,8,56,56)
    private fun choose(avoid:List<StageMenuPlacement.Box>,bounds:StageMenuPlacement.Box=safe)=
        StageMenuPlacement.compact(bounds,anchor,208,252,160,60,avoid,8)
    private fun inside(bounds:StageMenuPlacement.Box,actual:StageMenuPlacement.Box){
        assertTrue(actual.l>=bounds.l && actual.t>=bounds.t && actual.r<=bounds.r && actual.b<=bounds.b)
        assertTrue(actual.w>=160 && actual.h>=60)
    }
    @Test fun portraitGlassAllowsNarrowerTwoPaneWithoutOverlappingTasks(){
        val phone=b(8,32,355,780)
        val p=StageMenuPlacement.dualGlass(phone,anchor,304,252,282,204,
            listOf(b(0,300,363,780)),8)!!
        assertTrue(p.w in 282..304 && p.h>=204)
        assertEquals(0L,p.overlap(b(0,300,363,780)))
        assertNull(StageMenuPlacement.dualGlass(phone,anchor,304,252,282,204,
            listOf(b(0,180,363,780)),8))
        assertEquals(3,StageMenuPolicy.group("切换全屏（保留工作区）"))
        assertEquals(3,StageMenuPolicy.group("添加常用应用到快捷栏"))
        assertEquals(3,StageMenuPolicy.group("恢复被隐藏的常用应用"))
        assertEquals(0,StageMenuPolicy.group("✓ 主窗口优先布局"))
    }
    @Test fun selectedLayoutAndActionGlyphFollowRealCommandIdentity(){
        val selected=setOf("左侧排列","主窗口优先布局")
        assertTrue(StageMenuPolicy.isSelected("左侧排列",selected))
        assertTrue(StageMenuPolicy.isSelected("主窗口优先布局",selected))
        assertTrue(StageMenuPolicy.isSelected("✓ 完整显示布局",emptySet()))
        assertFalse(StageMenuPolicy.isSelected("顶部横排",selected))
        assertFalse(StageMenuPolicy.isSelected("添加小窗",setOf("添加小窗")))
        val labels=listOf("顶部横排","左侧排列","右侧排列","主窗口优先布局","完整显示布局",
            "切换全屏（保留工作区）","添加小窗","替换主应用","退出台前调度","恢复被隐藏的常用应用")
        assertEquals(labels.size,labels.map(StageMenuPolicy::actionGlyph).toSet().size)
        assertEquals(StageMenuPolicy.actionGlyph("✓ 主窗口优先布局"),StageMenuPolicy.actionGlyph("主窗口优先布局"))
        val management=listOf("主窗口与小窗交换","窗口顺序调整","隐藏/显示侧边栏")
        assertTrue(management.all { StageMenuPolicy.group(it)==1 })
        assertEquals(3,management.map(StageMenuPolicy::actionGlyph).toSet().size)
    }
    @Test fun fullSizeWithoutObstacles(){
        val p=choose(emptyList())
        assertEquals(208,p.w);assertEquals(252,p.h);inside(safe,p)
    }
    @Test fun topRailUsesAvailableShortGapInsteadOfCoveringCards(){
        val avoid=listOf(b(0,150,400,300),b(0,300,400,800))
        val p=choose(avoid);inside(safe,p)
        assertTrue(p.h<252);assertTrue(avoid.all{p.overlap(it)==0L})
        assertEquals(0L,avoid.sumOf{p.overlap(it)})
    }
    @Test fun leftAndRightRailsUseEmptyBottomStrip(){
        for(left in listOf(true,false)){
            val rail=if(left)b(0,0,105,700) else b(295,0,400,700)
            val main=if(left)b(110,0,400,700) else b(0,0,290,700)
            val avoid=listOf(main,rail)
            val p=choose(avoid);inside(safe,p)
            assertTrue(p.t>=700);assertTrue(avoid.all{p.overlap(it)==0L})
        }
    }
    @Test fun narrowFreeRegionCanReduceWidthWithoutCoveringTasks(){
        val avoid=listOf(b(0,0,220,800))
        val p=choose(avoid);inside(safe,p)
        assertTrue(p.w in 160..180);assertEquals(252,p.h)
        assertEquals(0L,p.overlap(avoid.first()))
    }
    @Test fun foldAndTabletRespectScreenOffsets(){
        for(bounds in listOf(b(20,32,700,850),b(24,16,1000,640))){
            val avoid=listOf(b(bounds.l,bounds.t+110,bounds.r,bounds.b))
            val p=choose(avoid,bounds);inside(bounds,p)
            assertTrue(avoid.all{p.overlap(it)==0L})
        }
    }
    @Test fun impossibleFullViewportKeepsAReachableCompactMenu(){
        val avoid=listOf(b(100,0,400,800),b(0,0,100,800))
        val p=choose(avoid);inside(safe,p)
        assertEquals(60,p.h);assertEquals(0L,p.overlap(avoid[1]))
        assertTrue(p.overlap(avoid[0])>0)
    }
    @Test fun exactTouchingEdgesRemainValidWithoutArtificialOverlap(){
        val avoid=listOf(b(0,60,400,800))
        val p=choose(avoid);inside(safe,p)
        assertEquals(60,p.h);assertEquals(0L,p.overlap(avoid[0]))
    }
    @Test fun overlappingProtectedBoundsAndOffscreenBoundsAreClamped(){
        val avoid=listOf(b(-30,0,220,600),b(0,500,220,900))
        val p=choose(avoid);inside(safe,p)
        assertTrue(avoid.all{p.overlap(it)==0L})
    }
    @Test fun fullMenuDoesNotCollapseWhenOnlyOneRowFitsOutsideTasks(){
        val avoid=listOf(b(0,60,400,800))
        val p=StageMenuPlacement.place(safe,anchor,208,252,avoid,8)
        assertEquals(208,p.w)
        assertEquals(252,p.h)
        inside(safe,p)
    }
    @Test fun fullMenuPrefersUncoveredSpaceWhenAvailable(){
        val avoid=listOf(b(0,320,400,800))
        val p=StageMenuPlacement.place(safe,anchor,208,252,avoid,8)
        assertEquals(252,p.h)
        assertEquals(0L,p.overlap(avoid.first()))
        inside(safe,p)
    }
}
