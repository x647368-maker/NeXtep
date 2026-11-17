package io.github.x647368maker.atomicworkbench.systemui

/** Called after claiming the first migration ACK. No scheduling on the UI lane. */
internal object StageAckDispatch {
    fun dispatch(onUi:Boolean, post:(Runnable)->Unit, action:Runnable) {
        if(onUi) action.run() else post(action)
    }
}
