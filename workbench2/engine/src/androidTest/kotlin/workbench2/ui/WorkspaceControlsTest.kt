package workbench2.ui

import android.view.MotionEvent
import android.view.View
import android.os.SystemClock
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import workbench2.layout.WorkspaceLayout
import workbench2.layout.WorkspaceLayout.Viewport
import workbench2.layout.WorkspaceLayout.Insets
import workbench2.layout.WorkspaceLayout.Rail
import workbench2.motion.Box

class WorkspaceControlsTest {
    private val phone=Viewport(1264,2780,512,Insets(bottom=100),205,316,rotation=0)
    private fun onMain(block: ()->Unit) {
        var error: Throwable?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch(t: Throwable) { error=t }
        }
        error?.let { throw it }
    }
    private fun create()=WorkspaceControls(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun arrange(v: WorkspaceControls,w: Int,h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
        v.layout(0,0,w,h)
    }
    private fun locate(v: WorkspaceControls,description: String): TextView =
        (0 until v.childCount).map{v.getChildAt(it)}.single{it.contentDescription==description} as TextView
    private fun bounds(v: View,b: Box) {
        assertEquals(b.x.toInt(),v.left);assertEquals(b.y.toInt(),v.top)
        assertEquals(b.w.toInt(),v.width);assertEquals(b.h.toInt(),v.height)
    }
    private fun touch(v: WorkspaceControls,b: Box,action: Int,down: Long): Boolean {
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,b.cx.toFloat(),b.cy.toFloat(),0)
        return try { v.dispatchTouchEvent(event) } finally { event.recycle() }
    }
    @Test fun nativeTopControlsUseLayoutAndHideAddAtCapacity()=onMain {
        val v=create();val seen=mutableListOf<WorkspaceControls.Request>()
        v.setRequestListener { seen.add(it) }
        val l=WorkspaceLayout.calculate(phone,Rail.TOP,listOf(0,3),true,7)
        assertTrue(v.bind(l,1,false,true));arrange(v,phone.width,phone.height)
        val add=locate(v,"添加小窗，最多四个")
        val main=locate(v,"主窗口操作");val collapse=locate(v,"收起小窗")
        bounds(add,l.addAction!!);bounds(main,l.topActions!!.main);bounds(collapse,l.topActions!!.collapse)
        val down=SystemClock.uptimeMillis()
        assertTrue(touch(v,l.addAction!!,MotionEvent.ACTION_DOWN,down))
        assertTrue(touch(v,l.addAction!!,MotionEvent.ACTION_UP,down))
        // Native TextView can post click to the looper; performClick supplies
        // an independent synchronous accessibility route.
        assertTrue(add.performClick());assertTrue(main.performClick());assertTrue(collapse.performClick())
        assertTrue(seen.any{it.action==WorkspaceControls.Action.ADD})
        assertTrue(seen.any{it.action==WorkspaceControls.Action.MAIN_ACTION && it.displayEpoch==7L})
        assertTrue(seen.any{it.action==WorkspaceControls.Action.COLLAPSE})
        val full=WorkspaceLayout.calculate(phone,Rail.TOP,(0..3).toList(),true,7)
        assertTrue(v.bind(full,2,false,true));arrange(v,phone.width,phone.height)
        assertEquals(View.INVISIBLE,add.visibility)
        val n=seen.size;add.performClick();assertEquals(n,seen.size)
        assertNull(full.addAction)
    }
    @Test fun nativeSideAddAndSmartRestoreKeepIndependentHitRegions()=onMain {
        val v=create();val seen=mutableListOf<WorkspaceControls.Request>()
        v.setRequestListener {seen.add(it)}
        val fold=Viewport(2248,2480,416,Insets(top=72,bottom=44),166,562,rotation=0)
        for((i,rail) in listOf(Rail.LEFT,Rail.RIGHT).withIndex()) {
            val l=WorkspaceLayout.calculate(fold,rail,listOf(0,2,3),true,8)
            val add=locate(v,"添加小窗，最多四个")
            val restore=locate(v,"展开小窗")
            assertTrue(v.bind(l,2L*i+1,false,true));arrange(v,fold.width,fold.height)
            bounds(add,l.addAction!!);assertEquals(View.INVISIBLE,restore.visibility)
            assertTrue(add.performClick());assertEquals(WorkspaceControls.Action.ADD,seen.last().action)
            assertTrue(v.bind(l,2L*i+2,true,true));arrange(v,fold.width,fold.height)
            bounds(restore,l.recovery.handle);assertEquals("│",restore.text.toString())
            assertEquals(View.INVISIBLE,add.visibility)
            assertTrue(l.recovery.main.w>=l.main.w)
            assertTrue(restore.performClick());assertEquals(WorkspaceControls.Action.RESTORE,seen.last().action)
            assertEquals(3,l.cards.size)
        }
    }
    @Test fun staleLayoutResizeInputGateAndClearCannotDispatchControls()=onMain {
        val v=create();val events=mutableListOf<WorkspaceControls.Request>()
        v.setRequestListener {events.add(it)}
        val l=WorkspaceLayout.calculate(phone,Rail.TOP,listOf(0),false,12)
        assertTrue(v.bind(l,5,false,true));arrange(v,phone.width,phone.height)
        val add=locate(v,"添加小窗，最多四个")
        val down=SystemClock.uptimeMillis()
        assertTrue(touch(v,l.addAction!!,MotionEvent.ACTION_DOWN,down))
        assertTrue(v.bind(l,6,false,false));arrange(v,phone.width,phone.height)
        assertFalse(touch(v,l.addAction!!,MotionEvent.ACTION_UP,down))
        add.performClick();assertTrue(events.isEmpty())
        assertFalse(v.bind(l,5,false,true))
        assertTrue(v.bind(l,7,false,true));arrange(v,phone.width-1,phone.height)
        add.performClick();assertTrue(events.isEmpty());assertEquals(View.INVISIBLE,add.visibility)
        arrange(v,phone.width,phone.height)
        assertTrue(add.performClick());assertEquals(7L,events.single().revision)
        assertTrue(v.clear(8));arrange(v,phone.width,phone.height)
        add.performClick();assertEquals(1,events.size)
        assertFalse(v.bind(l,7,false,true))
        val obsolete=WorkspaceLayout.calculate(phone,Rail.TOP,listOf(0),false,11)
        assertFalse(v.bind(obsolete,9,false,true))
    }
    @Test fun rotatedSideButtonsAndRestoreUsePhysicalDestinationCoordinates()=onMain {
        val v=create()
        val a=Viewport(2354,1080,480,Insets(left=132),192,270,rotation=3)
        var revision=0L
        for(rotation in listOf(3,1))for(rail in listOf(Rail.LEFT,Rail.RIGHT)) {
            val viewport=a.copy(rotation=rotation,insets=if(rotation==3)Insets(left=132)else Insets(right=132))
            val l=WorkspaceLayout.calculate(viewport,rail,listOf(0,1,2),false,revision)
            assertTrue(v.bind(l,++revision,false,true));arrange(v,viewport.width,viewport.height)
            bounds(locate(v,"添加小窗，最多四个"),l.addAction!!)
            assertTrue(v.bind(l,++revision,true,true));arrange(v,viewport.width,viewport.height)
            val restore=locate(v,"展开小窗");bounds(restore,l.recovery.handle)
            assertEquals("━",restore.text.toString())
        }
    }

    @Test fun attachedNativeControlReceivesScreenTouchInOwnActivity() {
        val instr=InstrumentationRegistry.getInstrumentation()
        val activity=instr.startActivitySync(android.content.Intent(instr.targetContext,
            workbench2.engine.OwnedSurfaceFixtureActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as workbench2.engine.OwnedSurfaceFixtureActivity
        val laidOut=java.util.concurrent.CountDownLatch(1)
        val clicked=java.util.concurrent.CountDownLatch(1)
        lateinit var control: WorkspaceControls
        var x=0f;var y=0f
        val seen=mutableListOf<WorkspaceControls.Request>()
        try {
            onMain {
                activity.window.setDecorFitsSystemWindows(false)
                control=WorkspaceControls(activity)
                control.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_-> if(control.width>0&&control.height>0)laidOut.countDown() }
                activity.setContentView(control)
                control.setRequestListener { seen.add(it);clicked.countDown() }
            }
            assertTrue("Own Android Activity control layout",laidOut.await(10,java.util.concurrent.TimeUnit.SECONDS))
            onMain {
                val metrics=activity.windowManager.currentWindowMetrics
                val b=metrics.bounds
                val rotation=activity.display!!.rotation
                val v=WorkspaceLayout.fromWindowMetrics(metrics,b.width(),b.height(),
                    activity.resources.displayMetrics.densityDpi,rotation,Rail.TOP,2,true)
                val l=WorkspaceLayout.calculate(v,Rail.TOP,listOf(0,1),true,51)
                assertTrue(control.bind(l,1,false,true))
                x=l.addAction!!.cx.toFloat();y=l.addAction!!.cy.toFloat()
            }
            // A requestLayout with unchanged parent bounds need not invoke a
            // layout-change listener; synchronize with the actual UI traversal.
            instr.waitForIdleSync()
            val until=SystemClock.elapsedRealtime()+5000
            var ready=false
            while(!ready && SystemClock.elapsedRealtime()<until) {
                onMain {
                    val add=locate(control,"添加小窗，最多四个")
                    val origin=IntArray(2);control.getLocationOnScreen(origin)
                    ready=control.isAttachedToWindow && origin.contentEquals(intArrayOf(0,0)) &&
                        add.visibility==View.VISIBLE && add.isEnabled && add.width>0
                }
                if(!ready)SystemClock.sleep(20) // Bounded test-only synchronization.
            }
            assertTrue("Actual control visible at verified display origin",ready)
            val down=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { instr.sendPointerSync(event) } finally { event.recycle() }
            }
            assertTrue("Real screen touch reached our own control",clicked.await(5,java.util.concurrent.TimeUnit.SECONDS))
            onMain {
                assertEquals(listOf(WorkspaceControls.Request(WorkspaceControls.Action.ADD,1,51)),seen)
            }
        } finally { onMain { activity.finish() } }
    }
}
