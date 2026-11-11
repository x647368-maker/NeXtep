package io.github.x647368maker.atomicworkbench.workspace

import org.junit.Assert.*
import org.junit.Test

class StageY700WechatTaskPolicyTest {
    @Test fun onlyNativeOwnedWechatOnY700IsProtected() {
        assertTrue(StageY700WechatTaskPolicy.nativeTaskMustStayOwned(
            2560,1600,"com.tencent.mm",true,1))
        assertTrue(StageY700WechatTaskPolicy.nativeTaskMustStayOwned(
            2560,1600,"com.tencent.mm",false,5))
        assertFalse(StageY700WechatTaskPolicy.nativeTaskMustStayOwned(
            2560,1600,"com.tencent.mm",false,1))
    }
    @Test fun neverAffectsOtherAppsOrAceN5() {
        for ((w,h) in listOf(1140 to 2616,2248 to 2480,2480 to 2248,1600 to 2560)) {
            assertFalse(StageY700WechatTaskPolicy.nativeTaskMustStayOwned(
                w,h,"com.tencent.mm",true,5))
        }
        for (pkg in listOf("com.example.reader","com.ss.android.ugc.aweme","com.android.settings")) {
            assertFalse(StageY700WechatTaskPolicy.nativeTaskMustStayOwned(
                2560,1600,pkg,true,5))
        }
    }
}
