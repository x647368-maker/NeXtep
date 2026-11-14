package io.github.x647368maker.atomicworkbench.framework

import org.junit.Assert.*
import org.junit.Test

class ObservedDemotionFitPolicyTest {
    private val token=Any()
    private val leash=Any()
    private fun eligible(display:Int=12, actualToken:Any?=token, actualLeash:Any?=leash,
                         fullscreen:Boolean=true, regular:Boolean=true, bounds:Boolean=true,
                         density:Boolean=true, reservation:Long?=2000L,
                         destination:Int?=12, now:Long=1000L):Boolean =
        ObservedDemotionFitPolicy.matches(12,display,token,actualToken,leash,actualLeash,
            fullscreen,regular,bounds,density,2000L,reservation,destination,now)

    @Test fun requiresExactDestinationAndOriginalTaskSurface() {
        assertTrue(eligible())
        assertFalse(eligible(display=0))
        assertFalse(eligible(display=13))
        assertFalse(eligible(actualToken=Any()))
        assertFalse(eligible(actualToken=null))
        assertFalse(eligible(actualLeash=Any()))
        assertFalse(eligible(actualLeash=null))
        assertFalse(eligible(fullscreen=false))
        assertFalse(eligible(regular=false))
        assertFalse(eligible(bounds=false))
        assertFalse(eligible(density=false))
    }

    @Test fun staleCancelledOrChangedReservationCannotWrite() {
        assertTrue(eligible(now=2000L))
        assertFalse(eligible(now=2001L))
        assertFalse(eligible(reservation=null))
        assertFalse(eligible(reservation=2001L))
        assertFalse(eligible(destination=0))
        assertFalse(eligible(destination=13))
        assertFalse(eligible(destination=null))
    }
}
