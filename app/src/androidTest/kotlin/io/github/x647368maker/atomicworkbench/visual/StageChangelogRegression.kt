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
        check(names.count { it.startsWith("v0.9.26-dev · ") } == 1) {
            "Installed 0.9.26-dev entry missing or duplicated: ${names.take(5)}"
        }
        check(names.count { it.startsWith("v0.9.25-dev · ") } == 1) {
            "Archived 0.9.25-dev entry missing or duplicated: ${names.take(5)}"
        }
        check(names[0].startsWith("v0.9.26-dev") && names[1].startsWith("v0.9.25-dev")) {
            "Version sorting is wrong: ${names.take(5)}"
        }
        inst.uiAutomation.takeScreenshot()?.let { frame ->
            File(output,"changelog-v25-v26.png").outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            frame.recycle()
        }
        inst.runOnMainSync { root.removeView(page) }
        return "PASS real Android changelog page: current v0.9.26 and archived v0.9.25 visible exactly once in correct order"
    }
}
