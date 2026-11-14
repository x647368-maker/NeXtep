package io.github.x647368maker.atomicworkbench.systemui

/** One terminal-render retry per flight. A retry never constitutes a commit. */
internal class StageTerminalCommitRecovery {
    companion object { const val RETRY_MS=48L }
    private var closed=false
    private var retried=false
    fun retry():Boolean {
        if(closed || retried)return false
        retried=true
        return true
    }
    fun accept(terminalDrawn:Boolean):Boolean {
        if(closed || !terminalDrawn)return false
        closed=true
        return true
    }
    fun close(){closed=true}
}
