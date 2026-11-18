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

    @Test fun unknownCannotSupplyRetainedGeometryBoolean() {
        assertNull(StageMirrorRootContract.UNKNOWN.detachedOrNull())
        assertEquals(false, StageMirrorRootContract.RETAINED.detachedOrNull())
        assertEquals(true, StageMirrorRootContract.DETACHED.detachedOrNull())
    }
}
