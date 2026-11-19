package workbench2.engine

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import workbench2.motion.Box
import workbench2.motion.Canvas
import workbench2.motion.Owner
import workbench2.motion.Pose
import workbench2.nativebridge.NativePort
import workbench2.nativebridge.NativeSnapshot
import workbench2.nativebridge.ReceiptBridge
import workbench2.unified.*

/** Real Android Looper transport, with synthetic receipts only in androidTest. */
class EngineLooperTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private fun owner(block: () -> Unit) = instr.runOnMainSync(block)
    private val task = Owner(42, 0, 902, 1, Canvas(1080, 2400, 440, 1))
    private val request = LaunchRequest(1, 0, "fixture/.Activity", task)
    private val plan = StartPlan(listOf(request), listOf(Box(0.0, 0.0, 540.0, 1200.0)), 1)
    // Never a production capability. Fixture-only, no installed OEM backend.
    private val fixture = Capability("fixture-fw", "fixture-host", true, true, true, true, true, true, true)
    private class Port : NativePort {
        var sent: Effect? = null
        var completion: ((NativeSnapshot) -> Unit)? = null
        var cancellations = 0
        override fun dispatch(effect: Effect, poses: Map<Owner, Pose>?, complete: (NativeSnapshot) -> Unit) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            sent = effect
            completion = complete
        }
        override fun stopLocalCallbacks() { assertSame(Looper.getMainLooper(), Looper.myLooper()) }
        override fun requestAttachCancellation(effect: Effect) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            assertEquals(sent, effect)
            cancellations++
        }
    }

    private fun receipt(observation: Observation, settled: Boolean = true, attach: AttachProof? = null,
                        instance: String = "fixture-instance", inputBlocked: Boolean? = true) =
        NativeSnapshot("fixture-fw", "fixture-host", instance, null, null, inputBlocked, observation,
            settled, true, false, true, attach)

    @Test fun noCapabilityMeansNoDispatch() = owner {
        val core = UnifiedCore()
        val raw = Port()
        val bridge = ReceiptBridge(core, OwnerLooperPort(raw, Looper.getMainLooper()),
            "fixture-fw", "fixture-host", "fixture-instance")
        assertFalse(core.beginPending(fixture.copy(authorized = false), plan))
        assertFalse(core.beginPending(fixture.copy(release = false), plan))
        assertFalse(bridge.pump())
        assertNull(raw.sent)
        assertEquals(Phase.CLOSED, core.phase)
        core.invariant()
    }

    @Test fun cancellationAndLateReceiptRetainReturnBeforeHostRelease() {
        lateinit var core: UnifiedCore
        lateinit var raw: Port
        lateinit var bridge: ReceiptBridge
        owner {
            core = UnifiedCore()
            raw = Port()
            bridge = ReceiptBridge(core, OwnerLooperPort(raw, Looper.getMainLooper()),
                "fixture-fw", "fixture-host", "fixture-instance")
            assertTrue(core.beginPending(fixture, plan))
            assertTrue(bridge.pump())
            assertEquals(Kind.HOST, raw.sent!!.resource!!.kind)
        }
        // Instrumentation thread is not the owner's main Looper.
        assertNotSame(Looper.getMainLooper(), Looper.myLooper())
        val staleHost = raw.completion!!
        staleHost(receipt(Observation.PRESENT))
        instr.waitForIdleSync()
        owner {
            assertTrue(bridge.pump())
            assertEquals(Kind.INPUT_GATE, raw.sent!!.resource!!.kind)
        }
        raw.completion!!(receipt(Observation.PRESENT))
        instr.waitForIdleSync()
        owner {
            assertTrue(bridge.pump())
            assertEquals(Kind.TASK_LEASE, raw.sent!!.resource!!.kind)
            bridge.stop()
            bridge.stop()
            assertEquals(1, raw.cancellations)
            assertFalse(bridge.pump())
            assertNull(core.current())
        }
        val attach = raw.completion!!
        val proof = AttachProof(1, "fixture-container", task, request.component, true, true)
        staleHost(receipt(Observation.PRESENT))
        attach(receipt(Observation.PRESENT, settled = false, attach = proof))
        instr.waitForIdleSync()
        owner {
            assertEquals(Phase.RECOVERY, core.phase)
            assertEquals(Kind.TASK_LEASE, core.outstanding()!!.resource!!.kind)
            assertFalse(bridge.pump())
        }
        attach(receipt(Observation.PRESENT, attach = proof))
        instr.waitForIdleSync()
        owner {
            assertEquals(Phase.DRAINING, core.phase)
            assertTrue(bridge.pump())
            assertEquals(Op.QUIESCE, raw.sent!!.op)
        }
        raw.completion!!(receipt(Observation.UNKNOWN))
        instr.waitForIdleSync()
        owner {
            assertTrue(bridge.pump())
            assertEquals(Kind.TASK_LEASE, raw.sent!!.resource!!.kind)
        }
        raw.completion!!(receipt(Observation.ABSENT, attach = proof.copy(actualOwner = null)))
        instr.waitForIdleSync()
        owner {
            assertTrue(bridge.pump())
            assertEquals(Kind.INPUT_GATE, raw.sent!!.resource!!.kind)
        }
        raw.completion!!(receipt(Observation.ABSENT, inputBlocked = false))
        instr.waitForIdleSync()
        owner {
            assertTrue(bridge.pump())
            assertEquals(Kind.HOST, raw.sent!!.resource!!.kind)
        }
        raw.completion!!(receipt(Observation.ABSENT))
        instr.waitForIdleSync()
        owner {
            assertFalse(bridge.pump())
            assertEquals(Phase.CLOSED, core.phase)
            assertTrue(core.resources().isEmpty())
            core.invariant()
        }
    }

    @Test fun mismatchedHostReceiptCannotSettleOutstandingEffect() {
        lateinit var core: UnifiedCore
        lateinit var raw: Port
        lateinit var bridge: ReceiptBridge
        owner {
            core = UnifiedCore()
            raw = Port()
            bridge = ReceiptBridge(core, OwnerLooperPort(raw, Looper.getMainLooper()),
                "fixture-fw", "fixture-host", "fixture-instance")
            assertTrue(core.beginPending(fixture, plan))
            assertTrue(bridge.pump())
        }
        val e = raw.sent!!
        raw.completion!!(receipt(Observation.PRESENT, instance = "different"))
        instr.waitForIdleSync()
        owner {
            assertEquals(e, core.outstanding())
            assertEquals(Phase.RECOVERY, core.phase)
            assertTrue(core.resources().isEmpty())
            assertFalse(bridge.pump())
            assertFalse(core.retryCleanup())
            core.invariant()
        }
    }
}
