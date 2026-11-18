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

    private val armed=AtomicReference<Ticket<F>?>(null)
    private val committed=AtomicReference<Ticket<F>?>(null)

    fun arm(leash:Any,reservation:Long,frame:F):Ticket<F> {
        val current=armed.get()
        if(current!=null && current.leash===leash &&
            current.reservation==reservation && current.frame==frame)return current
        val ticket=Ticket(leash,reservation,frame)
        committed.set(null)
        armed.set(ticket)
        return ticket
    }

    /** Called from the transaction-receipt executor; no UI work or Binder. */
    fun acknowledge(ticket:Ticket<F>,stillOwned:Boolean):Boolean {
        if(!stillOwned || armed.get()!==ticket)return false
        return committed.compareAndSet(null,ticket) && armed.get()===ticket
    }

    fun confirmed(leash:Any,reservation:Long,frame:F):Boolean {
        val ticket=committed.get() ?: return false
        return ticket===armed.get() && ticket.leash===leash &&
            ticket.reservation==reservation && ticket.frame==frame
    }

    fun invalidate() {
        committed.set(null)
        armed.set(null)
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
