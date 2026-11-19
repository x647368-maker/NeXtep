package workbench2.engine

import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import workbench2.motion.Owner
import workbench2.motion.Pose
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Public Android geometry primitive for layers created by this instance.
 * Never accepts an external task leash, owns no task or input, and never
 * translates a compositor commit into native task/focus/presentation proof.
 *
 * Caller owns the parent and buffers, must keep them valid until detach
 * commits, and must keep the owner Looper alive. No timer, thread or polling.
 * The public API cannot set corner radius here: nonzero radius is refused,
 * not silently rounded down. This is not the product's rendering backend.
 */
class OwnedSurfaceBatch(
    private val parent: SurfaceControl,
    contents: Map<Owner, HardwareBuffer>
) {
    companion object {
        private val REQUIRED_USAGE = HardwareBuffer.USAGE_COMPOSER_OVERLAY or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
    }
    enum class Kind { GEOMETRY, DETACH }
    data class Commit(val serial: Long, val kind: Kind)
    private val owner = requireNotNull(Looper.myLooper())
    private val handler = Handler(owner)
    private val executor = Executor { command ->
        if (!handler.post(command)) throw RejectedExecutionException("Owner Looper unavailable")
    }
    private val inputs = LinkedHashMap(contents)
    private val layers = linkedMapOf<Owner, SurfaceControl>()
    private var serial = 0L
    private var pending: Commit? = null
    private var attached = false
    private var detached = false
    private fun checkOwner() = check(Looper.myLooper() === owner)
    init {
        require(parent.isValid && inputs.size in 1..5)
        require(inputs.all { (o,b) ->
            !b.isClosed && b.width == o.canvas.width && b.height == o.canvas.height &&
                (b.usage and REQUIRED_USAGE) == REQUIRED_USAGE
        })
        try {
            for ((o,_) in inputs) {
                layers[o] = SurfaceControl.Builder().setName("Workbench2-owned-" + o.task)
                    .setHidden(true).build()
            }
        } catch (e: Exception) {
            layers.values.forEach { it.release() }
            throw e
        }
    }

    fun outstanding(): Commit? { checkOwner(); return pending }

    /**
     * Both/all layers are transformed in ONE transaction. A rollback is another
     * explicit submission of the independently retained prior poses, only after
     * the previous commit. No intermediate request is queued in this primitive.
     */
    fun submit(poses: Map<Owner, Pose>, onCommitted: (Commit) -> Unit): Boolean {
        checkOwner()
        if (pending != null || detached) return false
        check(parent.isValid && layers.values.all { it.isValid })
        require(poses.keys == inputs.keys)
        require(poses.all { (o,p) -> p.canvas == o.canvas && p.radiusPx == 0.0 &&
            p.scale.toFloat().isFinite() && p.scale.toFloat() > 0f &&
            p.content.x.toFloat().isFinite() && p.content.y.toFloat().isFinite() })
        SurfaceControl.Transaction().use { t ->
            for ((index,entry) in layers.entries.withIndex()) {
                val (o,layer) = entry
                val p = poses.getValue(o)
                val b = p.content
                if (!attached) {
                    check(!inputs.getValue(o).isClosed)
                    t.reparent(layer, parent).setBuffer(layer, inputs.getValue(o))
                    t.setLayer(layer, index + 1)
                }
                t.setCrop(layer, Rect(0,0,o.canvas.width,o.canvas.height))
                    .setPosition(layer,b.x.toFloat(),b.y.toFloat())
                    .setScale(layer,p.scale.toFloat(),p.scale.toFloat())
                    .setVisibility(layer,true)
            }
            apply(t, Kind.GEOMETRY, onCommitted)
        }
        return true
    }

    /** Release only our own child handles after the detach transaction commits. */
    fun detach(onCommitted: (Commit) -> Unit): Boolean {
        checkOwner()
        if (pending != null || detached) return false
        SurfaceControl.Transaction().use { t ->
            for (layer in layers.values) t.setVisibility(layer,false).setBuffer(layer,null).reparent(layer,null)
            apply(t, Kind.DETACH, onCommitted)
        }
        return true
    }

    private fun apply(t: SurfaceControl.Transaction, kind: Kind, onCommitted: (Commit) -> Unit) {
        val receipt = Commit(++serial, kind)
        t.addTransactionCommittedListener(executor) {
            checkOwner()
            if (pending != receipt) return@addTransactionCommittedListener
            if (kind == Kind.DETACH) {
                layers.values.forEach { it.release() }
                layers.clear()
                detached = true
            } else attached = true
            pending = null
            onCommitted(receipt)
        }
        pending = receipt
        // If apply throws after submission may have begun, keep the pending
        // receipt uncertain. Never fabricate settlement or release handles.
        t.apply()
    }
}
