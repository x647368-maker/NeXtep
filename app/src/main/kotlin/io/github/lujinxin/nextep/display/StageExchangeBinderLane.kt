package io.github.x647368maker.atomicworkbench.display

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Only the already authorized synchronous move IPC is offloaded.
 * The session's owner retains its reservation until the actual IPC returns;
 * cancel never pretends a submitted Binder call has completed. No retry,
 * parallel mutation, idle polling or permanent worker.
 */
internal class StageExchangeBinderLane(private val postToOwner: (() -> Unit) -> Unit) {
    private val owner=Thread.currentThread()
    private val worker=ThreadPoolExecutor(0,1,1L,TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(1),
        { r -> Thread(r,"StageExchangeBinder").apply { isDaemon=true } },
        ThreadPoolExecutor.AbortPolicy())
    private var inFlight=false
    fun submit(call:()->Unit,complete:(Result<Unit>)->Unit):Boolean {
        check(Thread.currentThread()===owner)
        if(inFlight)return false
        inFlight=true
        return try {
            worker.execute {
                val result=runCatching(call)
                postToOwner {
                    check(Thread.currentThread()===owner)
                    inFlight=false
                    complete(result)
                }
            }
            true
        } catch(error:java.util.concurrent.RejectedExecutionException){
            inFlight=false
            false
        }
    }
}
