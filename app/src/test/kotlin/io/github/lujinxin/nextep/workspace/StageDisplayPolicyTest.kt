package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageDisplayPolicyTest {
    @Test fun displayClassUsesRealSizeAndLogicalDensityNotModelName() {
        assertFalse(StageDisplayPolicy.expanded(1264,2780,512))
        assertFalse(StageDisplayPolicy.expanded(1140,2616,480))
        assertTrue(StageDisplayPolicy.expanded(2248,2480,420))
        assertTrue(StageDisplayPolicy.expanded(2480,2248,420))
    }
    @Test fun defaultsAreTopOnCompactAndSideOnExpanded() {
        assertEquals(StageDisplayPolicy.Choice(true,SidebarSide.LEFT),StageDisplayPolicy.initial(1264,2780,512,true,null,SidebarSide.RIGHT))
        assertEquals(StageDisplayPolicy.Choice(false,SidebarSide.LEFT),StageDisplayPolicy.initial(2248,2480,420,true,null,SidebarSide.RIGHT))
    }
    @Test fun firstLegacyLayoutMigratesWithoutOverwritingUsersChoice() {
        assertEquals(StageDisplayPolicy.Choice(false,SidebarSide.RIGHT),StageDisplayPolicy.initial(1264,2780,512,false,false,SidebarSide.RIGHT))
        assertEquals(StageDisplayPolicy.Choice(true,SidebarSide.RIGHT),StageDisplayPolicy.initial(2248,2480,420,false,true,SidebarSide.RIGHT))
        // An unseen later screen uses its own default instead of inheriting the last screen's selection.
        assertFalse(StageDisplayPolicy.initial(2248,2480,420,true,true,SidebarSide.RIGHT).top)
    }
    @Test fun foldAndRotationScopesRemainIndependent() {
        val scopes=listOf(StageDisplayPolicy.scope(1140,2616,480),StageDisplayPolicy.scope(2616,1140,480),
            StageDisplayPolicy.scope(2248,2480,420),StageDisplayPolicy.scope(2480,2248,420))
        assertEquals(4,scopes.toSet().size)
        val map=mutableMapOf<String,String>()
        scopes.forEachIndexed { i,k -> map[k]=StageDisplayPolicy.Choice(i%2==0,if(i<2) SidebarSide.LEFT else SidebarSide.RIGHT).encode() }
        repeat(10) { scopes.reversed().forEachIndexed { _,scope -> assertNotNull(StageDisplayPolicy.Choice.decode(map[scope])) } }
        assertTrue(StageDisplayPolicy.Choice.decode(map[scopes[0]])!!.top)
        assertFalse(StageDisplayPolicy.Choice.decode(map[scopes[3]])!!.top)
        assertEquals(SidebarSide.RIGHT,StageDisplayPolicy.Choice.decode(map[scopes[2]])!!.side)
    }
    @Test fun strategyAndAdaptiveKeysDoNotTouchCommonGroupsPinsOrMediaSettings() {
        val a=StageDisplayPolicy.scope(1140,2616,480);val b=StageDisplayPolicy.scope(2248,2480,420)
        assertNotEquals(StageDisplayPolicy.layoutKey(a,"main_priority"),StageDisplayPolicy.layoutKey(b,"main_priority"))
        assertNotEquals(StageDisplayPolicy.layoutKey(a,"adaptive"),StageDisplayPolicy.layoutKey(b,"adaptive"))
        assertEquals("main_priority",StageDisplayPolicy.layoutKey(null,"main_priority"))
        assertTrue(StageDisplayPolicy.layoutKey(a,"main_priority").startsWith("layout_v1:"))
    }
    @Test fun cachedChoiceRoundTripsAndRejectsMalformedRecords() {
        for(top in listOf(false,true)) for(side in SidebarSide.entries) {
            val c=StageDisplayPolicy.Choice(top,side);assertEquals(c,StageDisplayPolicy.Choice.decode(c.encode()))
        }
        for(value in listOf(null,"","1,unknown,LEFT","0,true,RIGHT","1,true,SIDE","1,true,LEFT,extra"))
            assertNull(StageDisplayPolicy.Choice.decode(value))
    }
    @Test fun rotationIsNotMisidentifiedAsFoldButCanvasAndDensityChangesAre() {
        assertTrue(StageDisplayPolicy.sameCanvas(2248,2480,420,2480,2248,420))
        assertFalse(StageDisplayPolicy.sameCanvas(2248,2480,420,1140,2616,480))
        assertFalse(StageDisplayPolicy.sameCanvas(1264,2780,512,1264,2780,560))
    }
}
