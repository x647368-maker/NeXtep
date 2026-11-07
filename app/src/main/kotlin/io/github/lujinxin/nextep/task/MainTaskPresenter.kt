package io.github.x647368maker.atomicworkbench.task

import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

interface MainTaskPresenter {
    fun present(taskId: Int, geometry: WorkspaceGeometry): Result<Unit>
    fun restore(taskId: Int): Result<Unit>
    fun reapply(taskId: Int, geometry: WorkspaceGeometry): Result<Unit>
}
