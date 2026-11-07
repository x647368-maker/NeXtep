package io.github.x647368maker.atomicworkbench.launcher

import android.content.ComponentName
import android.view.View
import io.github.x647368maker.atomicworkbench.R
import io.github.x647368maker.atomicworkbench.config.AppLanguage
import io.github.x647368maker.atomicworkbench.workspace.RecentTaskSelection
import io.github.x647368maker.atomicworkbench.workspace.SelectedRecentTask
import java.lang.ref.WeakReference

/** Read the actual centered OEM card, rather than guessing from recent-task ordering. */
object LauncherRecentTaskSelection {
    private var overview = WeakReference<View>(null)

    fun observe(view: View) {
        overview = WeakReference(view)
    }

    fun selection(): RecentTaskSelection {
        val view = overview.get() ?: return RecentTaskSelection.NotVisible
        if (!LauncherTransformController.isActive() || !view.isAttachedToWindow ||
            !view.isShown || view.windowVisibility != View.VISIBLE
        ) return RecentTaskSelection.NotVisible
        return runCatching {
            if (field(view, "mOverviewStateEnabled") != true) return@runCatching RecentTaskSelection.NotVisible
            val card = call(view, "getTaskViewNearestToCenterOfScreen")
                ?: return@runCatching RecentTaskSelection.Unavailable(AppLanguage.string(view.context, R.string.recent_center_task))
            if (call(card, "containsMultipleTasks") == true) {
                return@runCatching RecentTaskSelection.Unavailable(AppLanguage.string(view.context, R.string.recent_exit_split))
            }
            val task = checkNotNull(call(card, "getTask"))
            // ColorOS obfuscates TaskKey's fields (n/p/q in this ROM). The Task
            // accessors are stable and verified in the connected Launcher's APK.
            val taskId = call(task, "getId") as Int
            val userId = call(task, "getUserId") as Int
            val component = call(task, "getTopComponent") as? ComponentName
                ?: error("Card has no component")
            check(taskId >= 0 && userId >= 0)
            RecentTaskSelection.Selected(SelectedRecentTask(taskId, userId, component))
        }.getOrElse { RecentTaskSelection.Unavailable(AppLanguage.string(view.context, R.string.recent_unrecognized)) }
    }

    private fun field(target: Any, name: String): Any? = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == name } }.firstOrNull()
        ?.apply { isAccessible = true }?.get(target)

    private fun call(target: Any, name: String): Any? = generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { type -> type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 } }.firstOrNull()
        ?.apply { isAccessible = true }?.invoke(target)
}
