package io.github.x647368maker.atomicworkbench.framework

import android.app.ActivityManager
import android.os.SystemClock
import android.view.Display
import io.github.x647368maker.atomicworkbench.logging.NeXtepLog
import io.github.x647368maker.atomicworkbench.workspace.StageTabletColdLaunchPolicy
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

/**
 * Single, expiring Y700-specific first-frame reservation.
 * Called from a *user initiated* launch before startActivity. The existing
 * ShellTaskOrganizer onTaskAppeared callback attaches it to a real live leash
 * before Shell commits its task appearance transaction.
 *
 * No additional task, virtual display, bitmap, polling, or app lifecycle call.
 * Fail open to the original post-launch presentLaunchedMain flow on any mismatch.
 */
internal object StageTabletColdLaunch {
    private data class Pending(
        val targetPackage: String,
        val userId: Int,
        val geometry: WorkspaceGeometry,
        val startedAt: Long,
    )
    @Volatile private var pending: Pending? = null

    fun arm(packageName: String, userId: Int, geometry: WorkspaceGeometry): Boolean {
        if (!StageTabletColdLaunchPolicy.eligible(
                geometry.screenWidth,geometry.screenHeight,packageName)) return false
        synchronized(this) {
            pending = Pending(packageName,userId,geometry,SystemClock.uptimeMillis())
        }
        return true
    }

    fun cancel(packageName: String) {
        synchronized(this) {
            if (pending?.targetPackage==packageName) pending=null
        }
    }

    fun onTaskAppeared(info: ActivityManager.RunningTaskInfo, leash: Any?) {
        if (leash==null) return
        val state = TaskInfoCompat.readWindowState(info) ?: return
        if (state.displayId != Display.DEFAULT_DISPLAY || state.vendorWindowed ||
            state.windowingMode != 1 || info.taskId <= 0) return
        val packages = listOfNotNull(info.topActivity?.packageName,
            info.baseActivity?.packageName,info.baseIntent?.component?.packageName)
        val now=SystemClock.uptimeMillis()
        val reservation = synchronized(this) {
            val p=pending ?: return@synchronized null
            if (now < p.startedAt || now-p.startedAt > StageTabletColdLaunchPolicy.WINDOW_MS) {
                pending=null
                return@synchronized null
            }
            if (packages.none {
                    StageTabletColdLaunchPolicy.matches(it, info.userId,
                        p.targetPackage,p.userId,p.startedAt,now)
                }) return@synchronized null
            pending=null
            p
        } ?: return
        TaskSurfaceCompat.preparePromotion(info.taskId,reservation.geometry)
            .onSuccess {
                NeXtepLog.info("tablet_cold_launch",
                    "Prepared native first frame taskId=${info.taskId} without full-screen entry")
            }
            .onFailure {
                NeXtepLog.warn("tablet_cold_launch",
                    "First frame reservation unavailable; normal presentation remains active",it)
            }
    }
}
