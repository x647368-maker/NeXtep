package workbench2.ui

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import workbench2.engine.OwnedSurfaceFixtureActivity
import workbench2.layout.WorkspaceLayout
import workbench2.layout.WorkspaceLayout.Rail

class WorkspaceGlassMenuTest {
    private val instr=InstrumentationRegistry.getInstrumentation()
    private fun onMain(block:()->Unit){
        var error:Throwable?=null
        instr.runOnMainSync{try{block()}catch(t:Throwable){error=t}}
        error?.let{throw it}
    }
    private fun view(root:View,label:String):TextView {
        if(root is TextView&&root.contentDescription==label)return root
        if(root is ViewGroup)for(i in 0 until root.childCount){
            val found=runCatching{view(root.getChildAt(i),label)}.getOrNull()
            if(found!=null)return found
        }
        error("No menu row: "+label)
    }
    private fun fixture(block:(WorkspaceControls,WorkspaceControls.Request)->Unit) {
        val activity=instr.startActivitySync(Intent(instr.targetContext,OwnedSurfaceFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OwnedSurfaceFixtureActivity
        lateinit var controls:WorkspaceControls
        val laid=java.util.concurrent.CountDownLatch(1)
        try{
            onMain{
                activity.window.setDecorFitsSystemWindows(false)
                controls=WorkspaceControls(activity)
                controls.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->if(controls.width>0&&controls.height>0)laid.countDown()}
                activity.setContentView(controls)
            }
            assertTrue(laid.await(10,java.util.concurrent.TimeUnit.SECONDS))
            onMain{
                val m=activity.windowManager.currentWindowMetrics
                val b=m.bounds
                val vp=WorkspaceLayout.fromWindowMetrics(m,b.width(),b.height(),
                    activity.resources.displayMetrics.densityDpi,activity.display!!.rotation,Rail.TOP,2,true)
                val l=WorkspaceLayout.calculate(vp,Rail.TOP,listOf(0,2),true,60)
                assertTrue(controls.bind(l,1,false,true))
            }
            val until=SystemClock.elapsedRealtime()+5000
            var ready=false
            while(!ready&&SystemClock.elapsedRealtime()<until){
                onMain{val button=view(controls,"主窗口操作");ready=button.isEnabled&&button.isAttachedToWindow&&button.width>0}
                if(!ready)SystemClock.sleep(20)
            }
            assertTrue(ready)
            onMain{block(controls,WorkspaceControls.Request(WorkspaceControls.Action.MAIN_ACTION,1,60))}
        }finally{onMain{controls.dismissMenu();activity.finish()}}
    }
    private val entries=listOf(
        WorkspaceGlassMenu.Entry("top","顶部横排"),
        WorkspaceGlassMenu.Entry("left","左侧排列"),
        WorkspaceGlassMenu.Entry("add","添加小窗"),
        WorkspaceGlassMenu.Entry("quick","常用应用管理",children=listOf(
            WorkspaceGlassMenu.Entry("quickStyle","快捷栏样式"))),
        WorkspaceGlassMenu.Entry("exit","退出台前调度",false))
    @Test fun nativeGlassMenuNavigatesHistoricalGroupsAndDispatchesLeafOnce()=fixture{controls,request->
        val seen=mutableListOf<WorkspaceGlassMenu.Selection>()
        assertTrue(controls.showMenu(request,entries,setOf("顶部横排"),{true}){seen.add(it)})
        val root=requireNotNull(controls.menuContent)
        assertTrue(view(root,"顶部横排").isSelected)
        assertTrue(view(root,"快捷栏与显示").performClick())
        assertTrue(view(root,"常用应用管理").performClick())
        assertTrue(seen.isEmpty())
        val leaf=view(root,"快捷栏样式");assertTrue(leaf.performClick())
        assertEquals(listOf(WorkspaceGlassMenu.Selection("quickStyle",1,60)),seen)
        assertNull(controls.menuContent)
        assertTrue(controls.showMenu(request,entries,emptySet(),{true}){seen.add(it)})
        val newer=controls.menuContent
        leaf.performClick();assertEquals(1,seen.size)
        assertSame(newer,controls.menuContent)
        val obsolete=view(requireNotNull(newer),"顶部横排")
        view(requireNotNull(newer),"窗口管理").performClick()
        obsolete.performClick();assertEquals(1,seen.size)
        assertSame(newer,controls.menuContent)
    }
    @Test fun nativeGlassMenuRejectsStaleUnknownAndDisabledActions()=fixture{controls,request->
        val seen=mutableListOf<WorkspaceGlassMenu.Selection>()
        var closed=false
        fun open()=controls.showMenu(request,entries,emptySet(),{closed}){seen.add(it)}
        assertFalse(open())
        closed=true;assertTrue(open())
        var root=requireNotNull(controls.menuContent)
        assertTrue(view(root,"其他功能").performClick())
        val disabled=view(root,"退出台前调度")
        assertFalse(disabled.isEnabled);disabled.performClick();assertTrue(seen.isEmpty())
        controls.dismissMenu();assertTrue(open())
        root=requireNotNull(controls.menuContent)
        val choice=view(root,"顶部横排");closed=false;choice.performClick()
        assertTrue(seen.isEmpty());assertNull(controls.menuContent)
        closed=true;assertTrue(open())
        val prior=view(requireNotNull(controls.menuContent),"顶部横排")
        assertTrue(controls.clear(2));prior.performClick()
        assertFalse(open());assertTrue(seen.isEmpty());assertNull(controls.menuContent)
    }
}
