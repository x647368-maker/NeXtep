package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageTransientTapPolicyTest {
    @Test fun onlyCommittedValidFirstFrameMayStartRealMove() {
        val p=StageTransientTapPolicy
        assertTrue(p.canStartMove(true,true,false))
        assertFalse(p.canStartMove(true,false,false))
        assertFalse(p.canStartMove(false,true,false))
        assertFalse(p.canStartMove(true,true,true))
    }
    @Test fun flightAndActualOwnershipAckMustBothComplete() {
        val p=StageTransientTapPolicy
        assertFalse(p.canHandoff(false,false))
        assertFalse(p.canHandoff(true,false))
        assertFalse(p.canHandoff(false,true))
        assertTrue(p.canHandoff(true,true))
    }
    @Test fun capturePermitIsOneAttemptOnlyAndRevocationIsPermanent() {
        val old=StageTapCapturePermit()
        assertTrue(old.allows(0,160))
        old.revoke();old.revoke()
        assertTrue(old.revoked)
        assertFalse(old.allows(1,160))
        assertTrue(StageTapCapturePermit().allows(1,160))
    }
    @Test fun capturePermitRejectsDeadlineAtEveryStage() {
        val permit=StageTapCapturePermit()
        assertTrue(permit.allows(159,160))
        assertFalse(permit.allows(160,160))
        assertFalse(permit.allows(161,160))
    }
    @Test fun workerRechecksAfterInFlightCaptureAndSkipsRevokedWork() {
        val permit=StageTapCapturePermit()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result=executor.submit<Boolean> {
                check(permit.allows(0,160))
                entered.countDown()
                check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                permit.allows(1,160)
            }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            permit.revoke()
            release.countDown()
            assertFalse(result.get(5,java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown();executor.shutdownNow()
        }
    }
    @Test fun doNotStartSecondCaptureAfterOneShotBudget() {
        assertTrue(StageTransientTapPolicy.canCaptureSecond(159,160))
        assertFalse(StageTransientTapPolicy.canCaptureSecond(160,160))
        assertFalse(StageTransientTapPolicy.canCaptureSecond(161,160))
        assertEquals(300L,StageTransientTapPolicy.FLIGHT_MS)
        assertTrue(StageTransientTapPolicy.CAPTURE_BUDGET_MS+
            StageTransientTapPolicy.FIRST_COMMIT_BUDGET_MS+
            StageTransientTapPolicy.FLIGHT_MS+
            StageTransientTapPolicy.HANDOFF_BUDGET_MS<
            StageTransientTapPolicy.MAX_ATTEMPT_MS)
    }
}
