package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.HardwareBufferRenderer
import android.graphics.Paint
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.os.Looper
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Experimental one-shot conversion of a caller-owned wallpaper cache.
 * No screen/app capture. No worker created here. Never wait on the UI lane.
 * A successful result is immutable and owned by the caller, not a cover.
 */
internal object StageGpuWallpaperBuffer {
    private val USAGE=HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_COMPOSER_OVERLAY
    private val REQUIRED=HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
        HardwareBuffer.USAGE_COMPOSER_OVERLAY
    // Full resolution or refusal; do not resample the wallpaper or real tasks.
    private const val MAX_PIXELS=8_000_000L

    fun usable(buffer:HardwareBuffer):Boolean = runCatching {
        !buffer.isClosed && buffer.format==HardwareBuffer.RGBA_8888 &&
            buffer.width>1 && buffer.height>1 &&
            buffer.width.toLong()*buffer.height<=MAX_PIXELS &&
            (buffer.usage and REQUIRED)==REQUIRED
    }.getOrDefault(false)

    fun prepare(bitmap:Bitmap):HardwareBuffer? {
        if(Looper.myLooper()==Looper.getMainLooper() || bitmap.isRecycled ||
            bitmap.width<2 || bitmap.height<2 ||
            bitmap.width.toLong()*bitmap.height>MAX_PIXELS)return null
        var buffer:HardwareBuffer?=null
        var renderer:HardwareBufferRenderer?=null
        var node:RenderNode?=null
        var success=false
        val completion=CompletableFuture<HardwareBufferRenderer.RenderResult>()
        try {
            val target=HardwareBuffer.create(bitmap.width,bitmap.height,HardwareBuffer.RGBA_8888,1,USAGE)
            buffer=target
            if(!usable(target))return null
            val content=RenderNode("AtomicSwapCachedWallpaper")
            node=content
            content.setPosition(0,0,bitmap.width,bitmap.height)
            val canvas=content.beginRecording()
            // An alpha-bearing input must not expose the stationary app.
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG))
            content.endRecording()
            val hw=HardwareBufferRenderer(target)
            renderer=hw
            hw.setContentRoot(content)
            hw.obtainRenderRequest().draw(Executor { it.run() }) { result ->
                // A timeout/cancel never leaks a subsequently delivered fence.
                if(!completion.complete(result))runCatching { result.fence.close() }
            }
            val result=completion.get(120L,TimeUnit.MILLISECONDS)
            result.fence.use { fence ->
                if(result.status!=HardwareBufferRenderer.RenderResult.SUCCESS ||
                    !fence.await(Duration.ofMillis(120)))return null
            }
            success=true
            return target
        } catch(error:Exception) {
            if(error is InterruptedException)Thread.currentThread().interrupt()
            if(!completion.cancel(false))runCatching { completion.getNow(null)?.fence?.close() }
            return null
        } finally {
            runCatching { renderer?.close() }
            runCatching { node?.discardDisplayList() }
            if(!success)runCatching { buffer?.close() }
        }
    }
}
