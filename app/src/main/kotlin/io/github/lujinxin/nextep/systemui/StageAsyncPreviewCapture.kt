package io.github.x647368maker.atomicworkbench.systemui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/** Capture only pixels off the UI thread; task moves and TextureView access stay on UI. */
internal class StageAsyncPreviewCapture {
    private val ui=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { task -> Thread(task,"StagePreviewCapture").apply{isDaemon=true} }
    private var generation=0
    private var cancelled:(()->Unit)?=null
    fun cancel(){generation++;val c=cancelled;cancelled=null;c?.invoke()}
    fun request(load:()->Bitmap?,valid:()->Boolean,accept:(Bitmap?)->Unit,onCancel:()->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        cancel();val token=++generation;cancelled=onCancel
        val began=SystemClock.uptimeMillis()
        fun finish(bitmap:Bitmap?) {
            if(token!=generation){bitmap?.recycle();return}
            generation++;cancelled=null
            if(valid())accept(bitmap) else {bitmap?.recycle();onCancel()}
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
