package io.github.x647368maker.atomicworkbench.display

/** Read-only observation policy, never an artificial output ACK. */
internal object StageEarlySlotOutputPolicy {
    fun allowEarlier(earlyDisplay:Int, finalDisplay:Int,
                     earlyTexture:Any, finalTexture:Any,
                     earlySurface:Any, finalSurface:Any,
                     earlySequence:Long, finalSequence:Long,
                     sameCanvas:Boolean):Boolean =
        earlyDisplay==finalDisplay && earlyTexture===finalTexture &&
            earlySurface===finalSurface && earlySequence<=finalSequence &&
            sameCanvas
}
