package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageMirrorRootContractTest {
    @Test fun onlyMatchingReadOnlyFlagValuesIdentifyContract() {
        val values = listOf<Boolean?>(null, false, true)
        for (a in values) for (b in values) {
            val expected = when {
                a == true && b == true -> StageMirrorRootContract.DETACHED
                a == false && b == false -> StageMirrorRootContract.RETAINED
                else -> StageMirrorRootContract.UNKNOWN
            }
            assertEquals(expected, StageMirrorRootContract.fromFlagReads(a,b))
        }
    }

    @Test fun distinguishesMissingFromInconsistentFlagAndReflectionFailure() {
        val missing=StageMirrorRootObservation.fromReads(false,true)
        assertEquals(StageMirrorRootContract.UNKNOWN,missing.contract)
        assertEquals("missing_key_or_empty_package",missing.reason)
        assertEquals("inconsistent_reads",StageMirrorRootObservation.fromReads(true,false).reason)
        assertEquals("invalid_read_type",StageMirrorRootObservation.fromReads(null,true).reason)
        val error=StageMirrorRootObservation.error("package_load",
            java.lang.reflect.InvocationTargetException(SecurityException("private detail")))
        assertEquals(StageMirrorRootContract.UNKNOWN,error.contract)
        assertEquals("package_load:SecurityException",error.diagnostic())
        assertEquals("flag_read",StageMirrorRootObservation.fromReads(true,true).reason)
    }

    @Test fun evidenceTrialRequiresExactDebugScopeAndExplicitDiagnostic() {
        val missing=StageMirrorRootObservation.fromReads(false,true)
        for(scope in listOf(false,true))for(armed in listOf(false,true)) {
            val expected=if(scope && armed)StageMirrorRootContract.DETACHED
                else StageMirrorRootContract.UNKNOWN
            assertEquals(expected,StageMirrorEvidenceTrial.resolve(missing,scope,armed))
        }
        assertEquals(StageMirrorRootContract.UNKNOWN,missing.contract)
    }

    @Test fun evidenceTrialDoesNotOverrideInconsistentOrContradictoryRead() {
        for(obs in listOf(StageMirrorRootObservation.fromReads(true,false),
                StageMirrorRootObservation.fromReads(null,false),
                StageMirrorRootObservation.fromReads(false,false))) {
            assertEquals(StageMirrorRootContract.UNKNOWN,
                StageMirrorEvidenceTrial.resolve(obs,true,true))
        }
        val retained=StageMirrorRootObservation.fromReads(false,false)
        assertEquals(StageMirrorRootContract.RETAINED,
            StageMirrorEvidenceTrial.resolve(retained,false,true))
        val detached=StageMirrorRootObservation.fromReads(true,true)
        assertEquals(StageMirrorRootContract.DETACHED,
            StageMirrorEvidenceTrial.resolve(detached,false,false))
    }

    // Drive the actual capture lifetime and the same session rejection branch
    // used by tryConcurrentLiveSwap. This proves admission retry, not GPU success.
    private fun attempt(observation:StageMirrorRootObservation,scope:Boolean,
                        armed:Boolean,now:Long):StageMirrorRootContract? {
        val policy=StageConcurrentNativeTrialPolicy
        if(!policy.allowed(true,true,false,now))return null
        val contract=StageMirrorEvidenceTrial.resolve(observation,scope,armed)
        if(contract==StageMirrorRootContract.UNKNOWN) {
            val reason=if(StageMirrorEvidenceTrial.awaitingDiagnostic(observation,scope,armed))
                StageMirrorEvidenceTrial.DIAGNOSTIC_REQUIRED else "mirror_root_unknown"
            if(!policy.transientMirrorOutcome("fallback:"+reason))
                policy.disableForSession("OEM mirror unavailable: fallback:"+reason)
        }
        return contract
    }

    @Test fun diagnosticOffThenArmedAllowsNextExplicitTap() {
        val policy=StageConcurrentNativeTrialPolicy
        val capture=io.github.x647368maker.atomicworkbench.logging.StageHandoffEvidenceBuffer()
        val observation=StageMirrorRootObservation.fromReads(false,true)
        policy.resetForTests()
        try {
            assertEquals(StageMirrorRootContract.UNKNOWN,attempt(observation,true,capture.ticket(100L)!=null,100L))
            assertTrue(policy.allowed(true,true,false,101L))
            assertTrue(capture.arm(102L,102L,100L))
            assertEquals(StageMirrorRootContract.DETACHED,attempt(observation,true,capture.ticket(103L)!=null,103L))
            assertFalse(policy.allowed(true,true,true,103L))
        } finally { policy.resetForTests() }
    }

    @Test fun expiredDiagnosticCanBeRearmedWithoutResettingSession() {
        val policy=StageConcurrentNativeTrialPolicy
        val capture=io.github.x647368maker.atomicworkbench.logging.StageHandoffEvidenceBuffer()
        val observation=StageMirrorRootObservation.fromReads(false,true)
        policy.resetForTests()
        try {
            assertTrue(capture.arm(100L,100L,100L))
            assertEquals(StageMirrorRootContract.DETACHED,attempt(observation,true,capture.ticket(101L)!=null,101L))
            assertEquals(StageMirrorRootContract.UNKNOWN,attempt(observation,true,capture.ticket(200L)!=null,200L))
            assertTrue(policy.allowed(true,true,false,201L))
            assertTrue(capture.arm(202L,202L,100L))
            assertEquals(StageMirrorRootContract.DETACHED,attempt(observation,true,capture.ticket(203L)!=null,203L))
        } finally { policy.resetForTests() }
    }

    @Test fun retryDoesNotRelaxOtherUnknownsOrExistingOemDenial() {
        val policy=StageConcurrentNativeTrialPolicy
        val missing=StageMirrorRootObservation.fromReads(false,true)
        for(obs in listOf(missing,StageMirrorRootObservation.fromReads(true,false),
                StageMirrorRootObservation.fromReads(null,false),StageMirrorRootObservation.fromReads(false,false),
                StageMirrorRootObservation(StageMirrorRootContract.UNKNOWN,"other_failure"))) {
            for(scope in listOf(false,true))for(armed in listOf(false,true)) {
                assertEquals(obs==missing && scope && !armed,
                    StageMirrorEvidenceTrial.awaitingDiagnostic(obs,scope,armed))
            }
        }
        assertFalse(policy.transientMirrorOutcome("fallback:mirror_root_unknown"))
        assertFalse(policy.transientMirrorOutcome("fallback:no_owned_main_task_mirror_oem_denied"))
        policy.resetForTests()
        try {
            assertEquals(StageMirrorRootContract.UNKNOWN,attempt(missing,false,false,100L))
            assertNull(attempt(missing,false,true,101L))
            policy.resetForTests()
            policy.disableForSession("OEM explicitly denied")
            assertNull(attempt(missing,true,true,102L))
        } finally { policy.resetForTests() }
    }

    @Test fun unknownCannotSupplyRetainedGeometryBoolean() {
        assertNull(StageMirrorRootContract.UNKNOWN.detachedOrNull())
        assertEquals(false, StageMirrorRootContract.RETAINED.detachedOrNull())
        assertEquals(true, StageMirrorRootContract.DETACHED.detachedOrNull())
    }
}
