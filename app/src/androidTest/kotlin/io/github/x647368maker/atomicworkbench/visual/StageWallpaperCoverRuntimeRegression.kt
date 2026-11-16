package io.github.x647368maker.atomicworkbench.visual

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.SurfaceControl
import android.view.View
import android.widget.FrameLayout
import io.github.x647368maker.atomicworkbench.systemui.StageGpuMirrorOcclusion
import io.github.x647368maker.atomicworkbench.systemui.StageGpuWallpaperBuffer
import io.github.x647368maker.atomicworkbench.systemui.StageConcurrentNativeTrialPolicy
import io.github.x647368maker.atomicworkbench.systemui.StageWallpaperCoverCache
import java.io.File
import kotlin.math.abs

/** Real SurfaceFlinger test, not a Canvas-only screenshot or an app/task migration test. */
internal class StageWallpaperCoverRuntimeRegression(private val inst:Instrumentation) {
    fun run(activity:Activity,host:FrameLayout,out:File):String {
        val left=Color.rgb(226,183,54)
        val right=Color.rgb(57,178,223)
        val original=Color.rgb(164,50,185)
        val protected=Color.rgb(32,210,116)
        val origin=IntArray(2)
        var width=0
        var height=0
        lateinit var source:Rect
        lateinit var main:Rect
        lateinit var other:Rect
        fun similar(a:Int,b:Int)=abs(Color.red(a)-Color.red(b))<25 &&
            abs(Color.green(a)-Color.green(b))<25 && abs(Color.blue(a)-Color.blue(b))<25
        inst.runOnMainSync {
            host.removeAllViews()
            host.setBackgroundColor(original)
            host.getLocationOnScreen(origin)
            width=host.width;height=host.height
            check(width>=400 && height>=400)
            source=Rect(width/16,height/8,width/3,height/3)
            main=Rect(width*5/8,height/3,width*7/8,height*4/5)
            other=Rect(width/16,height/2,width/3,height*3/4)
            host.addView(View(activity).apply { setBackgroundColor(protected) },
                FrameLayout.LayoutParams(other.width(),other.height()).apply {
                    leftMargin=other.left;topMargin=other.top
                })
        }
        inst.waitForIdleSync()
        val wallpaper=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val buffer=try {
            Canvas(wallpaper).apply {
                drawColor(left)
                val p=android.graphics.Paint().apply { color=right }
                drawRect(width*.5f,0f,width.toFloat(),height.toFloat(),p)
            }
            var preparation="not_reported"
            val evidence=mutableListOf<String>()
            fun prepare(attempt:Int):android.hardware.HardwareBuffer? {
                val result=StageGpuWallpaperBuffer.prepare(wallpaper){ preparation=it }
                evidence.add("attempt="+attempt+" "+preparation)
                try {
                    File(out,"wallpaper-cover-preparation.txt").writeText(evidence.joinToString("\n")+"\n")
                    android.util.Log.i("StageWallpaperCover",evidence.last())
                } catch(error:Exception){ result?.close();throw error }
                return result
            }
            var prepared=prepare(0)
            // Use the same bounded production policy, preserving evidence
            // from the first attempt. Never bypass the real pixel assertion.
            if(prepared==null && StageConcurrentNativeTrialPolicy.retryWallpaperPreparation(0,preparation))
                prepared=prepare(1)
            checkNotNull(prepared) { "Wallpaper buffer render unavailable: "+evidence.joinToString("; ") }
        } finally { wallpaper.recycle() }
        var masks:StageGpuMirrorOcclusion?=null
        val cache=StageWallpaperCoverCache<android.hardware.HardwareBuffer>()
        val key=StageWallpaperCoverCache.Key(width,height,0)
        try {
            inst.runOnMainSync {
                cache.replace(key,buffer)
                check(cache.acquire(key.copy(rotation=1))==null)
                val lease=checkNotNull(cache.acquire(key))
                val overlay=StageGpuMirrorOcclusion.createTextured(lease.resource,lease::close)
                if(overlay==null){lease.close();error("Textured layer creation denied")}
                masks=overlay
                SurfaceControl.Transaction().use { tx ->
                    overlay.append(checkNotNull(host.rootSurfaceControl),tx,source,main,width,height)
                    tx.apply()
                }
                // Invalidate the cache as if a wallpaper change/rotation
                // arrived while the original masks are still attached.
                cache.clear()
                check(!buffer.isClosed && cache.acquire(key)==null)
            }
            fun checkPixels(name:String,shown:Boolean) {
                var ok=false
                repeat(10) {
                    Thread.sleep(64L)
                    val shot=checkNotNull(inst.uiAutomation.takeScreenshot())
                    try {
                        fun read(x:Int,y:Int)=shot.getPixel(x+origin[0],y+origin[1])
                        ok=similar(read(source.centerX(),source.centerY()),if(shown)left else original) &&
                            similar(read(main.centerX(),main.centerY()),if(shown)right else original) &&
                            similar(read(other.centerX(),other.centerY()),protected) &&
                            similar(read(width/2,height/8),original)
                        if(ok || it==9)File(out,"wallpaper-cover-$name.png").outputStream().use {
                            shot.compress(Bitmap.CompressFormat.PNG,100,it)
                        }
                    } finally { shot.recycle() }
                    if(ok)return
                }
                check(ok) { "Textured mask pixel mismatch: $name" }
            }
            checkPixels("visible",true)
            inst.runOnMainSync {
                SurfaceControl.Transaction().use { tx ->
                    checkNotNull(masks).setRevealAlpha(tx,0f)
                    tx.apply()
                }
            }
            checkPixels("revealed",false)
            inst.runOnMainSync {
                val overlay=checkNotNull(masks)
                SurfaceControl.Transaction().use { tx ->
                    overlay.hide(tx)
                    tx.apply()
                }
                overlay.release()
                masks=null
                check(buffer.isClosed)
            }
            checkPixels("released",false)
            return "PASS shared immutable wallpaper texture: physical separate crops, unaffected protected pixels, reveal and release\nWALLPAPER_COVER_PASS"
        } finally {
            inst.runOnMainSync {
                masks?.let { overlay ->
                    try {
                        SurfaceControl.Transaction().use { tx -> overlay.hide(tx);tx.apply() }
                    } finally { overlay.release() }
                }
                cache.clear()
                host.removeAllViews()
            }
            if(!buffer.isClosed)buffer.close()
        }
    }
}
