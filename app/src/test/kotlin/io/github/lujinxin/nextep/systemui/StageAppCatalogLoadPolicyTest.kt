package io.github.x647368maker.atomicworkbench.systemui

import org.junit.Assert.*
import org.junit.Test

class StageAppCatalogLoadPolicyTest {
    @Test fun healthyCatalogNeverTriggersExtraUserQuery() {
        var fallbackQueries = 0
        val apps = StageAppCatalogLoadPolicy.load(
            allProfiles = { listOf("regular", "clone") },
            currentUser = { fallbackQueries++; listOf("regular") },
        )
        assertEquals(listOf("regular", "clone"), apps)
        assertEquals(0, fallbackQueries)
    }

    @Test fun deniedCrossProfileQueryKeepsCurrentUserAppsAccessible() {
        var failures = 0
        val apps = StageAppCatalogLoadPolicy.load(
            allProfiles = { throw SecurityException("Clone profile is restricted") },
            currentUser = { listOf("camera", "messages") },
            onFullFailure = { failures++ },
        )
        assertEquals(listOf("camera", "messages"), apps)
        assertEquals(1, failures)
    }

    @Test fun emptyOEMQueryStillTriesPrimaryApps() {
        val apps = StageAppCatalogLoadPolicy.load(
            allProfiles = { emptyList<String>() },
            currentUser = { listOf("wechat") },
        )
        assertEquals(listOf("wechat"), apps)
    }

    @Test fun trulyEmptyCatalogIsNotFakedAsValidApplications() {
        val apps = StageAppCatalogLoadPolicy.load(
            allProfiles = { emptyList<String>() },
            currentUser = { emptyList() },
        )
        assertTrue(apps.isEmpty())
    }
}
