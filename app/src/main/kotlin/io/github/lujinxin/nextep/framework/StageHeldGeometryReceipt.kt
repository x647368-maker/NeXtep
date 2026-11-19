package io.github.x647368maker.atomicworkbench.framework

import java.util.concurrent.atomic.AtomicInteger

/**
 * Exact requested destination geometry, never a producer buffer identity.
 * Immutable values are copied from the existing owned surface write.
 */
internal data class StageHeldReceiptGeometry(
    val taskId:Int,val displayId:Int,val token:Any,val densityDpi:Int,
    val left:Int,val top:Int,val right:Int,val bottom:Int,
    val scale:Float,val x:Float,val y:Float,
) {
    fun valid():Boolean =
        densityDpi>0 && right.toLong()-left>=2L && bottom.toLong()-top>=2L &&
            scale.isFinite() && scale>0f && x.isFinite() && y.isFinite()
}

/**
 * Per direction, per active swap only. Reuse the existing CAS proof primitive.
 * A changed geometry always supersedes the prior ticket (including A-B-A).
 * No frame polling or new transaction. A missing proof never extends a hold.
 */
internal class StageHeldGeometryReceipt {
    private val proof=TaskPresentationCommitProof<StageHeldReceiptGeometry>()
    private val registrations=AtomicInteger(0)

    fun observe(leash:Any,reservation:Long?,geometry:StageHeldReceiptGeometry):
        TaskPresentationCommitProof.Ticket<StageHeldReceiptGeometry>? {
        if(reservation==null || !geometry.valid()) {
            proof.invalidate()
            return null
        }
        val ticket=proof.arm(leash,reservation,geometry)
        if(!proof.claimListener(ticket))return null
        // Strict bound even if a vendor repeatedly changes its window config
        // or fails listener registration. Exhaustion leaves proof unknown.
        while(true) {
            val count=registrations.get()
            if(count>=MAX_REGISTRATIONS)return null
            if(registrations.compareAndSet(count,count+1))return ticket
        }
    }

    fun acknowledge(ticket:TaskPresentationCommitProof.Ticket<StageHeldReceiptGeometry>,
                    stillActive:Boolean):Boolean =
        proof.acknowledge(ticket,stillActive)

    fun failedRegistration(ticket:TaskPresentationCommitProof.Ticket<StageHeldReceiptGeometry>) =
        proof.releaseFailedListener(ticket)

    fun confirmed(leash:Any,reservation:Long?,geometry:StageHeldReceiptGeometry):Boolean =
        reservation!=null && geometry.valid() && proof.confirmed(leash,reservation,geometry)

    fun invalidate()=proof.invalidate()

    companion object { const val MAX_REGISTRATIONS=4 }
}
