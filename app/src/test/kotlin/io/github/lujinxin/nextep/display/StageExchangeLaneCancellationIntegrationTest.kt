package io.github.lujinxin.nextep.display

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Exercise the real serialized worker and ExchangeSession with controlled
 * IPC stand-ins. This is not a real task move, nor proof of a batched candidate.
 * The two owner gates mirror the current production move/query path.
 */
class StageExchangeLaneCancellationIntegrationTest {
    private class Fixture {
        private val owner = Thread.currentThread()
        private val turns = LinkedBlockingQueue<() -> Unit>()
        private val lane = StageExchangeBinderLane { turns.add(it) }
        val moveEntered = CountDownLatch(1)
        val releaseMove = CountDownLatch(1)
        val queryEntered = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        var clock = 0L
        var queries = 0
        var published = 0
        var demotions = 0
        var rollbacks = 0
        var completions = 0
        var result: Boolean? = null
        lateinit var session: ExchangeSession<String>
            private set

        init {
            session = ExchangeSession(
                41, 100L, ExchangeSession.Origin(1, 0, "main-token", 0, "main"),
                ExchangeSession.Origin(2, 0, "slot-token", 7, "slot"),
                { clock }, { turns.add(it) },
                object : ExchangeSession.Driver {
                    override val nonBlocking = true
                    override fun promote() = error("unexpected synchronous promotion")
                    override fun demoteAndVerify() = error("unexpected synchronous demotion")
                    override fun restoreAndVerify() {
                        assertSame(owner, Thread.currentThread())
                        rollbacks++
                    }
                    override fun demoteAndVerifyAsync(done: (Throwable?) -> Unit) {
                        demotions++
                        done(null)
                    }
                    override fun promoteAsync(done: (Throwable?) -> Unit) {
                        check(lane.submit({
                            check(Thread.currentThread() !== owner)
                            moveEntered.countDown()
                            check(releaseMove.await(5, TimeUnit.SECONDS))
                        }) { move ->
                            val moveFailure = runCatching {
                                move.getOrThrow()
                                check(session.acceptsForwardWork)
                            }.exceptionOrNull()
                            if (moveFailure != null) {
                                done(moveFailure)
                            } else {
                                queries++
                                check(lane.submit({
                                    check(Thread.currentThread() !== owner)
                                    queryEntered.countDown()
                                    check(releaseQuery.await(5, TimeUnit.SECONDS))
                                }) { query ->
                                    done(runCatching {
                                        query.getOrThrow()
                                        check(session.acceptsForwardWork)
                                        assertSame(owner, Thread.currentThread())
                                        published++
                                    }.exceptionOrNull())
                                })
                            }
                        })
                    }
                },
                { result = it; completions++ }
            )
        }

        fun turn() = checkNotNull(turns.poll(5, TimeUnit.SECONDS)) {
            "Expected an actual worker/owner completion"
        }.invoke()

        fun start() {
            session.start()
            turn()
            assertTrue(moveEntered.await(5, TimeUnit.SECONDS))
        }

        fun assertHeld() {
            assertNull(result)
            assertEquals(0, rollbacks)
            assertEquals(0, completions)
            assertFalse(lane.submit({ error("overlapping Binder call") }) {})
        }

        fun finishRollback() {
            turn() // actual IPC/query completion
            turn() // ExchangeSession receives the driver reply
            turn() // verified rollback
            assertEquals(ExchangeSession.Phase.RESTORED, session.phase)
            assertEquals(false, result)
            assertEquals(1, rollbacks)
            assertEquals(1, completions)
            assertEquals(0, published)
            assertEquals(0, demotions)
            assertTrue(turns.isEmpty())
        }

        fun turnsEmptyForTest() = turns.isEmpty()

        fun cleanup() {
            releaseMove.countDown()
            releaseQuery.countDown()
        }
    }

    @Test fun cancelDuringRealWorkerMoveWaitsForReplyAndNeverStartsQuery() {
        val f = Fixture()
        try {
            f.start()
            f.session.cancel()
            assertFalse(f.session.acceptsForwardWork)
            f.assertHeld()
            f.releaseMove.countDown()
            f.finishRollback()
            assertEquals(0, f.queries)
        } finally { f.cleanup() }
    }

    @Test fun cancelDuringRealWorkerQueryWaitsForReplyAndNeverPublishes() {
        val f = Fixture()
        try {
            f.start()
            f.releaseMove.countDown()
            f.turn()
            assertTrue(f.queryEntered.await(5, TimeUnit.SECONDS))
            f.session.cancel()
            f.assertHeld()
            f.releaseQuery.countDown()
            f.finishRollback()
            assertEquals(1, f.queries)
        } finally { f.cleanup() }
    }

    @Test fun expirationDuringQueryDoesNotPublishOrReleaseBeforeActualReply() {
        val f = Fixture()
        try {
            f.start()
            f.releaseMove.countDown()
            f.turn()
            assertTrue(f.queryEntered.await(5, TimeUnit.SECONDS))
            f.clock = 100L
            assertFalse(f.session.acceptsForwardWork)
            f.assertHeld()
            f.releaseQuery.countDown()
            f.finishRollback()
            assertEquals(1, f.queries)
        } finally { f.cleanup() }
    }

    @Test fun validSessionPublishesOnceAndCommitsAfterRealWorkerReplies() {
        val f = Fixture()
        try {
            f.start()
            f.releaseMove.countDown()
            f.turn()
            assertTrue(f.queryEntered.await(5, TimeUnit.SECONDS))
            f.assertHeld()
            f.releaseQuery.countDown()
            f.turn() // query completion publishes on owner
            f.turn() // promotion result
            f.turn() // demotion
            f.turn() // demotion result
            assertEquals(ExchangeSession.Phase.COMMITTED, f.session.phase)
            assertEquals(true, f.result)
            assertEquals(1, f.queries)
            assertEquals(1, f.published)
            assertEquals(1, f.demotions)
            assertEquals(1, f.completions)
            assertEquals(0, f.rollbacks)
            assertTrue(f.session.settled)
            assertTrue(f.turnsEmptyForTest())
        } finally { f.cleanup() }
    }
}
