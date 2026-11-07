package io.github.x647368maker.atomicworkbench.task

import io.github.x647368maker.atomicworkbench.framework.TaskSurfaceCompat
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

class SurfaceTaskPresenter : MainTaskPresenter {
    fun presentFromFullscreen(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.present(taskId, geometry, animateFromFullscreen = true)

    fun restoreToFullscreen(taskId: Int): Result<Unit> =
        TaskSurfaceCompat.restore(taskId, animateToFullscreen = true)

    override fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.present(taskId, geometry)

    override fun restore(taskId: Int): Result<Unit> = TaskSurfaceCompat.restore(taskId)

    override fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit> =
        TaskSurfaceCompat.reapply(taskId, geometry)
}
