package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageIssueEvidencePolicyTest {
    @Test fun captureOnlySelectedCommandScope() {
        val groups=mapOf("services" to (listOf("save","new","restore","delete","rename") +
            listOf("quick_enable","quick_off","quick_collapse","quick_expand","quick_auto","quick_fixed")),
            "layout" to listOf("add_window","replace_window","close_window","main_priority","main_complete",
                "layout_side","layout_top","adaptive_on","adaptive_off","left","right","collapse","expand"),
            "content" to listOf("cinema_auto","cinema_landscape","cinema_portrait","cinema_off",
                "cinema_enter","cinema_leave","wechat_move"))
        val modules=listOf("services","layout","content","compatibility","performance")
        val cmds=groups.values.flatten()+listOf("query","","enable","disable","unknown")
        for(mask in 0 until (1 shl modules.size)) {
            val scope=modules.filterIndexed { i,_ -> mask and (1 shl i) != 0 }.toSet()
            for(cmd in cmds) {
                val owner=groups.entries.firstOrNull{cmd in it.value}?.key
                val expected=owner!=null && owner in scope
                val result=StageIssueEvidencePolicy.commandLabel(cmd,scope)
                assertEquals(expected,result!=null)
                if(result!=null) assertTrue(result.endsWith("/$cmd"))
            }
        }
    }
    @Test fun legacyAndComprehensiveUseActualCatalogScope() {
        fun has(id:String,cmd:String)=StageIssueEvidencePolicy.commandLabel(cmd,StageIssueTestCatalog.modulesFor(id))!=null
        assertTrue(has("all","save"))
        assertTrue(has("all","add_window"))
        assertTrue(has("all","cinema_enter"))
        assertTrue(has("windows","add_window"))
        assertTrue(has("gaming","cinema_enter"))
        assertFalse(has("performance","save"))
        assertFalse(has("other","save"))
        assertFalse(has("layout","query"))
    }
}
