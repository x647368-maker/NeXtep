package io.github.x647368maker.atomicworkbench.systemui

import android.view.MotionEvent
import android.view.View
import io.github.x647368maker.atomicworkbench.launcher.TouchCoordinateMapper
import io.github.x647368maker.atomicworkbench.workspace.WorkspaceGeometry

/** Native shade owns its drawing, input region and MotionEvents. Main/slot transforms are unrelated. */
object SystemUiRootTransformController {
    fun observeRoot(view: View) { StageRuntimeIdentity.install(view.context); SystemUiPanelMonitor.observe(view) }
    fun setActive(requestedActive: Boolean, fitExistingShade: Boolean = true) {
        StageRuntimeIdentity.bootstrap()
        if(!requestedActive) AtomicPopupMenu.dismissAll()
    }
    fun setGeometry(value: WorkspaceGeometry) = Unit
    fun mapToContent(view: View, event: MotionEvent?): TouchCoordinateMapper.Transform? = null
    fun mapToScreen(event: MotionEvent?, transform: TouchCoordinateMapper.Transform?) = Unit
    fun mapWindowLocationToContent(view: View, location: IntArray) = Unit
    fun beginShadeTransform(view: View): Boolean { observeRoot(view);return false }
    fun beginObservedShadeTransform(): Boolean = false
    fun isNotificationShadeRoot(view: View): Boolean = SystemUiPanelMonitor.isPanel(view)
}
