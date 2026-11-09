package io.github.x647368maker.atomicworkbench.systemui

/** No task/media operations. Unknown native state is deliberately fail-closed. */
internal class StagePanelMenuGate(
    private val state: () -> State,
    private val now: () -> Long,
    private val later: (Long, () -> Unit) -> Unit,
    private val dismiss: () -> Unit,
) {
    enum class State { CLOSED, OPEN, UNKNOWN }
    private var generation=0
    fun cancel() { generation++;dismiss() }
    fun panelChanged() { if(state()!=State.CLOSED) dismiss() }
    fun request(alive:()->Boolean, collapse:()->Boolean, show:()->Unit, failed:()->Unit) {
        val token=++generation
        dismiss()
        if(!alive())return
        if(state()==State.CLOSED){show();return}
        if(!collapse()){failed();return}
        val deadline=now()+1200
        var closedSince:Long?=null
        fun check() {
            if(token!=generation || !alive())return
            val time=now()
            if(state()==State.CLOSED) {
                if(closedSince==null)closedSince=time
                if(time-closedSince!!>=80){show();return}
            } else closedSince=null
            if(time>=deadline){failed();return}
            later(60,::check)
        }
        later(60,::check)
    }
}
