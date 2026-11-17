package io.github.x647368maker.atomicworkbench.systemui

import io.github.x647368maker.atomicworkbench.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageRuntimeIdentityTest {
    @Test fun runtimeDiagnosticReportsCompiledBuildNotJustSharedVersionName() {
        val expected =
            "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}+${BuildConfig.ATOMIC_BUILD_REVISION}"
        assertEquals(expected, StageRuntimeIdentity.BUILD)
        assertTrue("invalid compiled commit identifier",
            BuildConfig.ATOMIC_BUILD_REVISION == "unknown" ||
                BuildConfig.ATOMIC_BUILD_REVISION.matches(Regex("[a-fA-F0-9]{8,12}")))
        assertEquals(89, BuildConfig.VERSION_CODE)
    }
}
