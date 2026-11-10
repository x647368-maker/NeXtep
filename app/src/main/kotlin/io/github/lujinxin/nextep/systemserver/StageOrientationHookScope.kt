package io.github.x647368maker.atomicworkbench.systemserver

/**
 * Conservative ownership, game and window-mode filter for Android window hooks.
 * Unknown ownership or reflection failure leaves Android/ColorOS in control.
 * Never force portrait sizing on games, OEM parallel windows or foreign displays.
 */
internal object StageOrientationHookScope {
    enum class Target { PHYSICAL, OWN_SLOT, OTHER, UNKNOWN }

    data class Snapshot(
        val target: Target,
        val landscapeRequested: Boolean,
        val appCategory: Int? = null,
        val windowingMode: Int? = null,
    ) {
        val isGame: Boolean get() = appCategory == 0 // ApplicationInfo.CATEGORY_GAME
        val isNormalFullscreen: Boolean get() = windowingMode == 1
    }

    fun classify(displayId: Int?, displayName: String?): Target = when {
        displayId == 0 -> Target.PHYSICAL
        displayId != null && displayId > 0 && displayName?.startsWith("NeXtep-slot-") == true &&
            displayName.removePrefix("NeXtep-slot-").toIntOrNull() in 0..3 -> Target.OWN_SLOT
        displayId == null -> Target.UNKNOWN
        else -> Target.OTHER
    }

    fun explicitlyLandscape(orientation: Int?): Boolean = orientation in setOf(0, 6, 8, 11)

    /** Only known portrait-capable, non-game fullscreen activities may be overridden. */
    private fun canOverride(snapshot: Snapshot): Boolean =
        snapshot.isNormalFullscreen && snapshot.appCategory != null && !snapshot.isGame &&
            !snapshot.landscapeRequested && snapshot.target in setOf(Target.PHYSICAL, Target.OWN_SLOT)

    /** Keep system rotation for games and native split/parallel windows. */
    fun canRewriteMainLandscape(snapshot: Snapshot): Boolean =
        snapshot.target == Target.PHYSICAL && snapshot.isNormalFullscreen &&
            snapshot.appCategory != null && !snapshot.isGame

    /** Null means call the original Android implementation. */
    fun aspectOverride(snapshot: Snapshot, topRail: Boolean, physicalAspect: Float): Float? {
        if (!physicalAspect.isFinite() || physicalAspect <= 1f || !canOverride(snapshot)) return null
        return when (snapshot.target) {
            Target.PHYSICAL -> physicalAspect
            Target.OWN_SLOT -> if (topRail) 1120f / 720f else physicalAspect
            Target.OTHER, Target.UNKNOWN -> null
        }
    }

    fun shouldBypassSizeCompat(snapshot: Snapshot): Boolean = canOverride(snapshot)

    fun snapshot(activity: Any?): Snapshot {
        if (activity == null) return Snapshot(Target.UNKNOWN, false)
        return runCatching {
            val display = invokeZeroArg(activity, "getDisplayContent")
                ?: return@runCatching Snapshot(Target.UNKNOWN, false)
            val info = invokeZeroArg(display, "getDisplayInfo")
                ?: return@runCatching Snapshot(Target.UNKNOWN, false)
            val displayId = (invokeZeroArg(display, "getDisplayId") as? Int)
                ?: (readField(info, "displayId") as? Int)
            val name = readField(info, "name") as? String
            val requested = invokeZeroArg(activity, "getRequestedOrientation") as? Int
            // ColorOS tasks in vendor windowing modes must keep their SizeCompat policy.
            val task = invokeZeroArg(activity, "getTask")
            val mode = (task?.let { invokeZeroArg(it, "getWindowingMode") } as? Int)
                ?: (invokeZeroArg(activity, "getWindowingMode") as? Int)
            val activityInfo = readField(activity, "info")
            val applicationInfo = activityInfo?.let { readField(it, "applicationInfo") }
            val category = applicationInfo?.let { readField(it, "category") } as? Int
            Snapshot(classify(displayId, name), explicitlyLandscape(requested), category, mode)
        }.getOrDefault(Snapshot(Target.UNKNOWN, false))
    }

    private fun invokeZeroArg(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.apply { isAccessible = true }?.invoke(target)

    private fun readField(target: Any, name: String): Any? =
        generateSequence(target.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == name }
            ?.apply { isAccessible = true }?.get(target)
}
