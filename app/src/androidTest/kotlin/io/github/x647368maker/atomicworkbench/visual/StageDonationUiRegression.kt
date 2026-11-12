package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import io.github.x647368maker.atomicworkbench.config.StageDonationPage
import java.io.File

/**
 * Real-device regression for the previously blank donation image areas.
 * Verifies both QR previews render black modules (not empty white rectangles).
 * No payment app is launched and no gallery write/payment is performed.
 */
internal class StageDonationUiRegression(private val instrumentation: Instrumentation) {
    fun run(activity: Activity, root: FrameLayout, out: File): String {
        val previews = mutableListOf<ImageView>()
        val screen = ScrollView(activity)
        instrumentation.runOnMainSync {
            val page = StageDonationPage.create(activity as AppCompatActivity)
            screen.addView(page, ViewGroup.LayoutParams(-1, -2))
            root.removeAllViews()
            root.setBackgroundColor(Color.rgb(245, 245, 247))
            root.addView(screen, FrameLayout.LayoutParams(-1, -1))
            fun collect(view: View) {
                if (view is ImageView && view.contentDescription?.contains("收款码") == true)
                    previews.add(view)
                if (view is ViewGroup)
                    for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            collect(page)
            check(previews.size == 2) { "Expected two donation QR previews, found ${previews.size}" }
            previews.forEach { preview ->
                val bitmap = (preview.drawable as? BitmapDrawable)?.bitmap
                    ?: error("${preview.contentDescription}: no bitmap drawable")
                check(!bitmap.isRecycled && bitmap.isMutable) {
                    "${preview.contentDescription}: bitmap must stay allocated and mutable"
                }
                check(bitmap.width >= 400 && bitmap.height >= 400) {
                    "${preview.contentDescription}: QR dimensions too small"
                }
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val darkCount = pixels.count { Color.red(it) < 75 && Color.green(it) < 75 && Color.blue(it) < 75 }
                check(darkCount > pixels.size / 10) {
                    "${preview.contentDescription}: QR matrix missing (all-white regression)"
                }
            }
        }
        instrumentation.waitForIdleSync()
        val top = instrumentation.uiAutomation.takeScreenshot() ?: error("No donation screenshot")
        File(out, "donation-wechat-qr.png").outputStream().use {
            top.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        top.recycle()
        instrumentation.runOnMainSync { screen.fullScroll(View.FOCUS_DOWN) }
        instrumentation.waitForIdleSync()
        val bottom = instrumentation.uiAutomation.takeScreenshot() ?: error("No Alipay QR screenshot")
        File(out, "donation-alipay-qr.png").outputStream().use {
            bottom.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bottom.recycle()
        instrumentation.runOnMainSync {
            root.removeAllViews()
            root.setBackgroundColor(Color.rgb(20, 32, 70))
        }
        return "PASS donation UI: two mutable QR bitmaps with visible black modules; Android screenshots saved"
    }
}
