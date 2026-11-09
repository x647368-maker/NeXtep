package io.github.x647368maker.atomicworkbench.display

/** Facts come from one task snapshot; stale labels never establish capacity. */
internal object SlotAvailabilityPolicy {
    enum class State { EMPTY, READY, OCCUPIED, FAILED }
    data class Fact(val state: State, val display: Int?, val task: Int?, val actualDisplay: Int?,
        val tasksOnDisplay: List<Int>, val surface: Boolean, val visible: Boolean)
    sealed interface Decision {
        data class Available(val index: Int): Decision
        data object Full: Decision
        data object Recover: Decision
    }
    fun decide(facts: List<Fact>): Decision {
        require(facts.size == 4)
        val vacant=facts.indexOfFirst { it.state == State.READY && it.display != null && it.surface && it.tasksOnDisplay.isEmpty() }
        if(vacant>=0) return Decision.Available(vacant)
        val ids=facts.mapNotNull { it.task }
        return if(ids.size==4 && ids.distinct().size==4 && facts.all {
            it.state==State.OCCUPIED && it.display!=null && it.actualDisplay==it.display &&
                it.task in it.tasksOnDisplay && it.surface && it.visible
        }) Decision.Full else Decision.Recover
    }
}
