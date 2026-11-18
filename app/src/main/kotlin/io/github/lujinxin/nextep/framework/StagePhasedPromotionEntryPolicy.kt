package io.github.x647368maker.atomicworkbench.framework

/**
 * A task already moved to display 0 by the reserved phased exchange has
 * its main-card placement applied by the task presentation coordinator.
 * If the speculative pre-fit was lost, do not play a second independent
 * cold-launch scale-up when publishing that same real task.
 *
 * This does not change its task/display/geometry, skip a compositor receipt,
 * animate any fake task image, or weaken the exchange rollback protocol.
 */
internal object StagePhasedPromotionEntryPolicy {
    fun suppressDuplicateEntrance(
        activeReservation: Boolean,
        reservedDestinationDisplay: Int?,
        verifiedMainLeash: Boolean,
        preparedAlready: Boolean,
        explicitFullscreenEntrance: Boolean,
    ): Boolean =
        activeReservation && reservedDestinationDisplay==0 &&
        verifiedMainLeash && !preparedAlready && !explicitFullscreenEntrance

    /**
     * A real display=0 promotion owns the card's visible flight and will only
     * reveal the resized app after its matching compositor receipt. While that
     * receipt is pending, a concurrent geometry refresh must not install an
     * additional independent REPOSITION animator onto the same task leash:
     * it can first restore the previous large frame and then scale it down,
     * creating a second zoom after the flight.
     *
     * This only affects the confirmed *current main leash* of a still-recorded
     * real move, even if its scheduling TTL elapsed; a deadline cannot be
     * interpreted as a compositor receipt. Until both the final frame commits
     * and the exact visual handoff closes, normal reapply must not start
     * another scale flight. Normal post-handoff layout, user drag, rotation,
     * task replacement and cold app opening still retain their old routes.
     */
    /**
     * The display exchange reservation is normally replaced by the next
     * task move. Once the task's real terminal frame is committed AND the
     * scheduling window has elapsed, retaining the old entry forever would
     * suppress normal later user layout animations.
     *
     * Never retire on time alone, on memory-only frame equality, on an
     * unrelated leash, before the compositor accepts that final frame, or\n     * in the same callback that submits new task geometry. The latter would\n     * turn the new uncommitted frame into a falsely settled endpoint.
     */
    fun canRetireCompletedMainReservation(
        hasReservation:Boolean,
        expired:Boolean,
        destinationDisplay:Int?,
        ownsSameMainLeash:Boolean,
        finalFrameCommitted:Boolean,
        geometryStable:Boolean,
        noPresentationAnimator:Boolean,
        visualHandoffPending:Boolean=false,
    ):Boolean =
        hasReservation && expired && destinationDisplay==0 &&
            ownsSameMainLeash && finalFrameCommitted &&
            geometryStable && noPresentationAnimator && !visualHandoffPending

    fun commitMoveRepositionWithoutExtraAnimator(
        recordedPromotion:Boolean,
        destinationDisplay:Int?,
        verifiedMainLeash:Boolean,
        previousFinalCommitted:Boolean,
        geometryChanged:Boolean,
        rotationChanged:Boolean,
        leashChanged:Boolean,
        visualHandoffPending:Boolean=false,
    ):Boolean =
        recordedPromotion && destinationDisplay==0 && verifiedMainLeash &&
            (!previousFinalCommitted || visualHandoffPending) && geometryChanged &&
            !rotationChanged && !leashChanged
}
