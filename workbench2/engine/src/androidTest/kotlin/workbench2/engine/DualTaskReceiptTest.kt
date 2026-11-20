package workbench2.engine

import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import workbench2.motion.*
import workbench2.nativebridge.*
import workbench2.unified.*

/** Synthetic native receipts transported by real Android Handler/Looper. Never OEM proof. */
class DualTaskReceiptTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val a = Owner(11,0,101,1,Canvas(1080,2400,440,1))
    private val b = Owner(12,0,102,1,Canvas(1080,2400,440,1))
    private val scene = Scene(listOf(a,b),listOf(Box(0.0,0.0,540.0,1200.0),Box(600.0,0.0,180.0,400.0)),1)
    private val target = scene.swap(1)
    private val plan = StartPlan(listOf(LaunchRequest(1,0,"fixture/.A",a), LaunchRequest(2,0,"fixture/.B",b)),scene.places,1)
    private val capability = Capability("fixture-fw","fixture-host",true,true,true,true,true,true,true)
    private fun owner(block:()->Unit) = instr.runOnMainSync { block() }
    private class Port : NativePort {
        var sent: Effect? = null
        var complete: ((NativeSnapshot)->Unit)? = null
        var count = 0
        override fun dispatch(effect:Effect,poses:Map<Owner,Pose>?,complete:(NativeSnapshot)->Unit) {
            assertSame(Looper.getMainLooper(),Looper.myLooper())
            if(effect.op==Op.FRAME)assertEquals(2,poses!!.size) else assertNull(poses)
            sent=effect;this.complete=complete;count++
        }
        override fun stopLocalCallbacks() { assertSame(Looper.getMainLooper(),Looper.myLooper()) }
    }
    private inner class Session {
        lateinit var core: UnifiedCore
        lateinit var port: Port
        lateinit var bridge: ReceiptBridge
        init { owner {
            core=UnifiedCore();port=Port()
            bridge=ReceiptBridge(core,OwnerLooperPort(port,Looper.getMainLooper()),"fixture-fw","fixture-host","fixture-instance")
            assertTrue(core.beginPending(capability,plan))
        } }
        fun issue(op:Op,kind:Kind?=null):Effect {
            owner {
                assertTrue(bridge.pump())
                assertEquals(op,port.sent!!.op)
                assertEquals(kind,port.sent!!.resource?.kind)
                assertFalse(bridge.pump())
            }
            return port.sent!!
        }
        fun send(observation:Observation=Observation.UNKNOWN, blocked:Boolean?=true, observed:Scene?=null, focus:Owner?=null,
                 ready:Boolean=false, verified:Boolean=true, settled:Boolean=true, attach:AttachProof?=null) {
            port.complete!!(NativeSnapshot("fixture-fw","fixture-host","fixture-instance",observed,focus,blocked,
                observation,settled,verified,ready,true,attach))
            instr.waitForIdleSync()
        }
        fun proof(e:Effect,returned:Boolean=false):AttachProof {
            val r=e.resource!!.request!!
            return AttachProof(r.id,"container-"+r.id,if(returned)null else r.existing,r.component,true,true,
                if(returned)r.existing?.let { TaskReturnProof(it.task,it.user,it.token,true,true,noPendingDestructiveCleanup=true) } else null)
        }
        fun activate() {
            for (kind in listOf(Kind.HOST,Kind.INPUT_GATE,Kind.TASK_LEASE,Kind.BORROWED_PRESENTATION,
                Kind.TASK_LEASE,Kind.BORROWED_PRESENTATION,Kind.OBSERVER)) {
                val e=issue(Op.ACQUIRE,kind)
                send(Observation.PRESENT,attach=if(kind==Kind.TASK_LEASE)proof(e) else null)
            }
            issue(Op.VERIFY_VISUAL); send(observed=scene,ready=true)
            issue(Op.VERIFY_FOCUS); send(blocked=false,observed=scene,focus=a,ready=true)
            owner { assertEquals(Phase.ACTIVE,core.phase);assertEquals(scene,core.current());core.invariant() }
        }
        fun firstFrame():Effect {
            owner { assertTrue(core.request(b)) }
            issue(Op.BLOCK);send()
            val f=issue(Op.FRAME);assertEquals(0.0,f.progress!!,0.0)
            return f
        }
        fun finalFrame() {
            firstFrame();send()
            owner { assertTrue(core.frame(1.0)) }
            val f=issue(Op.FRAME);assertEquals(1.0,f.progress!!,0.0)
            send()
        }
        fun cleanup() {
            owner { assertEquals(Phase.DRAINING,core.phase);assertEquals(7,core.resources().size) }
            issue(Op.QUIESCE);send()
            for(kind in listOf(Kind.OBSERVER,Kind.BORROWED_PRESENTATION,Kind.TASK_LEASE,
                Kind.BORROWED_PRESENTATION,Kind.TASK_LEASE,Kind.INPUT_GATE,Kind.HOST)){
                val e=issue(Op.RELEASE,kind)
                send(Observation.ABSENT,blocked=kind!=Kind.INPUT_GATE&&kind!=Kind.HOST,
                    attach=if(kind==Kind.TASK_LEASE)proof(e,true) else null)
            }
            owner { assertFalse(bridge.pump());assertEquals(Phase.CLOSED,core.phase);assertTrue(core.resources().isEmpty());core.invariant() }
        }
    }
    @Test fun swapCoalescesFramesAndPublishesOnlyAfterVisualAndFocus() {
        val s=Session();s.activate();val frozen=requireNotNull(s.firstFrame().expected)
        owner {
            assertTrue(s.core.frame(0.25));assertTrue(s.core.frame(0.75));assertFalse(s.core.frame(0.5))
            assertFalse(s.bridge.pump());assertEquals(scene,s.core.current())
            try { (frozen.owners as MutableList<Owner>).clear();fail("Expected owners must be immutable") }
            catch (_: UnsupportedOperationException) {}
            try { (frozen.places as MutableList<Box>).clear();fail("Expected places must be immutable") }
            catch (_: UnsupportedOperationException) {}
        }
        s.send()
        val middle=s.issue(Op.FRAME);assertEquals(0.75,middle.progress!!,0.0);assertSame(frozen,middle.expected)
        owner { assertTrue(s.core.frame(1.0));assertFalse(s.bridge.pump()) }
        s.send()
        val end=s.issue(Op.FRAME);assertEquals(1.0,end.progress!!,0.0);assertSame(frozen,end.expected)
        s.send()
        owner { assertEquals(scene,s.core.current()) }
        assertSame(frozen,s.issue(Op.VERIFY_VISUAL).expected);s.send(observed=target,ready=true)
        owner { assertEquals(scene,s.core.current());assertEquals(Phase.VERIFY_FOCUS,s.core.phase) }
        assertSame(frozen,s.issue(Op.VERIFY_FOCUS).expected);s.send(blocked=false,observed=target,focus=b,ready=true)
        owner {
            assertEquals(Phase.ACTIVE,s.core.phase);assertEquals(target,s.core.current());assertSame(frozen,s.core.current())
            val count=s.port.count
            repeat(20){assertFalse(s.bridge.pump())}
            assertEquals(count,s.port.count);s.core.invariant();s.bridge.stop()
        }
        s.cleanup()
    }
    @Test fun lostOrUnknownInputDuringFrameDrainsWithoutPublishingSwap() {
        for(blocked in listOf(false,null)){
            val s=Session();s.activate();s.firstFrame();s.send(blocked=blocked)
            owner { assertEquals(scene,s.core.current());assertFalse(s.core.frame(1.0)) }
            s.cleanup()
        }
    }
    @Test fun wrongVisualOrFocusNeverPublishesSwap() {
        val s=Session();s.activate();s.finalFrame()
        s.issue(Op.VERIFY_VISUAL);s.send(observed=scene,ready=true)
        owner { assertEquals(scene,s.core.current()) };s.cleanup()
        for(blocked in listOf(false,null)){
            val t=Session();t.activate();t.finalFrame();t.issue(Op.VERIFY_VISUAL);t.send(observed=target,ready=true)
            t.issue(Op.VERIFY_FOCUS);t.send(blocked=blocked,observed=target,focus=if(blocked==false)a else b,ready=true)
            owner { assertEquals(scene,t.core.current()) };t.cleanup()
        }
    }
    @Test fun cancelledFrameRetainsEveryLeaseUntilLateTerminalReceipt() {
        val s=Session();s.activate();val f=s.firstFrame()
        val late=s.port.complete!!
        owner { assertTrue(s.core.frame(1.0));s.bridge.stop();assertFalse(s.bridge.pump());assertEquals(f,s.core.outstanding()) }
        s.send(settled=false)
        owner { assertEquals(Phase.RECOVERY,s.core.phase);assertEquals(7,s.core.resources().size);assertFalse(s.bridge.pump());assertFalse(s.core.retryCleanup()) }
        s.send()
        owner { assertEquals(scene,s.core.current());assertEquals(Phase.DRAINING,s.core.phase) }
        s.issue(Op.QUIESCE)
        late(NativeSnapshot("fixture-fw","fixture-host","fixture-instance",null,null,true,Observation.UNKNOWN,true,true,false,true))
        instr.waitForIdleSync()
        owner { assertEquals(Op.QUIESCE,s.core.outstanding()!!.op) }
        s.send()
        for(kind in listOf(Kind.OBSERVER,Kind.BORROWED_PRESENTATION,Kind.TASK_LEASE,
            Kind.BORROWED_PRESENTATION,Kind.TASK_LEASE,Kind.INPUT_GATE,Kind.HOST)){
            val e=s.issue(Op.RELEASE,kind)
            s.send(Observation.ABSENT,blocked=kind!=Kind.INPUT_GATE&&kind!=Kind.HOST,
                attach=if(kind==Kind.TASK_LEASE)s.proof(e,true) else null)
        }
        owner { assertFalse(s.bridge.pump());assertEquals(Phase.CLOSED,s.core.phase);assertTrue(s.core.resources().isEmpty()) }
    }
    @Test fun unverifiedResourceAndUnknownGateCannotSettleAtReducer() = owner {
        val core=UnifiedCore();assertTrue(core.beginPending(capability,plan))
        val h=core.next()!!
        core.receipt(Evidence(h,true,Observation.PRESENT,false))
        assertEquals(Phase.RECOVERY,core.phase);assertEquals(h,core.outstanding());assertTrue(core.resources().isEmpty())
        core.receipt(Evidence(h,true,Observation.PRESENT,true))
        val q=core.next()!!;assertEquals(Op.QUIESCE,q.op)
        core.receipt(Evidence(q,true,runtimeVerified=true,blocked=true,inputBlockedObserved=true))
        val release=core.next()!!;assertEquals(Kind.HOST,release.resource!!.kind)
        core.receipt(Evidence(release,true,Observation.ABSENT,false))
        assertEquals(release,core.outstanding());assertEquals(1,core.resources().size)
        core.receipt(Evidence(release,true,Observation.ABSENT,true));assertNull(core.next());assertEquals(Phase.CLOSED,core.phase)
        val s=UnifiedCore();assertTrue(s.beginPending(capability,plan))
        val host=s.next()!!;s.receipt(Evidence(host,true,Observation.PRESENT,true))
        val gate=s.next()!!;s.receipt(Evidence(gate,true,Observation.PRESENT,true,blocked=true))
        assertEquals(Phase.RECOVERY,s.phase);assertEquals(gate,s.outstanding());assertEquals(1,s.resources().size)
        assertNull(s.next());assertFalse(s.retryCleanup())
    }
    @Test fun inputGateReleaseRequiresExplicitReopenedEvidence() {
        val s=Session();s.activate()
        owner { s.bridge.stop() };s.issue(Op.QUIESCE);s.send()
        for(kind in listOf(Kind.OBSERVER,Kind.BORROWED_PRESENTATION,Kind.TASK_LEASE,
            Kind.BORROWED_PRESENTATION,Kind.TASK_LEASE)){
            val e=s.issue(Op.RELEASE,kind);s.send(Observation.ABSENT,attach=if(kind==Kind.TASK_LEASE)s.proof(e,true) else null)
        }
        val gate=s.issue(Op.RELEASE,Kind.INPUT_GATE)
        s.send(Observation.ABSENT,blocked=null)
        owner { assertEquals(Phase.RECOVERY,s.core.phase);assertEquals(gate,s.core.outstanding());assertEquals(2,s.core.resources().size);assertFalse(s.bridge.pump()) }
        s.send(Observation.ABSENT,blocked=false)
        s.issue(Op.RELEASE,Kind.HOST);s.send(Observation.ABSENT,blocked=false)
        owner { assertFalse(s.bridge.pump());assertEquals(Phase.CLOSED,s.core.phase);s.core.invariant() }
    }
}
