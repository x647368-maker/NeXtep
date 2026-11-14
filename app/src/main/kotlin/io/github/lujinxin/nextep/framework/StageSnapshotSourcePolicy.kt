package io.github.x647368maker.atomicworkbench.framework

/** A recent completed move is NOT an in-flight move. Never clear the existing
 * Shell recovery reservation; a read-only preview may use it only after the
 * actual observed destination matches, and only while the stamp is unchanged.
 */
internal object StageSnapshotSourcePolicy {
    fun reservationAllowsRead(observedDisplay:Int, activeReservation:Boolean,
                              reservedDestination:Int?):Boolean =
        !activeReservation || reservedDestination == observedDisplay

    fun localExtentMatches(observedWidth:Int, observedHeight:Int,
                           cropWidth:Int,cropHeight:Int):Boolean =
        observedWidth >= 2 && observedHeight >= 2 &&
            cropWidth in 2..observedWidth && cropHeight in 2..observedHeight
}
