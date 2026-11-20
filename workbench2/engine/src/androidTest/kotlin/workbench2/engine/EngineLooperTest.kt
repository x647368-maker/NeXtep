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
        var failStop = false
        var failAttachDispatch = false
        override fun dispatch(effect: Effect, poses: Map<Owner, Pose>?, complete: (NativeSnapshot) -> Unit) {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            sent = effect
            completion = complete
            // Model a dispatch exception after the backend accepted this callback.
            if (failAttachDispatch && effect.op == Op.ACQUIRE && effect.resource?.kind == Kind.TASK_LEASE)
                error("fixture dispatch after partial submission")
        }
        override fun stopLocalCallbacks() {
            assertSame(Looper.getMainLooper(), Looper.myLooper())
            if (failStop) error("fixture local stop failure")
        }
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

    @Test fun metadataOnlyAdmissionAndLegacyScenePathCannotBorrowTasks() = owner {
        val core = UnifiedCore()
        val raw = Port()
        val bridge = ReceiptBridge(core, OwnerLooperPort(raw, Looper.getMainLooper()),
            "fixture-fw", "fixture-host", "fixture-instance")
        // Real R2 confirms host and symbols, but not authorization, embedding, input or release.
        val metadataOnly = fixture.copy(authorized = false, embedding = false, input = false, release = false)
        assertFalse(core.beginPending(metadataOnly, plan))
        for (incomplete in listOf(fixture.copy(embedding = false), fixture.copy(input = false),
            fixture.copy(authorized = false), fixture.copy(release = false))) {
            assertFalse(core.beginPending(incomplete, plan))
        }
        assertFalse(bridge.pump())
        assertNull(raw.sent)
        assertEquals(Phase.CLOSED, core.phase)
        assertTrue(core.resources().isEmpty())
        // Do not permit the old public Scene admission which omitted LaunchRequest/return proof.
        assertFalse(UnifiedCore::class.java.declaredMethods.any { it.name == "begin" })
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
        raw.completion!!(receipt(Observation.ABSENT, attach = proof.copy(actualOwner = null, returnedTask = TaskReturnProof(task.task, task.user, task.token, true, true, noPendingDestructiveCleanup = true))))
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

    @Test fun uncertainAttachReceiptRequestsCancellationOnceAndRetainsSettlement() = owner {
        val proof = AttachProof(request.id, "fixture-container", task, request.component, true, true)
        val uncertain = listOf(
            receipt(Observation.PRESENT, settled = false, attach = proof),
            receipt(Observation.PRESENT, attach = proof, instance = "different"),
            receipt(Observation.UNKNOWN, attach = proof)
        )
        for (bad in uncertain) {
            val core = UnifiedCore()
            val raw = Port()
            val bridge = ReceiptBridge(core, OwnerLooperPort(raw, Looper.getMainLooper()),
                "fixture-fw", "fixture-host", "fixture-instance")
            assertTrue(core.beginPending(fixture, plan))
            for (kind in listOf(Kind.HOST, Kind.INPUT_GATE)) {
                assertTrue(bridge.pump())
                assertEquals(kind, raw.sent!!.resource!!.kind)
                raw.completion!!(receipt(Observation.PRESENT))
            }
            assertTrue(bridge.pump())
            val pending = raw.sent!!
            assertEquals(Kind.TASK_LEASE, pending.resource!!.kind)
            val complete = raw.completion!!
            // No explicit stop or timeout: the invalid/uncertain receipt itself
            // must cancel the original request, without treating cancellation as settlement.
            complete(bad)
            assertEquals(Phase.RECOVERY, core.phase)
            assertEquals(pending, core.outstanding())
            assertEquals(1, raw.cancellations)
            assertEquals(2, core.resources().size)
            complete(bad)
            bridge.stop()
            assertEquals(1, raw.cancellations)
            assertFalse(bridge.pump())
            assertFalse(core.retryCleanup())
            core.invariant()
            // A late authenticated terminal attach can still establish the return obligation.
            complete(receipt(Observation.PRESENT, attach = proof))
            assertEquals(Phase.DRAINING, core.phase)
            assertEquals(3, core.resources().size)
            assertTrue(bridge.pump())
            assertEquals(Op.QUIESCE, raw.sent!!.op)
            raw.completion!!(receipt(Observation.UNKNOWN))
            assertTrue(bridge.pump())
            assertEquals(Kind.TASK_LEASE, raw.sent!!.resource!!.kind)
            raw.completion!!(receipt(Observation.ABSENT, attach = proof.copy(actualOwner = null,
                returnedTask = TaskReturnProof(task.task, task.user, task.token, true, true, noPendingDestructiveCleanup = true))))
            assertTrue(bridge.pump())
            assertEquals(Kind.INPUT_GATE, raw.sent!!.resource!!.kind)
            raw.completion!!(receipt(Observation.ABSENT, inputBlocked = false))
            assertTrue(bridge.pump())
            assertEquals(Kind.HOST, raw.sent!!.resource!!.kind)
            raw.completion!!(receipt(Observation.ABSENT))
            assertFalse(bridge.pump())
            assertEquals(Phase.CLOSED, core.phase)
            assertTrue(core.resources().isEmpty())
            assertEquals(1, raw.cancellations)
            core.invariant()
        }
    }

    @Test fun localStopFailureCannotSuppressCancellationOrBypassQuiescence() = owner {
        val proof = AttachProof(request.id, "fixture-container", task, request.component, true, true)
        for (trigger in listOf("stop", "timeout", "dispatch", "receipt")) {
            val core = UnifiedCore()
            val raw = Port().apply { failStop = true; failAttachDispatch = trigger == "dispatch" }
            val bridge = ReceiptBridge(core, OwnerLooperPort(raw, Looper.getMainLooper()),
                "fixture-fw", "fixture-host", "fixture-instance")
            assertTrue(core.beginPending(fixture, plan))
            for (kind in listOf(Kind.HOST, Kind.INPUT_GATE)) {
                assertTrue(bridge.pump())
                assertEquals(kind, raw.sent!!.resource!!.kind)
                raw.completion!!(receipt(Observation.PRESENT))
            }
            assertTrue(bridge.pump())
            val pending = raw.sent!!
            val complete = raw.completion!!
            assertEquals(Kind.TASK_LEASE, pending.resource!!.kind)
            when (trigger) {
                "stop" -> bridge.stop()
                "timeout" -> assertTrue(bridge.timeout(pending))
                "receipt" -> complete(receipt(Observation.UNKNOWN))
            }
            assertEquals(1, raw.cancellations)
            assertEquals(pending, core.outstanding())
            assertEquals(2, core.resources().size)
            assertFalse(bridge.pump())
            bridge.stop()
            assertEquals(1, raw.cancellations)
            // Keep the original callback live despite local stop failure.
            complete(receipt(Observation.PRESENT, attach = proof))
            assertEquals(Phase.DRAINING, core.phase)
            assertEquals(3, core.resources().size)
            assertTrue(bridge.pump())
            assertEquals(Op.QUIESCE, raw.sent!!.op)
            raw.completion!!(receipt(Observation.UNKNOWN).copy(callbacksStopped = false))
            assertEquals(Phase.RECOVERY, core.phase)
            assertEquals(3, core.resources().size)
            assertFalse(bridge.pump())
            assertTrue(core.retryCleanup())
            assertTrue(bridge.pump())
            assertEquals(Op.QUIESCE, raw.sent!!.op)
            raw.failStop = false
            raw.completion!!(receipt(Observation.UNKNOWN))
            assertTrue(bridge.pump())
            assertEquals(Kind.TASK_LEASE, raw.sent!!.resource!!.kind)
            raw.completion!!(receipt(Observation.ABSENT, attach = proof.copy(actualOwner = null,
                returnedTask = TaskReturnProof(task.task, task.user, task.token, true, true, noPendingDestructiveCleanup = true))))
            assertTrue(bridge.pump())
            assertEquals(Kind.INPUT_GATE, raw.sent!!.resource!!.kind)
            raw.completion!!(receipt(Observation.ABSENT, inputBlocked = false))
            assertTrue(bridge.pump())
            assertEquals(Kind.HOST, raw.sent!!.resource!!.kind)
            raw.completion!!(receipt(Observation.ABSENT))
            assertFalse(bridge.pump())
            assertEquals(Phase.CLOSED, core.phase)
            assertTrue(core.resources().isEmpty())
            assertEquals(1, raw.cancellations)
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

    private fun returnProofForTask() = TaskReturnProof(task.task, task.user, task.token, true, true, noPendingDestructiveCleanup = true)

    @Test fun borrowedTaskAbsenceDuringAcquireRequiresReturnProof() = owner {
        val (core, e) = pendingExistingAttach()
        val empty = AttachProof(request.id, null, null, null, true, true)
        val valid = returnProofForTask()
        val invalid = listOf(null, valid.copy(token = 903), valid.copy(task = 43),
            valid.copy(user = 1), valid.copy(originalOwnershipRestored = false),
            valid.copy(noPendingReattach = false),
            valid.copy(noPendingDestructiveCleanup = false),
            TaskReturnProof(task.task, task.user, task.token, true, true))
        for (returned in invalid) {
            assertTrue(core.receipt(Evidence(e, true, Observation.ABSENT, true,
                attach = empty.copy(returnedTask = returned))))
            assertEquals(Phase.RECOVERY, core.phase)
            assertEquals(e, core.outstanding())
            assertEquals(2, core.resources().size)
            assertNull(core.next())
            assertFalse(core.retryCleanup())
            core.invariant()
        }
        assertTrue(core.receipt(Evidence(e, true, Observation.ABSENT, true,
            attach = empty.copy(returnedTask = valid))))
        assertEquals(Phase.DRAINING, core.phase)
        assertEquals(Op.QUIESCE, core.next()!!.op)
        core.invariant()
    }

    @Test fun borrowedTaskReleaseWaitsForOriginalOwnershipAndTerminalReparent() = owner {
        val (core, acquire) = pendingExistingAttach()
        assertTrue(core.receipt(attachment(acquire, task)))
        core.stop()
        val q = core.next()!!
        assertEquals(Op.QUIESCE, q.op)
        core.receipt(Evidence(q, true, runtimeVerified = true, blocked = true, inputBlockedObserved = true))
        val release = core.next()!!
        assertEquals(Kind.TASK_LEASE, release.resource!!.kind)
        val valid = returnProofForTask()
        val empty = AttachProof(request.id, "fixture-container", null, null, true, true)
        val bad = listOf(empty, empty.copy(returnedTask = valid.copy(token = 903)),
            empty.copy(returnedTask = valid.copy(task = 43)), empty.copy(returnedTask = valid.copy(user = 1)),
            empty.copy(returnedTask = valid.copy(originalOwnershipRestored = false)),
            empty.copy(returnedTask = valid.copy(noPendingReattach = false)),
            empty.copy(returnedTask = valid.copy(noPendingDestructiveCleanup = false)),
            empty.copy(returnedTask = TaskReturnProof(task.task, task.user, task.token, true, true)),
            empty.copy(container = "other-container", returnedTask = valid),
            empty.copy(terminal = false, returnedTask = valid),
            empty.copy(correlated = false, returnedTask = valid),
            empty.copy(requestId = 99, returnedTask = valid),
            empty.copy(actualOwner = task, returnedTask = valid))
        for (proof in bad) {
            assertTrue(core.receipt(Evidence(release, true, Observation.ABSENT, true, attach = proof)))
            assertEquals(Phase.RECOVERY, core.phase)
            assertEquals(release, core.outstanding())
            assertEquals(3, core.resources().size)
            assertNull(core.next())
            assertFalse(core.retryCleanup())
            core.invariant()
        }
        assertFalse(core.receipt(attachment(acquire, task)))
        assertTrue(core.receipt(Evidence(release, true, Observation.ABSENT, false,
            attach = empty.copy(returnedTask = valid))))
        assertEquals(release, core.outstanding())
        assertTrue(core.receipt(Evidence(release, true, Observation.ABSENT, true,
            attach = empty.copy(returnedTask = valid))))
        assertEquals(Phase.DRAINING, core.phase)
        assertEquals(2, core.resources().size)
        val gate = core.next()!!
        assertEquals(Kind.INPUT_GATE, gate.resource!!.kind)
        core.receipt(Evidence(gate, true, Observation.ABSENT, true, inputBlockedObserved = false))
        val host = core.next()!!
        assertEquals(Kind.HOST, host.resource!!.kind)
        core.receipt(Evidence(host, true, Observation.ABSENT, true))
        assertNull(core.next())
        assertEquals(Phase.CLOSED, core.phase)
        core.invariant()
    }
}
