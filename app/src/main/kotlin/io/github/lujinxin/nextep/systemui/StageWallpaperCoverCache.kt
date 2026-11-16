package io.github.x647368maker.atomicworkbench.systemui

/** Main-lane ownership only. A replaced/cleared cache is not borrowable;
 * an already attached cover retains its original immutable resource until
 * the cover has released both surface handles. No timers or workers. */
internal class StageWallpaperCoverCache<T:AutoCloseable> {
    data class Key(val width:Int,val height:Int,val rotation:Int)
    private class Entry<T:AutoCloseable>(val key:Key,val resource:T) {
        var borrowers=0
        var retired=false
        var closed=false
        fun closeIfRetired() {
            if(retired && borrowers==0 && !closed) {
                closed=true
                runCatching { resource.close() }
            }
        }
    }
    class Lease<T:AutoCloseable> internal constructor(val resource:T,private val release:()->Unit):AutoCloseable {
        private var closed=false
        override fun close() {
            if(closed)return
            closed=true
            release()
        }
    }
    private var entry:Entry<T>?=null
    fun replace(key:Key,resource:T) {
        clear()
        entry=Entry(key,resource)
    }
    fun acquire(key:Key):Lease<T>? {
        val owner=entry?.takeIf { !it.retired && it.key==key }?:return null
        owner.borrowers++
        return Lease(owner.resource) {
            owner.borrowers--
            owner.closeIfRetired()
        }
    }
    fun clearIfMismatched(key:Key) {
        if(entry?.key!=key)clear()
    }
    fun clear() {
        val previous=entry?:return
        entry=null
        previous.retired=true
        previous.closeIfRetired()
    }
}
