package io.github.x647368maker.atomicworkbench.task

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.view.Display
import android.os.UserHandle
import io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.monitoring.WorkspaceEnergyDiagnostics

class TaskRepository(context: Context) {
    data class RetainedTask(
        val taskId: Int,
        val userId: Int,
        val baseComponent: ComponentName,
        val lastActiveTime: Long,
    )

    /** Includes recent tasks whose app process was reclaimed; a failed query is not an empty list. */
    // Runs in LSPosed-injected SystemUI, where privileged task APIs are required.
    // Keep the exemption local; ordinary APK code must not use this hidden API.
    @SuppressLint("BlockedPrivateApi")
    fun recentTasks(userId: Int): Result<List<RetainedTask>> = runCatching {
        val service = Class.forName("android.app.ActivityTaskManager")
            .getDeclaredMethod("getService").invoke(null)
        val result = service.javaClass.getMethod(
            "getRecentTasks", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).invoke(service, 256, 0, userId)
        val list = result.javaClass.getMethod("getList").invoke(result) as List<*>
        list.filterIsInstance<ActivityManager.RecentTaskInfo>().mapNotNull { info ->
            val base = info.baseIntent.component ?: info.baseActivity ?: return@mapNotNull null
            RetainedTask(
                info.taskId,
                info.javaClass.getField("userId").getInt(info),
                base,
                info.javaClass.getField("lastActiveTime").getLong(info),
            )
        }
    }

    // Same injected SystemUI context as recentTasks; failures remain explicit Results.
    @SuppressLint("BlockedPrivateApi")
    fun restoreRecentTask(taskId: Int, displayId: Int): Result<Unit> = runCatching {
        val service = Class.forName("android.app.ActivityTaskManager")
            .getDeclaredMethod("getService").invoke(null)
        val options = android.app.ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        service.javaClass.getMethod(
            "startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java,
        ).invoke(service, taskId, options.toBundle())
    }

    data class TaskSnapshot(
        val taskId: Int,
        val displayId: Int,
        val component: ComponentName,
        val bounds: Rect,
        val windowingMode: Int,
        val resizeMode: Int?,
        val densityDpi: Int,
        val token: Any?,
        val userId: Int?,
        val vendorWindowed: Boolean = false,
        val requestedOrientation: Int? = null,
        val configurationOrientation: Int? = null,
    )

    private val activityManager = context.getSystemService(ActivityManager::class.java)

    fun foregroundTask(): TaskSnapshot? = try {
        runningTasks().firstOrNull { snapshot ->
            snapshot.displayId == Display.DEFAULT_DISPLAY
        }
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Foreground task query failed", error)
        null
    }

    fun findTask(taskId: Int): TaskSnapshot? = try {
        runningTasks().firstOrNull { it.taskId == taskId }
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Task lookup failed taskId=$taskId", error)
        null
    }

    /** Fresh destination-only observation. The caller retains its final global observation.
     * Missing/unsupported/failed filtered reads always fall back to the existing full query. */
    fun findTaskOnDisplay(taskId: Int, displayId: Int): TaskSnapshot? {
        val query = displayTaskQuery ?: return findTask(taskId)
        val result = runCatching {
            WorkspaceEnergyDiagnostics.taskQueries.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            val raw = io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.phase("query_binder") {
                query.second.invoke(query.first, MAX_TASK_QUERY, false, false, displayId)
                    as List<ActivityManager.RunningTaskInfo>
            }
            io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.phase("query_decode") {
                raw.firstOrNull { it.taskId == taskId }?.let(::snapshot)
                    ?.takeIf { it.displayId == displayId }
            }
        }.getOrNull()
        return result ?: findTask(taskId)
    }

    fun findTaskForComponent(
        component: ComponentName,
        userHandle: UserHandle? = null,
        allowPackageFallback: Boolean = true,
    ): TaskSnapshot? {
        val tasks = runningTasks().let { snapshots ->
            val userId = userHandle?.let(TaskInfoCompat::userIdentifier) ?: return@let snapshots
            snapshots.filter { it.userId == userId }
        }
        return tasks.firstOrNull { it.component == component }
            ?: if (allowPackageFallback) {
                tasks.firstOrNull { it.component.packageName == component.packageName }
            } else null
    }

    fun bringTaskToFront(taskId: Int): Result<Unit> = runCatching {
        val manager = checkNotNull(activityManager) { "ActivityManager unavailable" }
        manager.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_WITH_HOME)
        NeXtepLog.info("task_repository", "Brought taskId=$taskId to front")
    }

    @Suppress("DEPRECATION")
    fun runningTasks(): List<TaskSnapshot> = try {
        WorkspaceEnergyDiagnostics.taskQueries.incrementAndGet()
        val raw=io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.phase("query_binder") {
            activityManager?.getRunningTasks(MAX_TASK_QUERY).orEmpty()
        }
        io.github.x647368maker.atomicworkbench.logging.StageExchangeTelemetry.phase("query_decode") { raw.mapNotNull(::snapshot) }
    } catch (error: Throwable) {
        NeXtepLog.error("task_repository", "Running task query failed", error)
        emptyList()
    }

    private fun snapshot(info: ActivityManager.RunningTaskInfo): TaskSnapshot? {
        val component = info.topActivity ?: info.baseActivity ?: return null
        val windowState = TaskInfoCompat.readWindowState(info) ?: return null
        TaskSurfaceCompat.observeTaskState(info.taskId, windowState)
        return TaskSnapshot(
            taskId = info.taskId,
            displayId = windowState.displayId,
            component = component,
            bounds = windowState.bounds,
            windowingMode = windowState.windowingMode,
            resizeMode = windowState.resizeMode,
            densityDpi = windowState.densityDpi,
            token = windowState.token,
            userId = TaskInfoCompat.readUserId(info),
            vendorWindowed = windowState.vendorWindowed,
            configurationOrientation = TaskInfoCompat.readConfigurationOrientation(info),
            requestedOrientation = runCatching { info.javaClass.getField("topActivityInfo").get(info) as? android.content.pm.ActivityInfo }.getOrNull()?.screenOrientation,
        )
    }

    private companion object {
        @get:SuppressLint("BlockedPrivateApi")
        private val displayTaskQuery: Pair<Any, java.lang.reflect.Method>? by lazy {
            runCatching {
                val type = Class.forName("android.app.ActivityTaskManager")
                val instance = checkNotNull(type.getDeclaredMethod("getInstance").invoke(null))
                val method = type.getDeclaredMethod("getTasks", Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).apply { isAccessible = true }
                instance to method
            }.getOrNull()
        }
        const val MAX_TASK_QUERY = 64
    }
}
