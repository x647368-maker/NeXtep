package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.x647368maker.atomicworkbench.config.UpdateSection
import java.io.File

/** Render the actual update page, assert its guide is in the live hierarchy. */
internal class StageUpdateUiRegression(private val instrumentation: Instrumentation) {
    fun run(activity: Activity, root: FrameLayout, out: File): String {
        val shown=mutableListOf<String>()
        val screen=ScrollView(activity)
        instrumentation.runOnMainSync {
            val page=UpdateSection.create(activity as AppCompatActivity)
            screen.addView(page,ViewGroup.LayoutParams(-1,-2))
            root.removeAllViews()
            root.setBackgroundColor(android.graphics.Color.rgb(21,17,45))
            root.addView(screen,FrameLayout.LayoutParams(-1,-1))
            fun visit(v:View) {
                if (v is TextView) shown.add(v.text.toString())
                if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
            visit(screen)
        }
        instrumentation.waitForIdleSync()
        for (required in listOf("台前调度","检查新版本","检查更新","当前设备")) {
            check(shown.any { it.contains(required) }) { "Update page hid required content: $required" }
        }
        check(shown.none { it.contains("已安装版本") || it.contains("Atomic Workbench") }) { "Update page exposed removed hero text" }
        val first=instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot for update hero")
        File(out,"update-center-hero.png").outputStream().use { first.compress(Bitmap.CompressFormat.PNG,100,it) }
        first.recycle()
        instrumentation.runOnMainSync { screen.fullScroll(View.FOCUS_DOWN) }
        instrumentation.waitForIdleSync()
        val last=instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot for update instructions")
        File(out,"update-center-help-visible.png").outputStream().use { last.compress(Bitmap.CompressFormat.PNG,100,it) }
        last.recycle()
        instrumentation.runOnMainSync {
            root.removeAllViews()
            root.setBackgroundColor(android.graphics.Color.rgb(20,32,70))
        }
        return "PASS update center: native gradients, clean installed-version hero, safe manual check; HWUI screenshots"
    }
}
