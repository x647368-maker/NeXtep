package io.github.x647368maker.atomicworkbench.systemui

/** No per-frame Java/Kotlin loop, no recurring timer or extra WindowManager view. */
internal object StageOneShotSwapMotionPolicy {
    const val OUTBOUND_MS=65L
    const val RETURN_MS=95L
    const val TOTAL_MS=OUTBOUND_MS+RETURN_MS
    const val PEEK_SCALE=1.035f
    fun allowed(enabled:Boolean, powerSave:Boolean, visibleLiveView:Boolean, width:Int, height:Int):Boolean =
        enabled && !powerSave && visibleLiveView && width>1 && height>1
}
