package io.github.x647368maker.atomicworkbench.launcher

import android.content.Context
import io.github.x647368maker.atomicworkbench.trigger.TriggerBroadcastContract

object LauncherPackageResolver {
    fun isCurrentHome(context: Context): Boolean =
        TriggerBroadcastContract.resolveHomePackage(context) == context.packageName

    fun isWorkspaceSurface(context: Context): Boolean =
        isCurrentHome(context) || context.packageName == ASSISTANT_SCREEN_PACKAGE

    fun isAssistantScreen(context: Context): Boolean =
        context.packageName == ASSISTANT_SCREEN_PACKAGE

    private const val ASSISTANT_SCREEN_PACKAGE = "com.coloros.assistantscreen"
}
