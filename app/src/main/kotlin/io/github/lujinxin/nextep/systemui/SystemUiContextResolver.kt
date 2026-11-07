package io.github.x647368maker.atomicworkbench.systemui

import android.content.Context

object SystemUiContextResolver {
    fun fromHookTarget(target: Any?): Context? = when (target) {
        is Context -> target.applicationContext ?: target
        else -> null
    }
}
