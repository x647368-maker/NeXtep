package workbench2.engine

import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import workbench2.motion.*
import workbench2.nativebridge.*
import workbench2.unified.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android Choreographer/Looper. Task receipts are synthetic test data only. */
class AndroidFrameDriverTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private fun owner(block: () -> Unit) = instr.runOnMainSync { block() }
    private val a = Owner(1,0,101,1,Canvas(100,240,160,1))
    private val b = Owner(2,0,102,1,Canvas(100,240,160,1))
    private val scene = Scene(listOf(a,b),listOf(Box(0.0,0.0,200.0,480.0),Box(240.0,0.0,80.0,192.0)),1)
    private val plan = StartPlan(listOf(LaunchRequest(1,0,"fixture/.A",a),LaunchRequest(2,0,"fixture/.B",b)),scene.places,1)
    private val capability = Capability("fixture","fixture",true,true,true,true,true,true,true)
    private inner class Port : NativePort {
        val started = CountDownLatch(1)
        val stalled = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val swapped = CountDownLatch(1)
        val samples = mutableListOf<Double>()
        var calls = 0
        var late: (() -> Unit)? = null
        override fun dispatch(e: Effect, poses: Map<Owner,Pose>?, complete: (NativeSnapshot)->Unit) {
            assertSame(Looper.getMainLooper(),Looper.myLooper())
            calls++
            val resource=e.resource
            val acquire=e.op==Op.ACQUIRE
            val release=e.op==Op.RELEASE
            val blocked=e.op!=Op.VERIFY_FOCUS && !(release && resource?.kind==Kind.INPUT_GATE)
            val observed=if(e.op==Op.VERIFY_VISUAL || e.op==Op.VERIFY_FOCUS)e.expected else null
            val attach=if(resource?.kind==Kind.TASK_LEASE)resource.request!!.let {
                AttachProof(it.id,"container-"+it.id,if(release)null else it.existing,it.component,true,true)
            } else null
            val receipt=NativeSnapshot("fixture","fixture","fixture-instance",observed,
                if(e.op==Op.VERIFY_FOCUS)observed!!.owners.first() else null,blocked,
                if(acquire)Observation.PRESENT else if(release)Observation.ABSENT else Observation.UNKNOWN,
                true,true,true,true,attach)
            if(e.op==Op.FRAME) {
                val progress=e.progress!!
                assertEquals(2,poses!!.size)
                samples.add(progress)
                if(progress>0.0 && late==null && stalled.count>0) {
                    late={complete(receipt)}
                    stalled.countDown()
                    return
                }
            }
            complete(receipt)
            if(e.op==Op.VERIFY_FOCUS) {
                if(observed==scene)started.countDown() else swapped.countDown()
            }
            if(release && resource?.kind==Kind.HOST)closed.countDown()
        }
        override fun stopLocalCallbacks() { assertSame(Looper.getMainLooper(),Looper.myLooper()) }
    }
    private fun await(latch: CountDownLatch) = assertTrue("Owner event did not arrive",latch.await(10,TimeUnit.SECONDS))
    @Test fun frameClockWaitsForReceiptAndStopsAtSettledEndpoint() {
        val port=Port()
        lateinit var driver:AndroidFrameDriver
        owner {
            driver=AndroidFrameDriver(port,"fixture","fixture","fixture-instance",600)
            assertFalse(driver.start(capability.copy(authorized=false),plan))
            assertFalse(driver.frameScheduled())
            assertTrue(driver.start(capability,plan))
        }
        await(port.started)
        owner {
            assertEquals(Phase.ACTIVE,driver.phase())
            assertTrue(driver.exchange(b))
        }
        await(port.stalled)
        var count=0
        owner {
            assertFalse(driver.frameScheduled())
            assertEquals(Op.FRAME,driver.outstanding()!!.op)
            assertEquals(0.0,port.samples.first(),0.0)
            assertEquals(2,port.samples.size)
            count=port.calls
        }
        SystemClock.sleep(750) // Test-only wait; exceeds configured animation duration.
        owner { assertEquals(count,port.calls);assertFalse(driver.frameScheduled()) }
        port.late!!.invoke() // Instrumentation thread delivery through real OwnerLooperPort.
        await(port.swapped)
        owner {
            assertEquals(scene.swap(1),driver.current())
            assertEquals(Phase.ACTIVE,driver.phase())
            assertFalse(driver.frameScheduled())
            // A heavily loaded emulator may have skipped straight to the endpoint.
            assertEquals(if(port.samples[1]==1.0)2 else 3,port.samples.size)
            assertEquals(1.0,port.samples.last(),0.0)
            count=port.calls
        }
        SystemClock.sleep(100)
        owner { assertEquals(count,port.calls);driver.stop() }
        await(port.closed)
        instr.waitForIdleSync()
        owner { assertEquals(Phase.CLOSED,driver.phase());assertFalse(driver.frameScheduled()) }
    }
    @Test fun stopDuringStalledFrameRetainsReceiptAndNeverRestartsClock() {
        val port=Port()
        lateinit var driver:AndroidFrameDriver
        owner { driver=AndroidFrameDriver(port,"fixture","fixture","fixture-instance",600);assertTrue(driver.start(capability,plan)) }
        await(port.started)
        owner { assertTrue(driver.exchange(b)) }
        await(port.stalled)
        var count=0
        owner {
            driver.stop()
            assertEquals(Phase.DRAINING,driver.phase())
            assertEquals(Op.FRAME,driver.outstanding()!!.op)
            assertFalse(driver.frameScheduled())
            count=port.calls
        }
        SystemClock.sleep(100)
        owner { assertEquals(count,port.calls) }
        port.late!!.invoke()
        await(port.closed)
        instr.waitForIdleSync()
        owner {
            assertEquals(Phase.CLOSED,driver.phase())
            assertEquals(2,port.samples.size)
            assertFalse(driver.frameScheduled())
            assertEquals(1L,port.swapped.count)
        }
    }
}
