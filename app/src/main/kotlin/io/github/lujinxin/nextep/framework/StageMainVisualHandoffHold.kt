package io.github.x647368maker.atomicworkbench.framework

import java.util.concurrent.ConcurrentHashMap

/**
 * Liveness of the existing visual coordinator, separate from task migration
 * and compositor commit. No clocks, polling, IPC or Surface transactions.
 * A delayed finish/cancel for a previous attempt cannot release a successor.
 */
internal class StageMainVisualHandoffHold {
    internal class Lease(val taskId:Int,val token:Any)
    private val active=ConcurrentHashMap<Int,Lease>()
    fun begin(taskId:Int,token:Any):Lease =
        Lease(taskId,token).also { active[taskId]=it }
    fun holds(taskId:Int,token:Any?):Boolean =
        token!=null && active[taskId]?.token==token
    fun end(lease:Lease):Boolean = active.remove(lease.taskId,lease)
}
