package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import kotlin.math.abs

/**
 * Validates transfer of temporary capture buffers to HWUI without a second
 * full-resolution ARGB copy. This is NOT a fake app-task test: the live task
 * continuity is separately checked by PhasedPreviewRegression.
 */
internal class StagePreviewBitmapRetentionRegression(
    private val instrumentation: Instrumentation,
) {
    fun run(activity: Activity, host: FrameLayout): String {
        val software = Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(32,175,210))
        }
        val copy=TaskSurfaceCompat.retainPreviewForAcceleratedCanvas(software)
            ?: error("Software fallback did not produce a preview")
        check(software.isRecycled) { "Software capture retained an obsolete extra copy" }
        check(!copy.isRecycled && copy.config == Bitmap.Config.ARGB_8888)
        check(copy.getPixel(12,12) == Color.rgb(32,175,210))
        copy.recycle()

        val source=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(70,188,135))
        }
        val hardware=runCatching { source.copy(Bitmap.Config.HARDWARE,false) }.getOrNull()
        source.recycle()
        if(hardware==null) return "PASS software preview fallback; HW bitmap unavailable in this emulator"
        val retained=TaskSurfaceCompat.retainPreviewForAcceleratedCanvas(hardware)
        check(retained === hardware && !hardware.isRecycled) {
            "GPU capture was copied to CPU or released before HWUI used it"
        }
        val view=object:View(activity) {
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            override fun onDraw(canvas:Canvas) {
                check(canvas.isHardwareAccelerated) { "Hardware bitmap must only be drawn by HWUI" }
                canvas.drawBitmap(hardware,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)
            }
        }
        try {
            instrumentation.runOnMainSync {
                host.removeAllViews()
                host.addView(view,FrameLayout.LayoutParams(240,240).apply {
                    leftMargin=40;topMargin=80
                })
            }
            instrumentation.waitForIdleSync()
            val shot=instrumentation.uiAutomation.takeScreenshot()
                ?: error("HWUI capture unavailable for GPU bitmap test")
            val xy=IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(xy) }
            val rgb=shot.getPixel(xy[0]+120,xy[1]+120)
            shot.recycle()
            check(abs(Color.red(rgb)-70)<30 &&
                abs(Color.green(rgb)-188)<30 &&
                abs(Color.blue(rgb)-135)<30) {
                "GPU-backed preview did not render on hardware canvas"
            }
        } finally {
            instrumentation.runOnMainSync { host.removeView(view) }
            instrumentation.waitForIdleSync()
            hardware.recycle()
        }
        return "PASS GPU preview retained identity, displayed in HWUI without software copy; software fallback safely cloned"
    }
}
