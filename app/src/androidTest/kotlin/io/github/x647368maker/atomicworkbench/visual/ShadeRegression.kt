package io.github.x647368maker.atomicworkbench.visual

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import io.github.x647368maker.atomicworkbench.systemui.*
import io.github.x647368maker.atomicworkbench.workspace.*
import java.io.File

/** Native AOSP panels are supplementary checks, never presented as ColorOS injection tests. */
internal class ShadeRegression(private val inst:Instrumentation) {
    private fun ui(block:()->Unit)=inst.runOnMainSync(block)
    private fun settle(){inst.waitForIdleSync();Thread.sleep(130)}
    private fun menuCount()=inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("GLASS_TEST_ACTION")?.size ?: 0
    fun run(activity:Activity,root:FrameLayout,out:File):List<String> {
        val lines=mutableListOf<String>();val wm=activity.getSystemService(WindowManager::class.java)
        lateinit var anchor:TextView;lateinit var panel:FrameLayout
        ui {
            root.removeAllViews();root.setBackgroundColor(Color.rgb(80,125,180))
            anchor=TextView(activity).apply{text="MENU";setTextColor(Color.WHITE)}
            root.addView(anchor,FrameLayout.LayoutParams(160,160).apply{leftMargin=30;topMargin=190})
            panel=FrameLayout(activity)
            val p=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT)
            p.token=activity.window.decorView.windowToken;p.setTitle("NotificationShade-Fixture");panel.visibility=View.INVISIBLE
            wm.addView(panel,p);SystemUiPanelMonitor.observe(panel)
        };settle()
        ui {
            val params=panel.layoutParams as WindowManager.LayoutParams
            val flags=params.flags;val clip=Rect(0,0,1200,2500);panel.clipBounds=clip
            val event=MotionEvent.obtain(10,20,MotionEvent.ACTION_MOVE,122f,2500f,0)
            val location=intArrayOf(22,31)
            repeat(20){
                SystemUiRootTransformController.setGeometry(WorkspaceLayoutProfile.create(1272,2772,512,WorkspaceNavigationInsets()).geometry)
                SystemUiRootTransformController.setActive(true)
                check(!SystemUiRootTransformController.beginShadeTransform(panel))
                check(SystemUiRootTransformController.mapToContent(panel,event)==null)
                SystemUiRootTransformController.mapToScreen(event,null)
                SystemUiRootTransformController.mapWindowLocationToContent(panel,location)
                SystemUiShadeInputController.apply(panel,WorkspaceLayoutProfile.create(1272,2772,512,WorkspaceNavigationInsets()).geometry)
                SystemUiShadeInputController.beforeLayout(panel,params);SystemUiShadeInputController.restore(panel)
                check(event.x==122f && event.y==2500f && location.contentEquals(intArrayOf(22,31)))
                check(panel.scaleX==1f && panel.scaleY==1f && panel.translationX==0f && panel.translationY==0f && panel.clipBounds==clip && params.flags==flags)
            };event.recycle();panel.clipBounds=null
        }
        lines.add("PASS native shade adapter: 20 cycles preserve matrix, clip, native flags, location and MotionEvent exactly; no custom touch region")
        repeat(20) { i->
            ui{AtomicPopupMenu.show(activity,anchor,(listOf("GLASS_TEST_ACTION" to {}) + if(i==0)(1..15).map{"MENU_ITEM_$it" to {}} else emptyList()))};settle();check(menuCount()==1){"Menu unavailable while panel closed"}
            if(i==0) {
                val bounds=Rect();inst.uiAutomation.rootInActiveWindow!!.getBoundsInScreen(bounds)
                check(bounds.height()<=activity.resources.displayMetrics.heightPixels*.60f)
                check(bounds.width()<=300*activity.resources.displayMetrics.density+3)
                inst.uiAutomation.takeScreenshot()?.let {b->File(out,"099-glass-menu.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            }
            ui{panel.visibility=View.VISIBLE;SystemUiPanelMonitor.changed()};settle()
            check(menuCount()==0){"Menu overlapped native panel"}
            ui{check(!AtomicPopupMenu.isShowing()){ "Popup survived native panel opening" }}
            // The normal test APK lacks STATUS_BAR: collapse denial must never create an overlay.
            ui{AtomicPopupMenu.show(activity,anchor,listOf("GLASS_TEST_ACTION" to {}))};settle();check(menuCount()==0)
            ui{panel.visibility=View.INVISIBLE;SystemUiPanelMonitor.changed()};settle()
        }
        ui{AtomicPopupMenu.dismissAll();wm.removeViewImmediate(panel)};settle()
        lines.add("PASS 20 real popup/native-window-fixture mutual exclusion cycles; panel open dismisses menu, collapse denial blocks overlay, closed state reopens")
        // Actual unmodified emulator SystemUI: open via public accessibility action and close by raw swipe.
        fun waitPackage(pkg:String):Boolean {
            repeat(30){if(inst.uiAutomation.rootInActiveWindow?.packageName?.toString()==pkg)return true;Thread.sleep(100)}
            return false
        }
        fun swipeUp() {
            val m=activity.resources.displayMetrics;val x=m.widthPixels*.5f;val down=SystemClock.uptimeMillis()
            for(i in 0..24){val y=m.heightPixels*(.88f-.85f*i/24f);val action=when(i){0->MotionEvent.ACTION_DOWN;24->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE};val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);check(inst.uiAutomation.injectInputEvent(e,true));e.recycle();Thread.sleep(10)}
        }
        for((action,name)in listOf(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS to "notifications",AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS to "quick settings")) {
            repeat(20){
                check(inst.uiAutomation.performGlobalAction(action));check(waitPackage("com.android.systemui")){"Native $name did not open"}
                Thread.sleep(220);swipeUp();check(waitPackage(activity.packageName)){"Native $name swipe-up did not return input focus"}
            }
            lines.add("PASS 20 actual AOSP $name accessibility-open/raw-swipe-up-close cycles; activity input focus returned")
        }
        File(out,"shade-results.txt").writeText(lines.joinToString("\n")+"\n")
        return lines
    }
}
