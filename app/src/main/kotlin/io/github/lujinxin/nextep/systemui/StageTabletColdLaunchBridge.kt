package io.github.x647368maker.atomicworkbench.systemui

import android.app.ActivityManager
import android.content.ComponentName
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import io.github.x647368maker.atomicworkbench.framework.TaskInfoCompat
import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.StageTabletColdLaunchPolicy
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

/**
 * One-shot native-shell handoff for Y700 launches from the frequent-app bar.
 *
 * Arm before startActivity. If the ShellTaskOrganizer announces a new
 * fullscreen display-0 task, its actual task leash is fitted to the existing
 * main-window geometry before Shell processes the appearance callback.
 * This uses the existing preparedPromotion transaction path, not an image,
 * simulated touch, forced freeform mode, virtual display, or private app API.
 *
 * If an OEM does not expose a normal fullscreen task/leash early enough,
 * gracefully fall back to the original delayed presenter.
 */
internal object StageTabletColdLaunchBridge {
    private data class Ticket(
        val id: Long, val packageName: String, val userId: Int,
        val geometry: WorkspaceGeometry, val expiresAt: Long,
    )

    private var nextId = 0L
    private var pending: Ticket? = null

    @Synchronized
    fun arm(component: ComponentName, targetUser: UserHandle?,
            geometry: WorkspaceGeometry): Long? {
        val user = TaskInfoCompat.userIdentifier(targetUser ?: Process.myUserHandle()) ?: return null
        if (!StageTabletColdLaunchPolicy.arm(
                geometry.screenWidth, geometry.screenHeight, component.packageName, user)) return null
        val id = ++nextId
        pending = Ticket(id, component.packageName, user, geometry,
            SystemClock.uptimeMillis() + StageTabletColdLaunchPolicy.HANDOFF_MS)
        return id
    }

    @Synchronized
    fun cancel(id: Long?) {
        if (id != null && pending?.id == id) pending = null
    }

    @Synchronized
    fun reset() { pending = null }

    fun onTaskAppeared(info: ActivityManager.RunningTaskInfo) {
        val state = TaskInfoCompat.readWindowState(info) ?: return
        val user = TaskInfoCompat.readUserId(info)
        val pkg = info.baseActivity?.packageName ?: info.topActivity?.packageName
        val now = SystemClock.uptimeMillis()
        val ticket = synchronized(this) {
            val current = pending ?: return
            if (now > current.expiresAt) { pending = null; return }
            if (!StageTabletColdLaunchPolicy.matches(
                    current.packageName, current.userId, current.expiresAt,
                    pkg, user, now, state.displayId, state.windowingMode, state.vendorWindowed)
            ) return
            pending = null
            current
        }
        // The hook has already recorded the real task leash. Do not touch
        // any synthetic display, shell task parent, or UI thread here.
        TaskSurfaceCompat.preparePromotion(info.taskId, ticket.geometry)
            .onSuccess {
                NeXtepLog.info("y700_cold_launch", "Prefitted native task=${info.taskId} before Shell appearance")
            }.onFailure {
                NeXtepLog.warn("y700_cold_launch",
                    "Native prefit unavailable, safe legacy presentation remains", it)
            }
    }
}
