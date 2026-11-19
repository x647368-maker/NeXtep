package workbench2.engine

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import workbench2.motion.Owner
import workbench2.motion.Pose
import workbench2.motion.Scene
import workbench2.nativebridge.NativePort
import workbench2.nativebridge.NativeSnapshot
import workbench2.nativebridge.ReceiptBridge
import workbench2.unified.Capability
import workbench2.unified.Effect
import workbench2.unified.Phase
import workbench2.unified.StartPlan
import workbench2.unified.UnifiedCore

/**
 * Owner-Looper event loop for the unified core. Uses the Android frame clock,
 * never a fixed-rate timer. No task backend or permission acquisition here.
 * A backend must authenticate its own capabilities and independent receipts.
 * Keep the owner Looper alive until all return obligations are settled.
 *
 * Only after the exact initial frame settles does elapsed frame time advance.
 * While a receipt is outstanding there is no outstanding animation callback.
 * A slow backend therefore skips intermediate progress, never queues a burst.
 * No callback or polling remains after idle, stop or uncertain settlement.
 */
class AndroidFrameDriver(
    delegate: NativePort,
    firmware: String,
    hostVersion: String,
    hostInstance: String,
    durationMillis: Long
) {
    init { require(durationMillis in 1..10000) }
    private val durationNanos = durationMillis * 1000000L
    private val owner = requireNotNull(Looper.myLooper())
    private val handler = Handler(owner)
    private val choreographer = Choreographer.getInstance()
    private val core = UnifiedCore()
    private val transport = OwnerLooperPort(delegate, owner)
    private var framePosted = false
    private var advancePosted = false
    private var startNanos: Long? = null
    private val advanceEvent = Runnable {
        advancePosted = false
        advance()
    }
    private val frameEvent = Choreographer.FrameCallback { time ->
        framePosted = false
        if (core.phase == Phase.FLYING && core.outstanding() == null) {
            val start = checkNotNull(startNanos)
            // frameTimeNanos uses the same monotonic time base as nanoTime.
            val p = (time - start).coerceAtLeast(0L).toDouble() / durationNanos
            core.frame(p.coerceAtMost(1.0))
            advance()
        } else cancelClock()
    }
    private val port = object : NativePort {
        override fun dispatch(effect: Effect, poses: Map<Owner, Pose>?, complete: (NativeSnapshot) -> Unit) {
            transport.dispatch(effect, poses) { snapshot ->
                val before = core.outstanding()
                val phase = core.phase
                complete(snapshot)
                // A stale or duplicate receipt must not create another wakeup.
                if (core.outstanding() != before || core.phase != phase) kick()
            }
        }
        override fun stopLocalCallbacks() {
            cancelClock()
            transport.stopLocalCallbacks()
        }
        override fun requestAttachCancellation(effect: Effect) = transport.requestAttachCancellation(effect)
    }
    private val bridge = ReceiptBridge(core, port, firmware, hostVersion, hostInstance)

    private fun checkOwner() = check(Looper.myLooper() === owner)
    fun phase(): Phase { checkOwner(); return core.phase }
    fun current(): Scene? { checkOwner(); return core.current() }
    fun outstanding(): Effect? { checkOwner(); return core.outstanding() }
    internal fun frameScheduled(): Boolean { checkOwner(); return framePosted }

    fun start(capability: Capability, plan: StartPlan): Boolean {
        checkOwner()
        val accepted = core.beginPending(capability, plan)
        if (accepted) kick()
        return accepted
    }
    fun exchange(target: Owner): Boolean {
        checkOwner()
        val accepted = core.request(target)
        if (accepted) kick()
        return accepted
    }
    fun stop() {
        checkOwner()
        if (core.phase == Phase.CLOSED) return
        cancelClock()
        bridge.stop()
        kick()
    }
    /** Called only for the exact outstanding effect by an independent timeout source. */
    fun timeout(effect: Effect): Boolean {
        checkOwner()
        val accepted = bridge.timeout(effect)
        if (accepted) cancelClock()
        return accepted
    }
    fun retryCleanup(): Boolean {
        checkOwner()
        val accepted = core.retryCleanup()
        if (accepted) kick()
        return accepted
    }

    private fun cancelClock() {
        if (framePosted) choreographer.removeFrameCallback(frameEvent)
        framePosted = false
        startNanos = null
    }
    private fun kick() {
        checkOwner()
        if (advancePosted || core.outstanding() != null) return
        if (core.phase in setOf(Phase.CLOSED, Phase.ACTIVE, Phase.RECOVERY)) {
            cancelClock()
            return
        }
        advancePosted = true
        if (!handler.post(advanceEvent)) advancePosted = false
    }
    private fun advance() {
        checkOwner()
        if (core.phase != Phase.FLYING) cancelClock()
        if (core.outstanding() != null) return
        // At most one dispatch per owner event; synchronous receipts post the
        // next advance rather than recursively entering the reducer.
        if (bridge.pump()) return
        if (core.phase == Phase.FLYING && !framePosted) {
            if (startNanos == null) startNanos = System.nanoTime()
            framePosted = true
            choreographer.postFrameCallback(frameEvent)
        }
    }
}
