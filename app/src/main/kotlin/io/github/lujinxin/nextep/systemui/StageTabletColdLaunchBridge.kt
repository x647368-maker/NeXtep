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
        var attempts: Int = 0,
        var inFlight: Boolean = false,
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

    fun onTaskAppeared(info: ActivityManager.RunningTaskInfo) = onTaskObserved(info)

    /** A first onTaskAppeared can precede availability of its real task leash.
     * Previously the one-shot was consumed before preparePromotion succeeded;
     * when that failed, the real app briefly appeared fullscreen until a later
     * presenter poll. Retry on real onTaskInfoChanged events (max three), with
     * the same exact package/user/display/WM checks. No timer or polling.
     */
    fun onTaskObserved(info: ActivityManager.RunningTaskInfo) {
        val state = TaskInfoCompat.readWindowState(info) ?: return
        val user = TaskInfoCompat.readUserId(info)
        val pkg = info.baseActivity?.packageName ?: info.topActivity?.packageName
        val now = SystemClock.uptimeMillis()
        val ticket = synchronized(this) {
            val current = pending ?: return
            if (!StageTabletColdLaunchPolicy.mayRetryPrefit(
                    current.attempts, current.expiresAt, now)) {
                pending = null
                return
            }
            // Some vendor Shell task callbacks can run concurrently; only one
            // attempt can own the ticket at a time.
            if (current.inFlight) return
            if (!StageTabletColdLaunchPolicy.matches(
                    current.packageName, current.userId, current.expiresAt,
                    pkg, user, now, state.displayId, state.windowingMode, state.vendorWindowed)
            ) return
            current.attempts += 1
            current.inFlight = true
            current
        }
        // Once the real task is already presented, a late callback must not
        // recreate a prepared promotion that might overwrite its final fit.
        if (TaskSurfaceCompat.ownsMainTask(info.taskId)) {
            synchronized(this) {
                if (pending?.id == ticket.id) pending = null
                ticket.inFlight = false
            }
            return
        }
        // Shell has already published ownership information. Do not touch
        // any synthetic display, parent container, or third-party app API.
        TaskSurfaceCompat.preparePromotion(info.taskId, ticket.geometry)
            .onSuccess {
                val superseded = synchronized(this) {
                    ticket.inFlight = false
                    val replaced = pending?.id != ticket.id
                    if (!replaced) pending = null
                    replaced
                }
                // The user may tap a second app while the vendor Shell is
                // processing the first callback. Never retain a prepared
                // transform for the superseded launch or a task already shown.
                if (superseded || TaskSurfaceCompat.ownsMainTask(info.taskId)) {
                    TaskSurfaceCompat.cancelPreparedPromotion(info.taskId)
                }
                NeXtepLog.info("y700_cold_launch",
                    "Prefitted task=${info.taskId} attempt=${ticket.attempts} superseded=$superseded")
            }.onFailure { error ->
                synchronized(this) {
                    ticket.inFlight = false
                    if (pending?.id == ticket.id &&
                        !StageTabletColdLaunchPolicy.mayRetryPrefit(
                            ticket.attempts, ticket.expiresAt, SystemClock.uptimeMillis())) {
                        pending = null
                    }
                }
                NeXtepLog.warn("y700_cold_launch",
                    "Native prefit not yet available task=${info.taskId} attempt=${ticket.attempts}; legacy fallback retained",
                    error)
            }
    }
}
