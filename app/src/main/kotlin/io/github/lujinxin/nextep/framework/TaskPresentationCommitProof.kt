package io.github.x647368maker.atomicworkbench.framework

import java.util.concurrent.atomic.AtomicReference

/**
 * A SurfaceControl.Transaction.apply() call only submits a task transform.
 * This one-shot proof ties a later compositor committed receipt to the exact
 * current main-task leash, display-move reservation and terminal frame.
 *
 * No pixels, tasks, Shell transactions or geometry are manufactured here.
 * In particular a callback from an earlier reparent must never satisfy the
 * readiness gate of a newly selected task or a later exchange.
 */
internal class TaskPresentationCommitProof<F> {
    internal class Ticket<F>(
        val leash: Any,
        val reservation: Long,
        val frame: F,
    )

    // One immutable state atomically joins the armed ticket and the only
    // accepted compositor receipt. Two separate atomic refs let a stale arm()
    // overwrite a newer ticket. A CAS loop avoids blocking the render/UI
    // thread or direct compositor callback on a monitor.
    private data class State<F>(
        val armed:Ticket<F>?=null,
        val committed:Ticket<F>?=null,
    )
    private val state=AtomicReference(State<F>())

    fun arm(leash:Any,reservation:Long,frame:F):Ticket<F> {
        while(true) {
            val before=state.get()
            val current=before.armed
            if(current!=null && current.leash===leash &&
                current.reservation==reservation && current.frame==frame)
                return current
            val ticket=Ticket(leash,reservation,frame)
            if(state.compareAndSet(before,State(ticket,null)))return ticket
        }
    }

    /** Called from the transaction-receipt executor; no UI work or Binder. */
    fun acknowledge(ticket:Ticket<F>,stillOwned:Boolean):Boolean {
        if(!stillOwned)return false
        while(true) {
            val before=state.get()
            if(before.armed!==ticket || before.committed!=null)return false
            if(state.compareAndSet(before,State(ticket,ticket)))return true
        }
    }

    fun confirmed(leash:Any,reservation:Long,frame:F):Boolean {
        val current=state.get()
        val ticket=current.committed ?: return false
        return ticket===current.armed && ticket.leash===leash &&
            ticket.reservation==reservation && ticket.frame==frame
    }

    fun invalidate() {
        state.set(State())
    }

    companion object {
        /**
         * Older devices keep their previous proof behavior. Only an active
         * move to physical display 0 can require a compositor receipt.
         */
        fun needsReceipt(sdk:Int,moveUnexpired:Boolean,destinationDisplay:Int?,
                         currentlyOwned:Boolean):Boolean =
            sdk>=33 && moveUnexpired && destinationDisplay==0 && currentlyOwned
    }
}
