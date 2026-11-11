package io.github.x647368maker.atomicworkbench.workspace

/** Eligibility only. This never changes an Activity's windowing mode or
 * its task, nor recreates a virtual display. Y700-like wide landscape only.
 */
internal object StageTabletColdLaunchPolicy {
    const val HANDOFF_MS = 2500L

    fun arm(screenWidth: Int, screenHeight: Int, packageName: String, userId: Int): Boolean =
        StageDisplayPolicy.wideLandscapeTablet(screenWidth, screenHeight) &&
            packageName.isNotBlank() && userId >= 0

    fun matches(expectedPackage: String, expectedUser: Int, expiresAt: Long,
                actualPackage: String?, actualUser: Int?, now: Long,
                displayId: Int, windowingMode: Int, vendorWindowed: Boolean): Boolean =
        now <= expiresAt && now >= expiresAt - HANDOFF_MS &&
            expectedPackage == actualPackage && expectedUser == actualUser &&
            displayId == 0 && windowingMode == 1 && !vendorWindowed
}
