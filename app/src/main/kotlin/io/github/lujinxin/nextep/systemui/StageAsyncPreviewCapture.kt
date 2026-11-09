package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Capture only pixels off the UI thread; task moves and TextureView access stay on UI. */
internal class StageAsyncPreviewCapture {
    private val ui=Handler(Looper.getMainLooper())
    private val worker=ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(1),
        {task -> Thread(task,"StagePreviewCapture").apply{isDaemon=true}},ThreadPoolExecutor.DiscardOldestPolicy())
    private var generation=0
    private var cancelled:(()->Unit)?=null
    private fun failed(error:Throwable) {
        io.github.x647368maker.atomicworkbench.logging.NeXtepLog.warn("swap_capture","Preview preparation cancelled safely",error)
    }
    private fun cancelSafely(callback:()->Unit){runCatching(callback).onFailure(::failed)}
    fun cancel(){generation++;val c=cancelled;cancelled=null;if(c!=null)cancelSafely(c)}
    fun request(load:()->Bitmap?,valid:()->Boolean,accept:(Bitmap?)->Unit,onCancel:()->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        cancel();val token=++generation;cancelled=onCancel
        val began=SystemClock.uptimeMillis()
        fun finish(bitmap:Bitmap?) {
            if(token!=generation){bitmap?.recycle();return}
            generation++;cancelled=null
            runCatching {
                if(valid())accept(bitmap) else {bitmap?.recycle();cancelSafely(onCancel)}
            }.onFailure {error ->
                if(bitmap?.isRecycled==false)bitmap.recycle()
                cancelSafely(onCancel);failed(error)
            }
        }
        // A slow capture cannot hold a user's exchange or retain a stale screenshot.
        ui.postDelayed({if(token==generation)finish(null)},180)
        worker.execute {
            val bitmap=runCatching(load).getOrNull()
            ui.post {
                io.github.x647368maker.atomicworkbench.logging.NeXtepLog.info("swap_capture","worker_ms=${SystemClock.uptimeMillis()-began} stale=${token!=generation}")
                finish(bitmap)
            }
        }
    }
}
