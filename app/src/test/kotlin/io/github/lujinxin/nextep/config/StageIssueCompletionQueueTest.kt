package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageIssueCompletionQueueTest {
    @Test fun alarmAndUiShareOneCompletionEvenWhenListenerThrows() {
        val q = StageIssueCompletionQueue<String>()
        val calls = mutableListOf<String>()
        val first = checkNotNull(q.join(7) { calls.add("ui:$it") })
        assertNull(q.join(7) { error("detached listener") })
        assertNull(q.join(7) { calls.add("alarm:$it") })
        assertTrue(q.complete(first,"saved"))
        assertEquals(listOf("ui:saved","alarm:saved"),calls)
        assertFalse(q.complete(first,"duplicate"))
    }

    @Test fun failedWriteCanRetryAndOldCallbackCannotCompleteIt() {
        val q = StageIssueCompletionQueue<Result<String?>>()
        val calls = mutableListOf<Result<String?>>()
        val original = checkNotNull(q.join(8) { calls.add(it) })
        assertTrue(q.complete(original,Result.failure(IllegalStateException("storage"))))
        assertTrue(calls.single().isFailure)
        val retry = checkNotNull(q.join(8) { calls.add(it) })
        assertFalse(q.complete(original,Result.success("old")))
        assertTrue(q.owns(retry))
        assertTrue(q.complete(retry,Result.success("current")))
        assertEquals("current",calls.last().getOrNull())
    }
}
