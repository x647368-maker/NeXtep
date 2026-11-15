package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageMediaBindingRegressionTest {
    @Test fun sessionsRemainIsolatedByProfileAndDuplicateKeysPreserveFirstObservation() {
        val one = StageMediaKey("video", 10, "session")
        val two = StageMediaKey("video", 20, "session")
        val playing = StagePlaybackObservation(one, StagePlayback.PLAYING)
        val paused = StagePlaybackObservation(two, StagePlayback.PAUSED)
        val tasks = listOf(StageObservedTask(1, "video", 10),
            StageObservedTask(2, "video", 20), StageObservedTask(3, "video", null))
        val result = StageMediaBinding.bind(tasks, listOf(playing,
            StagePlaybackObservation(one, StagePlayback.STOPPED), paused))
        assertEquals(mapOf(1 to playing, 2 to paused), result)
    }

    @Test fun ambiguousTasksAndSeparateSessionsNeverPickAnArbitraryPlaybackState() {
        val one = StagePlaybackObservation(StageMediaKey("video", 10, "a"), StagePlayback.PAUSED)
        val two = StagePlaybackObservation(StageMediaKey("video", 10, "b"), StagePlayback.PLAYING)
        val task = StageObservedTask(1, "video", 10)
        assertTrue(StageMediaBinding.bind(listOf(task), listOf(one, two)).isEmpty())
        assertTrue(StageMediaBinding.bind(listOf(task, task.copy(id = 2)), listOf(one)).isEmpty())
        assertTrue(StageMediaBinding.bind(listOf(task.copy(userId = null)), listOf(one)).isEmpty())
    }
}
