package io.github.x647368maker.atomicworkbench.systemui
import org.junit.Assert.*
import org.junit.Test
class StageNativePanelIdentityTest {
    @Test fun nativeTypesIdentifyRenamedOemWindows() {
        assertTrue(StageNativePanelIdentity.matches("OEM Panel","android.widget.FrameLayout",2040))
        assertTrue(StageNativePanelIdentity.matches("OEM Panel","android.widget.FrameLayout",2014))
        assertFalse(StageNativePanelIdentity.matches("StatusBar","android.widget.FrameLayout",2000))
        assertFalse(StageNativePanelIdentity.matches("workspace","android.widget.FrameLayout",2002))
        assertFalse(StageNativePanelIdentity.matches("popup","android.widget.PopupWindow",1000))
    }
}
