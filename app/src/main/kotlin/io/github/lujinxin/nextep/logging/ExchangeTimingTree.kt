package io.github.x647368maker.atomicworkbench.logging

/** Inclusive and exclusive durations share one exchange and one monotonic clock. */
internal class ExchangeTimingTree(private val clock:()->Long) {
    data class Stat(var total:Long=0,var own:Long=0,var calls:Int=0)
    private data class Frame(val name:String,val start:Long,var child:Long=0)
    private val stack=ArrayDeque<Frame>()
    val stats=linkedMapOf<String,Stat>()
    fun <T> span(name:String,block:()->T):T {
        val frame=Frame(name,clock());stack.addLast(frame)
        try{return block()}finally{
            val duration=(clock()-frame.start).coerceAtLeast(0);stack.removeLast()
            stack.lastOrNull()?.let{it.child+=duration}
            stats.getOrPut(name){Stat()}.apply{total+=duration;own+=(duration-frame.child).coerceAtLeast(0);calls++}
        }
    }
}
