package workbench2.engine

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.CountDownLatch

/** Test-only parent. No application task content, capture, or injection. */
class OwnedSurfaceFixtureActivity : Activity(), SurfaceHolder.Callback {
    lateinit var surfaceView: SurfaceView
        private set
    val ready = CountDownLatch(1)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
    }
    override fun surfaceCreated(holder: SurfaceHolder) {
        val c = holder.lockCanvas()
        try { c.drawColor(Color.BLACK) } finally { holder.unlockCanvasAndPost(c) }
        ready.countDown()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {}
}
