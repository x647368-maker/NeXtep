package io.github.x647368maker.atomicworkbench.workspace

/** Only the physical Y700-wide landscape canvas can opt into pre-positioning.
 * This is a native task leash reservation, not a screenshot, process freeze,
 * private app call, synthetic input or a second display.
 */
internal object StageTabletColdLaunchPolicy {
    const val WINDOW_MS = 2200L
    fun eligible(width: Int, height: Int, packageName: String): Boolean =
        StageDisplayPolicy.wideLandscapeTablet(width,height) &&
            packageName.isNotBlank() &&
            packageName !in setOf("com.android.systemui",
                "io.github.x647368maker.atomicworkbench",
                "com.tencent.mm")

    fun matches(packageName: String, userId: Int, targetPackage: String,
        targetUser: Int, launchedAt: Long, now: Long): Boolean =
        packageName == targetPackage && userId == targetUser &&
        now >= launchedAt && now-launchedAt<=WINDOW_MS
}
