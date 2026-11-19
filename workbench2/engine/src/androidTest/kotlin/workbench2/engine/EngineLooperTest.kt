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

    // Reducer admission on Android's main Looper. The owners/evidence below are
    // synthetic; actual Binder identity and presentation observations belong to the authorized backend.
    private fun pendingExistingAttach(): Pair<UnifiedCore, Effect> {
        val core = UnifiedCore()
        assertTrue(core.beginPending(fixture, plan))
        for (kind in listOf(Kind.HOST, Kind.INPUT_GATE)) {
            val e = core.next()!!
            assertEquals(kind, e.resource!!.kind)
            assertTrue(core.receipt(Evidence(e, true, Observation.PRESENT, true,
                inputBlockedObserved = true)))
        }
        val e = core.next()!!
        assertEquals(Kind.TASK_LEASE, e.resource!!.kind)
        return core to e
    }

    private fun attachment(e: Effect, actual: Owner, component: String = request.component) =
        Evidence(e, true, Observation.PRESENT, true,
            attach = AttachProof(request.id, "fixture-container", actual, component, true, true))

    @Test fun existingTaskReconfigurationUsesObservedOwner() = owner {
        val candidates = listOf(
            task, task.copy(leashEpoch = 2),
            task.copy(canvas = Canvas(1200, 1800, 420, 2)),
            task.copy(leashEpoch = 2, canvas = Canvas(1200, 1800, 420, 2))
        )
        for (actual in candidates) {
            val (core, attachEffect) = pendingExistingAttach()
            assertTrue(core.receipt(attachment(attachEffect, actual)))
            assertEquals(Phase.STARTING, core.phase)
            assertEquals(actual, core.resources().single { it.kind == Kind.TASK_LEASE }.owner)
            val presentation = core.next()!!
            assertEquals(Kind.BORROWED_PRESENTATION, presentation.resource!!.kind)
            assertEquals(actual, presentation.resource!!.owner)
            // No stale attachment can replace the new presentation while another effect is pending.
            assertFalse(core.receipt(attachment(attachEffect, task)))
            assertEquals(presentation, core.outstanding())
            assertTrue(core.receipt(Evidence(presentation, true, Observation.PRESENT, true)))
            val observer = core.next()!!
            assertEquals(Kind.OBSERVER, observer.resource!!.kind)
            assertTrue(core.receipt(Evidence(observer, true, Observation.PRESENT, true)))
            val visual = core.next()!!
            assertEquals(Op.VERIFY_VISUAL, visual.op)
            val observed = workbench2.motion.Scene(listOf(actual), plan.places, plan.displayEpoch)
            assertEquals(observed, visual.expected)
            assertTrue(core.receipt(Evidence(visual, true, runtimeVerified = true,
                observedScene = observed, blocked = true, inputBlockedObserved = true)))
            val focus = core.next()!!
            assertEquals(Op.VERIFY_FOCUS, focus.op)
            assertTrue(core.receipt(Evidence(focus, true, runtimeVerified = true,
                observedScene = observed, focused = actual, inputBlockedObserved = false)))
            assertEquals(Phase.ACTIVE, core.phase)
            assertEquals(observed, core.current())
            core.invariant()
        }
    }

    private fun assertRejectedAttachment(actual: Owner, component: String = request.component) {
        val (core, attachEffect) = pendingExistingAttach()
        assertTrue(core.receipt(attachment(attachEffect, actual, component)))
        assertEquals(Phase.DRAINING, core.phase)
        assertNull(core.current())
        val obligation = core.resources().single { it.kind == Kind.TASK_LEASE }
        assertNull(obligation.owner)
        assertEquals(request, obligation.request)
        assertEquals("fixture-container", obligation.container)
        val quiesce = core.next()!!
        assertEquals(Op.QUIESCE, quiesce.op)
        assertTrue(core.receipt(Evidence(quiesce, true, runtimeVerified = true,
            blocked = true, inputBlockedObserved = true)))
        val release = core.next()!!
        assertEquals(Op.RELEASE, release.op)
        assertEquals(obligation, release.resource)
        // A rejection is not a return receipt. Keep the lease and both outer resources.
        assertEquals(3, core.resources().size)
        core.invariant()
    }

    @Test fun existingTaskReplacementIdentityIsRejected() = owner {
        assertRejectedAttachment(task.copy(token = 903))
        assertRejectedAttachment(task.copy(task = 43))
        assertRejectedAttachment(task.copy(user = 1))
        assertRejectedAttachment(task, "fixture/.DifferentActivity")
    }

    @Test fun existingTaskStalePresentationIsRejected() = owner {
        assertRejectedAttachment(task.copy(leashEpoch = 0))
        assertRejectedAttachment(task.copy(canvas = task.canvas.copy(contentEpoch = 0)))
        assertRejectedAttachment(task.copy(canvas = task.canvas.copy(width = 1200)))
        assertRejectedAttachment(task.copy(canvas = task.canvas.copy(dpi = 420)))
    }
}
