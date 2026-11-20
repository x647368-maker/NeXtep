package workbench2.engine

import android.os.Handler
import android.os.Looper
import workbench2.motion.Owner
import workbench2.motion.Pose
import workbench2.nativebridge.NativePort
import workbench2.nativebridge.NativeSnapshot
import workbench2.unified.Effect
import java.util.Collections

/**
 * Android transport boundary for the existing ReceiptBridge.
 * Use a caller-owned, already running Looper; this creates no worker, timer,
 * global hook, or native task implementation. Backend still must independently
 * authenticate snapshots. The host must retain this Looper and settlement path
 * until every return obligation is resolved.
 *
 * If the Looper quits, posting a receipt fails closed: no new receipt is
 * invented. Host-death recovery is a separate, still unverified OEM contract.
 * The delegate must hand over a stable snapshot (no concurrent mutation).
 */
class OwnerLooperPort(
    private val delegate: NativePort,
    private val owner: Looper
) : NativePort {
    init { require(Looper.myLooper() === owner) }
    private val handler = Handler(owner)
    private fun checkOwner() { check(Looper.myLooper() === owner) }

    override fun dispatch(effect: Effect, poses: Map<Owner, Pose>?, complete: (NativeSnapshot) -> Unit) {
        checkOwner()
        delegate.dispatch(effect, poses) { snapshot ->
            // Receipts without a scene contain only immutable values. Avoid a second
            // snapshot allocation on the usual frame acknowledgement path.
            val stable = snapshot.scene?.let { scene ->
                snapshot.copy(scene = scene.copy(
                    owners = Collections.unmodifiableList(ArrayList(scene.owners)),
                    places = Collections.unmodifiableList(ArrayList(scene.places))
                ))
            } ?: snapshot
            if (Looper.myLooper() === owner) complete(stable)
            else handler.post { complete(stable) }
            // A rejected post cannot justify native release or production admission.
        }
    }

    override fun stopLocalCallbacks() {
        checkOwner()
        delegate.stopLocalCallbacks()
        // Do not remove queued receipt callbacks; cancellation is not settlement.
    }

    override fun requestAttachCancellation(effect: Effect) {
        checkOwner()
        delegate.requestAttachCancellation(effect)
    }
}
