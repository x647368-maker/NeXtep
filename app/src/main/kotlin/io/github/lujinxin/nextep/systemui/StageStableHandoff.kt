package io.github.x647368maker.atomicworkbench.systemui

internal class StageStableHandoff {
    enum class Decision { WAIT, READY, TIMEOUT }
    fun decide(ready:Boolean,expired:Boolean):Decision {
        val stable=observe(ready)
        return if(stable)Decision.READY else if(expired)Decision.TIMEOUT else Decision.WAIT
    }
    private var consecutive=0
    fun observe(ready:Boolean):Boolean {consecutive=if(ready)consecutive+1 else 0;return consecutive>=2}
}
