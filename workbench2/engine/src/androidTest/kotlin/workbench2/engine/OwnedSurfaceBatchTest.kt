package workbench2.engine

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import workbench2.motion.Box
import workbench2.motion.Canvas
import workbench2.motion.Flight
import workbench2.motion.Owner
import workbench2.motion.Pose
import workbench2.motion.Scene
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OwnedSurfaceBatchTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private fun owner(block: () -> Unit) = instr.runOnMainSync(block)
    private var origin = IntArray(2)
    private val a = Owner(101,0,1001,1,Canvas(80,80,160,1))
    private val b = Owner(102,0,1002,1,Canvas(80,80,160,1))
    private val flight = Flight(1, Scene(listOf(a,b),
        listOf(Box(20.0,20.0,180.0,180.0),Box(300.0,20.0,90.0,90.0)),1),1,0.0,0.0)

    private class FixtureBuffer(color: Int) : AutoCloseable {
        private val usage = HardwareBuffer.USAGE_COMPOSER_OVERLAY or
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_CPU_WRITE_OFTEN
        private val reader = ImageReader.newInstance(80,80,PixelFormat.RGBA_8888,2,usage)
        private val image: Image
        val buffer: HardwareBuffer
        init {
            val ready = CountDownLatch(1)
            try {
                reader.setOnImageAvailableListener({ ready.countDown() },Handler(Looper.getMainLooper()))
                val surface = reader.surface
                val canvas = surface.lockCanvas(null)
                try { canvas.drawColor(color) } finally { surface.unlockCanvasAndPost(canvas) }
                check(ready.await(10,TimeUnit.SECONDS))
                image = requireNotNull(reader.acquireNextImage())
                buffer = requireNotNull(image.hardwareBuffer)
            } catch (e: Exception) {
                reader.close()
                throw e
            }
        }
        override fun close() { buffer.close(); image.close(); reader.close() }
    }
    private fun red(c: Int) = Color.red(c)>180 && Color.green(c)<65 && Color.blue(c)<65
    private fun green(c: Int) = Color.green(c)>180 && Color.red(c)<65 && Color.blue(c)<65
    private fun black(c: Int) = Color.red(c)<45 && Color.green(c)<45 && Color.blue(c)<45

    /** Capture composed display only in test, independent of predicted state or commit callback. */
    private fun pixels(label: String, p: Map<Owner, Pose>?, clearPoints: List<Pair<Int,Int>> = emptyList()) {
        val until = SystemClock.elapsedRealtime()+8000
        var last = ""
        while (SystemClock.elapsedRealtime()<until) {
            val shot = instr.uiAutomation.takeScreenshot() ?: error("No screenshot")
            val bmp = if (shot.config==Bitmap.Config.HARDWARE)
                requireNotNull(shot.copy(Bitmap.Config.ARGB_8888,false)) else shot
            fun at(x: Int,y: Int): Int = bmp.getPixel(origin[0]+x,origin[1]+y)
            val colors = p?.let {
                listOf(at(it.getValue(a).content.cx.toInt(),it.getValue(a).content.cy.toInt()),
                    at(it.getValue(b).content.cx.toInt(),it.getValue(b).content.cy.toInt()))
            } ?: emptyList()
            val clears = clearPoints.map { (x,y) -> at(x,y) }
            last = (colors+clears).joinToString(",") { Integer.toHexString(it) }
            val ok = (p==null || (red(colors[0]) && green(colors[1]))) && clears.all(::black)
            if (bmp !== shot) bmp.recycle()
            shot.recycle()
            if (ok) {
                Log.i("WB2SurfaceTest","PIXELS_OK " + label + " " + last)
                return
            }
            SystemClock.sleep(80) // Test-only finite presentation wait, not engine polling.
        }
        fail("Composed pixels not observed for " + label + ": " + last)
    }

    @Test fun ownedDualLayerCommitSwapAndRollback() {
        val activity = instr.startActivitySync(Intent(instr.targetContext, OwnedSurfaceFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OwnedSurfaceFixtureActivity
        lateinit var batch: OwnedSurfaceBatch
        val fixtures = mutableListOf<FixtureBuffer>()
        try {
            fixtures.add(FixtureBuffer(Color.RED))
            fixtures.add(FixtureBuffer(Color.GREEN))
            val buffers = fixtures.map { it.buffer }
            assertTrue("Parent surface ready",activity.ready.await(10,TimeUnit.SECONDS))
            owner {
                val view = activity.surfaceView
                assertTrue(view.width>420 && view.height>220)
                view.getLocationOnScreen(origin)
                batch = OwnedSurfaceBatch(requireNotNull(view.surfaceControl), mapOf(a to buffers[0],b to buffers[1]))
                // Rounded product rendering is not supported by this public geometry primitive.
                try {
                    batch.submit(Flight(2,flight.from,1,24.0,12.0).sample(0.0)) {}
                    fail("Must not silently remove rounded corners")
                } catch (_: IllegalArgumentException) {}
                assertNull(batch.outstanding())
            }
            fun submit(p: Double, serial: Long) {
                val latch = CountDownLatch(1)
                var receipt: OwnedSurfaceBatch.Commit? = null
                owner {
                    assertTrue(batch.submit(flight.sample(p)) { receipt=it; latch.countDown() })
                    assertNotNull(batch.outstanding())
                    assertFalse("At most one outstanding compositor transaction", batch.submit(flight.sample(0.0)) {})
                    assertFalse("Cannot detach before prior settlement",batch.detach {})
                }
                assertTrue("Actual compositor commit",latch.await(10,TimeUnit.SECONDS))
                assertEquals(OwnedSurfaceBatch.Commit(serial,OwnedSurfaceBatch.Kind.GEOMETRY),receipt)
                owner { assertNull(batch.outstanding()) }
                Log.i("WB2SurfaceTest","COMMIT_OK " + serial + " p=" + p)
            }
            submit(0.0,1); pixels("initial",flight.sample(0.0),listOf(250 to 60))
            submit(0.25,2); pixels("interrupted",flight.sample(0.25))
            submit(0.0,3); pixels("rollback",flight.sample(0.0),listOf(250 to 60))
            submit(1.0,4); pixels("swapped",flight.sample(1.0),listOf(250 to 60))
            val released=CountDownLatch(1)
            owner { assertTrue(batch.detach { assertEquals(OwnedSurfaceBatch.Kind.DETACH,it.kind);released.countDown() }) }
            assertTrue("Detach must commit",released.await(10,TimeUnit.SECONDS))
            owner { assertNull(batch.outstanding()); assertFalse(batch.submit(flight.sample(0.0)) {});assertFalse(batch.detach {}) }
            pixels("detached",null,listOf(110 to 110,345 to 65))
        } finally {
            // Closing the test Activity destroys the independently owned parent.
            owner { activity.finish() }
            fixtures.forEach(FixtureBuffer::close)
        }
    }
}
