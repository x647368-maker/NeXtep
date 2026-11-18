package io.github.x647368maker.atomicworkbench.framework

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

    // Both fields are a single logical snapshot. Using separate atomic refs
    // allowed two producer threads to interleave arm(A)/arm(B), publishing an
    // obsolete ticket last and invalidating the newest task transaction.
    // Each critical section is purely in-memory, with no IPC, surface writes,
    // waiting for a compositor callback or UI work under this monitor.
    private var armed:Ticket<F>?=null
    private var committed:Ticket<F>?=null

    @Synchronized
    fun arm(leash:Any,reservation:Long,frame:F):Ticket<F> {
        val current=armed
        if(current!=null && current.leash===leash &&
            current.reservation==reservation && current.frame==frame)return current
        val ticket=Ticket(leash,reservation,frame)
        committed=null
        armed=ticket
        return ticket
    }

    /** Called from the transaction-receipt executor; no UI work or Binder. */
    @Synchronized
    fun acknowledge(ticket:Ticket<F>,stillOwned:Boolean):Boolean {
        if(!stillOwned || armed!==ticket || committed!=null)return false
        committed=ticket
        return true
    }

    @Synchronized
    fun confirmed(leash:Any,reservation:Long,frame:F):Boolean {
        val ticket=committed ?: return false
        return ticket===armed && ticket.leash===leash &&
            ticket.reservation==reservation && ticket.frame==frame
    }

    @Synchronized
    fun invalidate() {
        committed=null
        armed=null
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
