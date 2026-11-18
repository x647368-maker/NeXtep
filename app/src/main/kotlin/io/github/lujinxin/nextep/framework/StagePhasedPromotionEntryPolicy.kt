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
}
