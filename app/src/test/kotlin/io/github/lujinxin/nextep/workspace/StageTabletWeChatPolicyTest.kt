package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageTabletWeChatPolicyTest {
    @Test fun y700NativeDoublePaneMustNotBeForcedIntoVirtualDisplay() {
        assertTrue(StageTabletWeChatPolicy.nativeWindowMustRemainUntouched(
            2560,1600,"com.tencent.mm",1,true))
        assertTrue(StageTabletWeChatPolicy.nativeWindowMustRemainUntouched(
            2560,1600,"com.tencent.mm",6,false))
        // Normal fullscreen WeChat still uses the real-task migration path.
        assertFalse(StageTabletWeChatPolicy.nativeWindowMustRemainUntouched(
            2560,1600,"com.tencent.mm",1,false))
    }
    @Test fun onePlusN5AndOtherAppsNeverInheritWechatSpecialCase() {
        assertFalse(StageTabletWeChatPolicy.nativeWindowMustRemainUntouched(
            1140,2616,"com.tencent.mm",1,true))
        assertFalse(StageTabletWeChatPolicy.nativeWindowMustRemainUntouched(
            2248,2480,"com.tencent.mm",1,true))
        assertFalse(StageTabletWeChatPolicy.nativeWindowMustRemainUntouched(
            2560,1600,"com.example.notes",1,true))
    }
    @Test fun recognizeExactWechatDefaultDisplayRedirectWithoutRelaunch() {
        assertTrue(StageTabletWeChatPolicy.nativeMainRedirect(
            2560,1600,"com.tencent.mm","com.tencent.mm",0))
        assertFalse(StageTabletWeChatPolicy.nativeMainRedirect(
            2560,1600,"com.tencent.mm","com.tencent.mm",19))
        assertFalse(StageTabletWeChatPolicy.nativeMainRedirect(
            2560,1600,"com.tencent.mm","com.example.app",0))
        assertFalse(StageTabletWeChatPolicy.nativeMainRedirect(
            1140,2616,"com.tencent.mm","com.tencent.mm",0))
        assertFalse(StageTabletWeChatPolicy.nativeMainRedirect(
            2248,2480,"com.tencent.mm","com.tencent.mm",0))
    }
}
