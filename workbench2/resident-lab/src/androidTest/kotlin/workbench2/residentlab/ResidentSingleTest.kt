package workbench2.residentlab

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import workbench2.motion.Box
import workbench2.motion.Canvas
import workbench2.motion.Pose
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ResidentSingleTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val width = 480
    private val height = 720
    private val dpi = 160
    private fun main(block: () -> Unit) = instr.runOnMainSync(block)

    private class Output(val view: View, val ready: CountDownLatch) {
        var surface: Surface? = null
        var ownedSurface = false
        var generation = 0
        var destroyed = 0
    }
    private fun output(host: HostActivity, texture: Boolean): Output {
        val ready = CountDownLatch(1)
        val out = if (texture) {
            val v = TextureView(host)
            Output(v, ready).also { o ->
                v.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                        t.setDefaultBufferSize(width, height)
                        o.surface = Surface(t)
                        o.ownedSurface = true
                        o.generation++
                        ready.countDown()
                    }
                    override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {
                        t.setDefaultBufferSize(width, height)
                    }
                    override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                        o.destroyed++
                        return true
                    }
                    override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                }
            }
        } else {
            val v = SurfaceView(host)
            Output(v, ready).also { o ->
                v.holder.setFixedSize(width, height)
                v.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {}
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) {
                        if (w == width && height == this@ResidentSingleTest.height && o.surface == null) {
                            o.surface = h.surface
                            o.generation++
                            ready.countDown()
                        }
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) { o.destroyed++ }
                })
            }
        }
        val pose = Pose.fit(Box(0.0, 0.0, width.toDouble(), height.toDouble()),
            Canvas(width, height, dpi, 1L), 0.0)
        val size = pose.content
        check(size.w.toInt() == width && size.h.toInt() == height)
        check(host.root.width >= width && host.root.height >= height)
        host.root.addView(out.view, FrameLayout.LayoutParams(width, height))
        return out
    }

    /** Test-only independent composed-screen observation; never a displayed substitute. */
    private fun pixel(out: Output, expected: Int) {
        val xy = IntArray(2)
        main { out.view.getLocationOnScreen(xy) }
        val end = SystemClock.elapsedRealtime() + 8000
        var last = 0
        while (SystemClock.elapsedRealtime() < end) {
            val shot = requireNotNull(instr.uiAutomation.takeScreenshot())
            val b = if (shot.config == Bitmap.Config.HARDWARE)
                requireNotNull(shot.copy(Bitmap.Config.ARGB_8888, false)) else shot
            try {
                last = b.getPixel(xy[0] + width/2, xy[1] + height/2)
                if (kotlin.math.abs(Color.red(last)-Color.red(expected)) < 40 &&
                    kotlin.math.abs(Color.green(last)-Color.green(expected)) < 40 &&
                    kotlin.math.abs(Color.blue(last)-Color.blue(expected)) < 40) return
            } finally { if (b !== shot) b.recycle(); shot.recycle() }
            SystemClock.sleep(80)
        }
        fail("Actual composed color missing: expected=$expected last=$last")
    }

    /** Official own-UID instrumentation, never in application runtime or user input path. */
    private fun touch(display: Int, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                arrayOf(MotionEvent.PointerProperties().apply {
                    id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                }), arrayOf(MotionEvent.PointerCoords().apply {
                    this.x = x; this.y = y; pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f
                    size = 1f
                }), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, display, 0,
                MotionEvent.CLASSIFICATION_NONE)
            val pointer = requireNotNull(event) { "Cannot construct test touch event for display $display" }
            try { instr.sendPointerSync(pointer) } finally { pointer.recycle() }
        }
    }

    private fun runCase(host: HostActivity, texture: Boolean): Float {
        val mode = if (texture) "texture" else "surface"
        var cleanupOut: Output? = null
        lateinit var out: Output
        var display: VirtualDisplay? = null
        var target: TargetActivity? = null
        val dm = requireNotNull(host.getSystemService(DisplayManager::class.java))
        val am = requireNotNull(host.getSystemService(ActivityManager::class.java))
        try {
            main { out = output(host, texture); cleanupOut = out }
            assertTrue("Output ready", out.ready.await(10, TimeUnit.SECONDS))
            val surface = requireNotNull(out.surface)
            assertTrue(surface.isValid)
            main {
                display = dm.createVirtualDisplay("wb2-own-$mode", width, height, dpi, surface, 0)
            }
            val d = requireNotNull(display).display
            val id = d.displayId
            assertNotEquals(Display.DEFAULT_DISPLAY, id)
            assertTrue(d.flags and Display.FLAG_PRIVATE != 0)
            val rate = d.refreshRate
            assertTrue(rate > 0 && rate.isFinite())
            val intent = Intent(instr.targetContext, TargetActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            assertTrue("Own activity launch allowed",
                am.isActivityStartAllowedOnDisplay(host, id, intent))
            val t = instr.startActivitySync(intent,
                ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()) as TargetActivity
            target = t
            val taskId = t.taskId
            assertNotEquals(host.taskId, taskId)
            main {
                assertEquals(id, t.display?.displayId)
                assertEquals(Display.DEFAULT_DISPLAY, host.display?.displayId)
                assertTrue(t.content.width > 0 && t.content.height > 0)
            }
            pixel(out, Color.RED)
            val loc = IntArray(2)
            var x = 0f; var y = 0f
            main {
                t.content.getLocationOnScreen(loc)
                x = loc[0] + t.content.width / 2f
                y = loc[1] + t.content.height / 2f
            }
            touch(id,x,y)
            assertEquals(1,t.content.downs)
            assertEquals(1,t.content.touches)
            pixel(out, Color.GREEN)
            val durations = mutableListOf<Long>()
            var lost = 0
            val listener = Window.OnFrameMetricsAvailableListener { _, m, dropped ->
                val n = m.getMetric(FrameMetrics.TOTAL_DURATION)
                if (n >= 0) durations.add(n)
                lost += dropped
            }
            try {
                main {
                    t.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
                    t.content.startWorkload()
                }
                assertTrue("120 actual target draws",t.content.workloadFinished.await(20,TimeUnit.SECONDS))
                instr.waitForIdleSync()
            } finally { main { t.window.removeOnFrameMetricsAvailableListener(listener) } }
            assertEquals(120,t.content.rendered)
            pixel(out, Color.GREEN)
            main {
                assertEquals(taskId,t.taskId)
                assertEquals(id,t.display?.displayId)
                assertEquals(1,out.generation)
                assertEquals(0,out.destroyed)
                assertTrue(surface.isValid)
            }
            assertTrue("Target FrameMetrics samples", durations.isNotEmpty())
            val sorted = durations.sorted()
            fun p(q: Double): Long = sorted[(kotlin.math.ceil(q*sorted.size).toInt()-1).coerceIn(0,sorted.lastIndex)]
            Log.i("WB2Resident", "MEASURE mode=$mode task=$taskId display=$id rate=$rate draws=120 frameSamples=${sorted.size} callbackDrops=$lost p50ns=${p(.5)} p95ns=${p(.95)} p99ns=${p(.99)}")
            main { t.finishAndRemoveTask() }
            assertTrue("New disposable target destroyed",t.destroyed.await(10,TimeUnit.SECONDS))
            val deadline = SystemClock.elapsedRealtime()+8000
            while(am.appTasks.any { it.taskInfo.taskId == taskId } && SystemClock.elapsedRealtime()<deadline)
                SystemClock.sleep(80)
            assertFalse("Own task still registered",am.appTasks.any { it.taskInfo.taskId == taskId })
            main { requireNotNull(display).release(); display = null }
            val until = SystemClock.elapsedRealtime()+5000
            while(dm.getDisplay(id)!=null && SystemClock.elapsedRealtime()<until) SystemClock.sleep(80)
            assertNull("Private display still allocated",dm.getDisplay(id))
            Log.i("WB2Resident","SINGLE_OK mode=$mode task=$taskId display=$id rate=$rate")
            return rate
        } finally {
            main {
                target?.let { if (!it.isDestroyed) it.finishAndRemoveTask() }
                // On failure only: all tasks/displays are new same-UID disposable fixtures.
                display?.release()
                cleanupOut?.let { o ->
                    if (o.ownedSurface) o.surface?.release()
                    host.root.removeView(o.view)
                }
            }
        }
    }

    @Test fun ownSingleTaskSameCanvasTextureThenSurface() {
        assertTrue(instr.targetContext.packageManager.hasSystemFeature(
            PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS))
        val host = instr.startActivitySync(Intent(instr.targetContext, HostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HostActivity
        try {
            val a = runCase(host,true)
            val b = runCase(host,false)
            assertEquals("Equal actual refresh rate, not a production refresh claim", a,b,0.01f)
            Log.i("WB2Resident","PAIR_SINGLE_OK same_canvas=${width}x$height dpi=$dpi rate=$a")
        } finally { main { host.finishAndRemoveTask() } }
    }
}
