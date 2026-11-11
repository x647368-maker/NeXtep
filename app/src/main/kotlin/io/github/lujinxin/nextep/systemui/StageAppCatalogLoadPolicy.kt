package io.github.x647368maker.atomicworkbench.systemui

/**
 * A failed or empty cross-profile query is not proof that no launchable apps exist.
 * Retry only the current user. No polling, activity launch, or task mutation.
 */
internal object StageAppCatalogLoadPolicy {
    fun <T> load(
        allProfiles: () -> List<T>,
        currentUser: () -> List<T>,
        onFullFailure: (Throwable) -> Unit = {},
    ): List<T> {
        val all = runCatching { allProfiles() }.getOrElse { error ->
            onFullFailure(error)
            emptyList()
        }
        return if (all.isNotEmpty()) all else currentUser()
    }
}
