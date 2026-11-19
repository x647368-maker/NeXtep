package io.github.x647368maker.atomicworkbench.framework

/** Only exact same task and organizer leash on the reserved local canvas. */
internal object ObservedDemotionFitPolicy {
    fun matches(expectedDisplay: Int, observedDisplay: Int,
                expectedToken: Any, observedToken: Any?,
                expectedLeash: Any, observedLeash: Any?,
                fullscreen: Boolean, notVendorWindowed: Boolean,
                exactBounds: Boolean, exactDensity: Boolean,
                reservation: Long, activeReservation: Long?, activeDestination: Int?,
                now: Long): Boolean =
        expectedDisplay > 0 && observedDisplay == expectedDisplay &&
            expectedToken == observedToken && expectedLeash === observedLeash &&
            fullscreen && notVendorWindowed && exactBounds && exactDensity &&
            activeReservation == reservation && activeDestination == expectedDisplay &&
            now <= reservation

    /** A read-only mirror exception, NOT permission to move/clear a task or
     * stop the existing demotion writers. Identity of the fit (not just its
     * expiration timestamp) binds the completion to one observed generation.
     */
    fun completedMirrorSource(verifiedDemotion:Boolean,currentFit:Any?,completedFit:Any?,
                              noConflictingPresentation:Boolean,noHeldWriter:Boolean):Boolean =
        verifiedDemotion && currentFit!=null && completedFit===currentFit &&
            noConflictingPresentation && noHeldWriter
}
