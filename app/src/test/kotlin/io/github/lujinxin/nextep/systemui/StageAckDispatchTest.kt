package io.github.x647368maker.atomicworkbench.systemui
import org.junit.Assert.*
import org.junit.Test
class StageAckDispatchTest {
 @Test fun uiAckDoesNotWaitBehindUnrelatedQueueWork() {
  var complete=false
  StageAckDispatch.dispatch(true,{ error("UI ACK queued again") },Runnable {complete=true})
  assertTrue(complete)
 }
 @Test fun workerAckWaitsForUiAndRechecksCancellation() {
  val queue=mutableListOf<Runnable>();var running=true;var completed=0
  StageAckDispatch.dispatch(false,{queue.add(it)},Runnable {if(running)completed++})
  assertEquals(0,completed);assertEquals(1,queue.size)
  running=false;queue.single().run();assertEquals(0,completed)
 }
 @Test fun workerAckRunsOnceWhenDelivered() {
  val queue=mutableListOf<Runnable>();var completed=0
  StageAckDispatch.dispatch(false,{queue.add(it)},Runnable {completed++})
  assertEquals(0,completed);queue.single().run();assertEquals(1,completed)
 }
}
