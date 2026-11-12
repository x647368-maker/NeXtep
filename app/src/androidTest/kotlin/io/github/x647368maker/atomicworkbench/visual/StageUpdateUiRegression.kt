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
import io.github.x647368maker.atomicworkbench.config.StageUpdateBackdrop
import java.io.File

/** Render the actual update page, assert its guide is in the live hierarchy. */
internal class StageUpdateUiRegression(private val instrumentation: Instrumentation) {
    fun run(activity: Activity, root: FrameLayout, out: File): String {
        val shown=mutableListOf<String>()
        lateinit var screen: View
        instrumentation.runOnMainSync {
            screen=UpdateSection.create(activity as AppCompatActivity)
            root.removeAllViews()
            root.background = StageUpdateBackdrop()
            root.addView(screen,FrameLayout.LayoutParams(-1,-1))
            fun visit(v:View) {
                if (v is TextView) shown.add(v.text.toString())
                if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
            visit(screen)
        }
        instrumentation.waitForIdleSync()
        // Wait for the first HWUI surface frame, not merely the view-tree traversal.
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
        for (required in listOf("台前调度","检查新版本","检查更新")) {
            check(shown.any { it.contains(required) }) { "Update page hid required content: $required" }
        }
        check(shown.none { it.contains("已安装版本") || it.contains("Atomic Workbench") }) { "Update page exposed removed hero text" }
        // Real full-height layout: all static drawing, bottom-anchored card, and
        // a visible gap between device hero and status content on tall phones.
        val hero=root.findViewWithTag<View>("stage_update_hero")
        val card=root.findViewWithTag<View>("stage_update_bottom_card")
        check(hero!=null && card!=null) { "Update hero or bottom glass card not present" }
        if (root.height > (650*activity.resources.displayMetrics.density)) {
            val heroXY=IntArray(2);val cardXY=IntArray(2)
            hero.getLocationOnScreen(heroXY);card.getLocationOnScreen(cardXY)
            check(cardXY[1]-heroXY[1]-hero.height > (36*activity.resources.displayMetrics.density)) {
                "Updated status card crowds the device title"
            }
        }
        // An emulated HWUI surface can lag the first idle callback. Do not
        // silently accept a plain backdrop as a passed hero screenshot.
        val heroBounds=IntArray(2)
        hero.getLocationOnScreen(heroBounds)
        fun heroPainted(bitmap:Bitmap): Boolean {
            var whitePixels=0
            val left=(bitmap.width/5).coerceAtLeast(0)
            val right=(bitmap.width*4/5).coerceAtMost(bitmap.width)
            val top=heroBounds[1].coerceIn(0,bitmap.height-1)
            val bottom=(top+hero.height).coerceAtMost(bitmap.height)
            for(y in top until bottom step 4) for(x in left until right step 4) {
                val color=bitmap.getPixel(x,y)
                if(android.graphics.Color.red(color)+android.graphics.Color.green(color)+
                    android.graphics.Color.blue(color)>690) whitePixels++
                if(whitePixels>=20) return true
            }
            return false
        }
        var first:Bitmap?=null
        for(attempt in 0 until 8) {
            val shot=instrumentation.uiAutomation.takeScreenshot() ?: continue
            if(heroPainted(shot)) {first=shot;break}
            shot.recycle()
            Thread.sleep(160)
            instrumentation.waitForIdleSync()
        }
        val ready=first ?: error("Update hero not visible in an actual HWUI screenshot")
        File(out,"update-center-hero.png").outputStream().use { ready.compress(Bitmap.CompressFormat.PNG,100,it) }
        ready.recycle()
        instrumentation.runOnMainSync { (screen as? ScrollView)?.fullScroll(View.FOCUS_DOWN) }
        instrumentation.waitForIdleSync()
        val last=instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot for update instructions")
        File(out,"update-center-help-visible.png").outputStream().use { last.compress(Bitmap.CompressFormat.PNG,100,it) }
        last.recycle()
        instrumentation.runOnMainSync {
            root.removeAllViews()
            root.setBackgroundColor(android.graphics.Color.rgb(20,32,70))
        }
        return "PASS update center: device hero, bottom glass card, no top version, safe manual check; HWUI screenshots"
    }
}
