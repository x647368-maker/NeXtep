package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * API37 HWUI/SF integration probe. Two *separate attached live ViewRoot* windows
 * are mirrored into one full-display host and animated in ONE GPU Transaction.
 * Changing an original View's pixels MUST propagate to its mirror afterwards.
 * This is a compositor primitive test; it is NOT proof of ColorOS task-leash
 * availability or real third-party task lifecycle continuity.
 */
internal class StageLiveMirrorPrimitiveRegression(private val instrumentation: Instrumentation) {
    fun run(activity: Activity, host: FrameLayout, out: File): String {
        if (Build.VERSION.SDK_INT < 29) return "SKIP native mirror below API29"
        return runCatching { probe(activity,host,out) }.getOrElse { error ->
            if (Build.VERSION.SDK_INT >= 37) throw error
            "SKIP API${Build.VERSION.SDK_INT} unprivileged mirror reflection: " +
                "${error.javaClass.simpleName} ${error.message?.take(160)}"
        }
    }

    private fun probe(activity: Activity, host: FrameLayout, out: File): String {
        val green=View(activity).apply {setBackgroundColor(Color.rgb(33,194,110))}
        val purple=View(activity).apply {setBackgroundColor(Color.rgb(166,75,205))}
        var first:PopupWindow?=null
        var second:PopupWindow?=null
        var m1:SurfaceControl?=null
        var m2:SurfaceControl?=null
        try {
            instrumentation.runOnMainSync {
                host.removeAllViews()
                host.setBackgroundColor(Color.rgb(20,32,70))
                first=PopupWindow(green,130,170,false).apply {
                    isTouchable=false
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    showAtLocation(host,Gravity.TOP or Gravity.LEFT,20,80)
                }
                second=PopupWindow(purple,130,170,false).apply {
                    isTouchable=false
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    showAtLocation(host,Gravity.TOP or Gravity.LEFT,190,80)
                }
            }
            instrumentation.waitForIdleSync()
            val initialCommitted=CountDownLatch(1)
            instrumentation.runOnMainSync {
                fun mirror(view:View):SurfaceControl {
                    val attached=view.rootSurfaceControl ?: error("No ViewRoot compositor")
                    val public=attached.javaClass.methods.firstOrNull {
                        it.name=="createMirror" && it.parameterCount==0
                    }?.let {runCatching {it.invoke(attached) as? SurfaceControl}.getOrNull()}
                    if (public?.isValid==true) return public
                    public?.release()
                    // API35 fallback. Only SystemUI normally bypasses the
                    // non-SDK reflection restrictions; SKIP is explicit here.
                    val accessor=View::class.java.getDeclaredMethod("getViewRootImpl")
                        .apply {isAccessible=true}
                    val root=accessor.invoke(view) ?: error("No ViewRootImpl")
                    val field=generateSequence(root.javaClass as Class<*>?) {it.superclass}
                        .mapNotNull {runCatching {it.getDeclaredField("mSurfaceControl")}.getOrNull()}
                        .firstOrNull() ?: error("No SurfaceControl field")
                    val original=field.apply {isAccessible=true}.get(root) as? SurfaceControl
                        ?: error("No live SurfaceControl")
                    val method=SurfaceControl::class.java.getDeclaredMethod(
                        "mirrorSurface",SurfaceControl::class.java
                    ).apply {isAccessible=true}
                    return (method.invoke(null,original) as? SurfaceControl)
                        ?.takeIf {it.isValid} ?: error("AOSP mirrorSurface unavailable")
                }
                m1=mirror(green)
                m2=mirror(purple)
                val root=host.rootSurfaceControl ?: error("Missing target compositor root")
                val a=root.buildReparentTransaction(m1!!) ?: error("First reparent failed")
                val b=root.buildReparentTransaction(m2!!) ?: error("Second reparent failed")
                try {a.merge(b)} finally {b.close()}
                try {
                    a.setCrop(m1!!,Rect(0,0,130,170))
                    a.setCrop(m2!!,Rect(0,0,130,170))
                    a.setLayer(m1!!,65000)
                    a.setLayer(m2!!,65001)
                    a.setPosition(m1!!,400f,330f)
                    a.setPosition(m2!!,610f,330f)
                    a.setVisibility(m1!!,true)
                    a.setVisibility(m2!!,true)
                    a.addTransactionCommittedListener(activity.mainExecutor) {
                        initialCommitted.countDown()
                    }
                    a.apply()
                } finally {a.close()}
            }
            check(initialCommitted.await(3,TimeUnit.SECONDS)) {
                "SurfaceFlinger did not commit both live mirrored root layers"
            }
            Thread.sleep(220L)

            fun matches(c:Int,r:Int,g:Int,b:Int):Boolean =
                abs(Color.red(c)-r)<28 && abs(Color.green(c)-g)<28 && abs(Color.blue(c)-b)<28
            fun screenshot() = instrumentation.uiAutomation.takeScreenshot()
                ?: error("No physical compositor screenshot")
            val firstShot=screenshot()
            val xy=IntArray(2)
            instrumentation.runOnMainSync {host.getLocationOnScreen(xy)}
            val left=firstShot.getPixel(xy[0]+465,xy[1]+400)
            val right=firstShot.getPixel(xy[0]+675,xy[1]+400)
            check(matches(left,33,194,110) && matches(right,166,75,205)) {
                "Two separate real window mirrors not present at the requested GPU positions"
            }
            firstShot.recycle()

            // A live source changes AFTER the mirror has been created. A static
            // screenshot would stay green: a real mirrored buffer becomes blue.
            instrumentation.runOnMainSync {green.setBackgroundColor(Color.rgb(39,116,239))}
            instrumentation.waitForIdleSync()
            Thread.sleep(170L)
            val updated=screenshot()
            val live=updated.getPixel(xy[0]+465,xy[1]+400)
            check(matches(live,39,116,239)) {
                "Mirrored content did not update with the original attached window"
            }
            updated.recycle()

            // One atomic transaction moves BOTH opposite directions. Crop and
            // buffer identity are not changed, only position and uniform scale.
            instrumentation.runOnMainSync {
                SurfaceControl.Transaction().use {tx ->
                    tx.setScale(m1!!,1.3f,1.3f)
                    tx.setScale(m2!!,.8f,.8f)
                    tx.setPosition(m1!!,690f,620f)
                    tx.setPosition(m2!!,420f,620f)
                    tx.apply()
                }
            }
            Thread.sleep(170L)
            val moved=screenshot()
            val grow=moved.getPixel(xy[0]+755,xy[1]+700)
            val shrink=moved.getPixel(xy[0]+472,xy[1]+686)
            check(matches(grow,39,116,239) && matches(shrink,166,75,205)) {
                "Two GPU mirrors did not move/scale in the same committed frame"
            }
            File(out,"native-api37-dual-live-surface-mirrors.png").outputStream().use {
                moved.compress(Bitmap.CompressFormat.PNG,100,it)
            }
            moved.recycle()
            return "PASS API${Build.VERSION.SDK_INT} SurfaceFlinger live dual-root mirror: both moved in one GPU transaction and updated producer pixels WITHOUT bitmap capture"
        } finally {
            instrumentation.runOnMainSync {
                runCatching {
                    SurfaceControl.Transaction().use {tx ->
                        m1?.takeIf {it.isValid}?.let {tx.setVisibility(it,false)}
                        m2?.takeIf {it.isValid}?.let {tx.setVisibility(it,false)}
                        tx.apply()
                    }
                }
                runCatching {m1?.release()}
                runCatching {m2?.release()}
                first?.dismiss()
                second?.dismiss()
            }
        }
    }
}
