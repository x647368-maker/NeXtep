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
        val listenerClaimed:Boolean=false,
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
            if(state.compareAndSet(before,State(ticket,null,false)))return ticket
        }
    }

    /** Called from the transaction-receipt executor; no UI work or Binder. */
    fun acknowledge(ticket:Ticket<F>,stillOwned:Boolean):Boolean {
        if(!stillOwned)return false
        while(true) {
            val before=state.get()
            if(before.armed!==ticket || before.committed!=null)return false
            if(state.compareAndSet(before,State(ticket,ticket,true)))return true
        }
    }

    /**
     * Attach at most one listener to the exact final frame of one still-owned
     * presentation generation, regardless of how often steady reapply is
     * invoked after a scheduling TTL. Another frame/leash/move gets a fresh
     * ticket from arm(). Failed listener installation or Transaction.apply()
     * releases this claim for the next bounded reconciliation opportunity.
     */
    fun claimListener(ticket:Ticket<F>):Boolean {
        while(true) {
            val before=state.get()
            if(before.armed!==ticket || before.committed!=null ||
                before.listenerClaimed)return false
            if(state.compareAndSet(before,before.copy(listenerClaimed=true)))return true
        }
    }

    fun releaseFailedListener(ticket:Ticket<F>) {
        while(true) {
            val before=state.get()
            if(before.armed!==ticket || before.committed!=null ||
                !before.listenerClaimed)return
            if(state.compareAndSet(before,before.copy(listenerClaimed=false)))return
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
         * Only a real destination-display-0 task transfer on a supported
         * device can install the compositor callback. At submission time a
         * live reservation is required, avoiding idle receipt listeners.
         */
        fun needsReceipt(sdk:Int,moveUnexpired:Boolean,destinationDisplay:Int?,
                         currentlyOwned:Boolean):Boolean =
            sdk>=33 && moveUnexpired && destinationDisplay==0 && currentlyOwned

        /**
         * An incomplete main promotion must never be pruned merely because
         * its *scheduling* TTL expired. Successful promotions are retired by
         * the existing exact-leash, exact-receipt, stable-geometry policy;
         * failed promotions use explicit cancellation/rollback. Legacy
         * expired slot reservations retain their original cleanup behavior.
         */
        fun mayPruneExpiredReservation(sdk:Int,expired:Boolean,
                                      destinationDisplay:Int?):Boolean =
            expired && !(sdk>=33 && destinationDisplay==0)

        /**
         * Handoff readiness must not become "true" only because the
         * reservation deadline elapsed. A recorded transfer can still be
         * completing while its compositor receipt is late or missing.
         *
         * This predicate performs no work and never prolongs the existing
         * bounded visual attempt: its TIMEOUT/rollback owner remains the
         * GPU/RT handoff coordinator. Once the record is cancelled or a
         * different destination owns the task, normal readiness resumes.
         */
        fun settledAfterRecordedMove(sdk:Int,recordedReservation:Long?,
                                     destinationDisplay:Int?,currentlyOwned:Boolean,
                                     committed:Boolean):Boolean =
            !needsReceipt(sdk,recordedReservation!=null,destinationDisplay,
                currentlyOwned) || committed

        /**
         * A delayed compositor callback is still valid after the scheduling
         * reservation's *deadline* provided its recorded move identity and
         * task surface ownership have not changed. Using a current-time/TTL
         * check here contradicts settledAfterRecordedMove: a real late
         * transaction would be discarded and the handoff could only timeout.
         *
         * Cancellation clears the record; subsequent moves replace it and
         * invalidate the old ticket. Neither case can reuse this receipt.
         */
        fun matchesCommittedReceipt(recordedReservation:Long?,
                                    submittedReservation:Long,
                                    destinationDisplay:Int?,
                                    samePresentation:Boolean,
                                    sameLeash:Boolean):Boolean =
            recordedReservation==submittedReservation &&
                destinationDisplay==0 && samePresentation && sameLeash
    }
}
