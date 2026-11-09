package io.github.x647368maker.atomicworkbench.systemui

import android.view.View
import android.view.WindowManager
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

/** Read-only observation: never install custom shade insets, flags or coordinate mapping. */
internal object SystemUiShadeInputController {
    fun apply(view: View, geometry: WorkspaceGeometry) { SystemUiPanelMonitor.observe(view) }
    fun beforeLayout(view: View, params: WindowManager.LayoutParams) {
        SystemUiPanelMonitor.observe(view)
        if(SystemUiPanelMonitor.isPanel(view))view.post {SystemUiPanelMonitor.changed()}
    }
    fun restore(view: View) = Unit
}
