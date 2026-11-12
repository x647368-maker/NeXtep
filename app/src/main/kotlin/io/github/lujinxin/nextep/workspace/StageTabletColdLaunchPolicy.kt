package io.github.x647368maker.atomicworkbench.workspace

/** Eligibility only. This never changes an Activity's windowing mode or
 * its task, nor recreates a virtual display. Y700-like wide landscape only.
 */
internal object StageTabletColdLaunchPolicy {
    const val HANDOFF_MS = 2500L
    // Shell may announce the task before a usable leash is published. Retry
    // only on subsequent real task callbacks, never by polling.
    const val MAX_PREFIT_ATTEMPTS = 3

    fun mayRetryPrefit(attempts: Int, expiresAt: Long, now: Long): Boolean =
        attempts < MAX_PREFIT_ATTEMPTS && now <= expiresAt

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
