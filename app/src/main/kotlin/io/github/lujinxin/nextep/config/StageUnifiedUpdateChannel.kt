package io.github.x647368maker.atomicworkbench.config

/** Old "stable only" selections cannot strand users when there is one channel. */
internal object StageUnifiedUpdateChannel {
    fun fromLegacySelection(selection: String?): UpdatePolicy.Channel =
        UpdatePolicy.Channel.DEV // DEV historically means accept both dev and stable.
}
