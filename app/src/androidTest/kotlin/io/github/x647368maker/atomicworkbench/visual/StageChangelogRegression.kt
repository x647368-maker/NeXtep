package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.x647368maker.atomicworkbench.config.StageChangelogPage
import java.io.File

/** Android-rendered proof that the bundled 0.9.26 and archived 0.9.25 entries appear. */
internal class StageChangelogRegression(private val inst: Instrumentation) {
    private fun texts(view: View, out: MutableList<String>) {
        if (view is TextView) out.add(view.text.toString())
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            texts(view.getChildAt(index), out)
        }
    }

    fun run(activity: Activity, root: FrameLayout, output: File): String {
        check(activity is AppCompatActivity) { "Changelog requires an AppCompatActivity host" }
        lateinit var page: View
        inst.runOnMainSync {
            root.removeAllViews()
            page=StageChangelogPage.create(activity)
            root.addView(page, FrameLayout.LayoutParams(-1,-2))
        }
        inst.waitForIdleSync()
        Thread.sleep(130)
        val labels=mutableListOf<String>()
        inst.runOnMainSync { texts(page,labels) }
        val names=labels.filter { it.startsWith("v0.9.") && it.contains(" · ") }
        check(names.count { it.startsWith("v0.9.29-dev · ") } == 1) {
            "Installed 0.9.27-dev entry missing or duplicated: ${names.take(5)}"
        }
        check(names.count { it.startsWith("v0.9.28-dev · ") } == 1) {
            "Archived 0.9.25-dev entry missing or duplicated: ${names.take(5)}"
        }
        check(names[0].startsWith("v0.9.29-dev") && names[1].startsWith("v0.9.28-dev")) {
            "Version sorting is wrong: ${names.take(5)}"
        }
        inst.runOnMainSync {
            // The current long release entry starts expanded. Close only its
            // *real* accordion header so the archived v25 card is on screen.
            val firstCard=(page as ViewGroup).getChildAt(2) as ViewGroup
            check(firstCard.getChildAt(0).performClick()) { "Cannot collapse current changelog entry" }
            check(firstCard.getChildAt(1).visibility==View.GONE) { "Changelog accordion did not close" }
        }
        inst.waitForIdleSync()
        inst.uiAutomation.takeScreenshot()?.let { frame ->
            File(output,"changelog-v28-v29.png").outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            frame.recycle()
        }
        inst.runOnMainSync { root.removeView(page) }
        return "PASS real Android changelog page: current v0.9.28 and archived v0.9.27 visible exactly once in correct order"
    }
}
