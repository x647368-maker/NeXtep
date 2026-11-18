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

    @Test fun unknownCannotSupplyRetainedGeometryBoolean() {
        assertNull(StageMirrorRootContract.UNKNOWN.detachedOrNull())
        assertEquals(false, StageMirrorRootContract.RETAINED.detachedOrNull())
        assertEquals(true, StageMirrorRootContract.DETACHED.detachedOrNull())
    }
}
