package io.github.x647368maker.atomicworkbench.xposed

/** Bounded, process-local evidence. No package inventories, task data or exception messages. */
class InjectionLedger {
    data class Event(val phase: String, val state: String, val error: String, val time: Long)
    private val events = LinkedHashMap<String, Event>()
    @Synchronized fun record(phase: String, state: String, error: String, time: Long): Boolean {
        val old=events[phase]
        if(old?.state==state && old.error==error)return false
        if(events.size>=48 && phase !in events)events.remove(events.values.firstOrNull{it.state=="OK"}?.phase ?: events.keys.first())
        events[phase]=Event(phase,state,error,time);return true
    }
    @Synchronized fun snapshot():List<Event> = events.values.toList()
}

/** One installer per module generation/process, including concurrent callbacks. */
class InjectionOnce {
    private var claimed=false
    @Synchronized fun claim():Boolean { if(claimed)return false;claimed=true;return true }
}

object InjectionDispatchPolicy {
    fun accept(first:Boolean,packageName:String,processName:String):Boolean = first ||
        (packageName=="com.android.systemui" && processName=="com.android.systemui")
}
