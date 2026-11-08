package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.display.TaskContinuityPolicy
import org.junit.Assert.*
import org.junit.Test

/** Policy tests only: names describe desired scenarios, not device app execution. */
class StageContinuityRegressionTest {
    private fun session(pkg: String, user: Int, token: String, state: StagePlayback) =
        StagePlaybackObservation(StageMediaKey(pkg, user, token), state)
    @Test fun pausedVideoSwapProducesNoWorkbenchTransport() {
        val read = session("video", 0, "token-a", StagePlayback.PAUSED)
        assertFalse(TaskContinuityPolicy.canTransport(TaskContinuityPolicy.Origin.WINDOW_SWAP, read.key, read.key))
        assertEquals(StagePlayback.PAUSED, read.state)
    }
    @Test fun playingVideoSwapProducesNoWorkbenchTransport() {
        assertFalse(TaskContinuityPolicy.canTransport(TaskContinuityPolicy.Origin.WINDOW_SWAP, "a", "a"))
    }
    @Test fun musicSwitchToChatCannotCausePlayPauseOrAudioFocusCommands() {
        for (origin in listOf(TaskContinuityPolicy.Origin.WINDOW_SWAP, TaskContinuityPolicy.Origin.LAYOUT,
            TaskContinuityPolicy.Origin.TASK_OBSERVATION, TaskContinuityPolicy.Origin.LOCK_RESTORE, TaskContinuityPolicy.Origin.SYSTEM_OR_APP)) {
            assertFalse(TaskContinuityPolicy.canTransport(origin, "music", "music"))
        }
    }
    @Test fun twoIndependentPlayersAreObservedSeparately() {
        val tasks = listOf(StageObservedTask(1, "a", 0), StageObservedTask(2, "b", 0))
        val bound = StageMediaBinding.bind(tasks, listOf(session("a", 0, "sa", StagePlayback.PAUSED), session("b", 0, "sb", StagePlayback.PLAYING)))
        assertEquals(StagePlayback.PAUSED, bound.getValue(1).state)
        assertEquals(StagePlayback.PLAYING, bound.getValue(2).state)
    }
    @Test fun profilesAreNeverMergedByPackage() {
        val tasks = listOf(StageObservedTask(1, "same", 0), StageObservedTask(2, "same", 10))
        val bound = StageMediaBinding.bind(tasks, listOf(session("same", 0, "sa", StagePlayback.PAUSED), session("same", 10, "sb", StagePlayback.PLAYING)))
        assertEquals(0, bound.getValue(1).key.userId)
        assertEquals(10, bound.getValue(2).key.userId)
        assertNotEquals(bound.getValue(1).key, bound.getValue(2).key)
    }
    @Test fun samePackageMultipleTasksRemainAmbiguousRatherThanGuessing() {
        val tasks = listOf(StageObservedTask(1, "same", 0), StageObservedTask(2, "same", 0))
        assertTrue(StageMediaBinding.bind(tasks, listOf(session("same", 0, "s", StagePlayback.PLAYING))).isEmpty())
    }
    @Test fun samePackageMultipleSessionsRemainAmbiguousRatherThanGuessing() {
        assertTrue(StageMediaBinding.bind(listOf(StageObservedTask(1, "same", 0)), listOf(
            session("same", 0, "s1", StagePlayback.PAUSED), session("same", 0, "s2", StagePlayback.PLAYING))).isEmpty())
    }
    @Test fun unknownUserAndMissingSessionAreNotAssumedToBeCurrentUser() {
        assertTrue(StageMediaBinding.bind(listOf(StageObservedTask(1, "same", null)), listOf(session("same", 0, "s", StagePlayback.PLAYING))).isEmpty())
        assertTrue(StageMediaBinding.bind(listOf(StageObservedTask(1, "same", 0)), emptyList()).isEmpty())
    }
    @Test fun newestUserPauseIsNotOverwrittenByEarlierPlayingObservation() {
        val task = listOf(StageObservedTask(1, "video", 0))
        val before = StageMediaBinding.bind(task, listOf(session("video", 0, "s", StagePlayback.PLAYING)))
        val after = StageMediaBinding.bind(task, listOf(session("video", 0, "s", StagePlayback.PAUSED)))
        assertEquals(StagePlayback.PLAYING, before.getValue(1).state)
        assertEquals(StagePlayback.PAUSED, after.getValue(1).state)
        assertFalse(TaskContinuityPolicy.canTransport(TaskContinuityPolicy.Origin.TASK_OBSERVATION, before[1]?.key, after[1]?.key))
    }
    @Test fun changedSessionRejectsDelayedUserCommand() {
        assertFalse(TaskContinuityPolicy.canTransport(TaskContinuityPolicy.Origin.USER_MEDIA_BUTTON, "old-token", "new-token"))
        assertFalse(TaskContinuityPolicy.canTransport(TaskContinuityPolicy.Origin.USER_MEDIA_BUTTON, null, null))
        assertTrue(TaskContinuityPolicy.canTransport(TaskContinuityPolicy.Origin.USER_MEDIA_BUTTON, "same-token", "same-token"))
    }
    @Test fun ordinarySwapsAndReconciliationNeverLaunchTask() {
        for (origin in TaskContinuityPolicy.Origin.entries) {
            assertFalse(TaskContinuityPolicy.canLaunch(origin, existingTask = true))
            if (origin != TaskContinuityPolicy.Origin.USER_LAUNCH) assertFalse(TaskContinuityPolicy.canLaunch(origin, existingTask = false))
        }
        assertTrue(TaskContinuityPolicy.canLaunch(TaskContinuityPolicy.Origin.USER_LAUNCH, existingTask = false))
    }
    @Test fun existingChatAndBrowserTaskNeedNoDuplicateMoveToSameDisplay() {
        assertFalse(TaskContinuityPolicy.shouldMove(4, 4))
        assertFalse(TaskContinuityPolicy.shouldMove(0, 0))
        assertTrue(TaskContinuityPolicy.shouldMove(4, 0))
    }
    @Test fun toolbarTopSideAndVideoLayoutPreserveActiveCanvasAndDpi() {
        val active = TaskContinuityPolicy.Canvas(1264, 2780, 512)
        for (requested in listOf(TaskContinuityPolicy.Canvas(720, 1120, 320), TaskContinuityPolicy.Canvas(1280, 720, 240),
            TaskContinuityPolicy.Canvas(1080, 2354, 480))) assertEquals(active, TaskContinuityPolicy.canvas(active, requested))
    }
    @Test fun newDisplayUsesSelectedCanvasOnce() {
        val initial = TaskContinuityPolicy.Canvas(720, 1120, 320)
        assertEquals(initial, TaskContinuityPolicy.canvas(null, initial))
    }
    @Test fun reliableDirectionRequiresAgreementNotPackageNameOrPreviewShape() {
        assertEquals(StageVideoDirection.LANDSCAPE, StageVideoSignalPolicy.direction(0, 2))
        assertEquals(StageVideoDirection.PORTRAIT, StageVideoSignalPolicy.direction(1, 1))
        assertNull(StageVideoSignalPolicy.direction(null, 2))
        assertNull(StageVideoSignalPolicy.direction(-1, 1))
        assertNull(StageVideoSignalPolicy.direction(0, 1))
    }
    @Test fun directionChangesDebounceAndDoNotUsePausedOrBufferingFrames() {
        val p = StageDirectionDebounce()
        assertNull(p.update(1, StageVideoDirection.LANDSCAPE, true, 0))
        assertEquals(StageVideoDirection.LANDSCAPE, p.update(1, StageVideoDirection.LANDSCAPE, true, 700))
        assertNull(p.update(1, StageVideoDirection.PORTRAIT, false, 1000))
        assertNull(p.update(1, StageVideoDirection.PORTRAIT, true, 2000))
        assertEquals(StageVideoDirection.PORTRAIT, p.update(1, StageVideoDirection.PORTRAIT, true, 2700))
    }
    @Test fun manualVideoChoiceHasPriorityOverObservedOtherTask() {
        val p = StageCinemaPolicy(); p.enter(4)
        assertTrue(p.isManual)
        assertEquals(4, p.update(2, setOf(2, 4), setOf(2), 1000, setOf(2, 4)))
    }
    @Test fun pauseBufferAndUnknownRetainCurrentLayoutWithoutIssuingCommand() {
        val p = StageCinemaPolicy(); p.update(1, setOf(1), setOf(1), 0)
        assertEquals(1, p.update(1, setOf(1), setOf(1), 700))
        for (state in listOf(StagePlayback.PAUSED, StagePlayback.BUFFERING, StagePlayback.UNKNOWN)) {
            assertEquals(1, p.update(null, setOf(1), emptySet(), 1000, present = setOf(1)))
            assertFalse(TaskContinuityPolicy.canTransport(TaskContinuityPolicy.Origin.LAYOUT, state, state))
        }
        assertNull(p.update(null, emptySet(), emptySet(), 3000))
    }
    @Test fun lockResumeAndWorkgroupGeometryKeepCanvasWhenDisplayRetained() {
        val canvas = TaskContinuityPolicy.Canvas(720, 1120, 320)
        repeat(10) { assertSame(canvas, TaskContinuityPolicy.canvas(canvas, TaskContinuityPolicy.Canvas(1280, 720, 240))) }
    }
}
