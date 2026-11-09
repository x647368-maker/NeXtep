package io.github.x647368maker.atomicworkbench.systemui

internal class StageStableHandoff {
    private var consecutive=0
    fun observe(ready:Boolean):Boolean {consecutive=if(ready)consecutive+1 else 0;return consecutive>=2}
}
