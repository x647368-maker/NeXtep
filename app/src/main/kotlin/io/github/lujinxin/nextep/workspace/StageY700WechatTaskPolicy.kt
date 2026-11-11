package io.github.x647368maker.atomicworkbench.workspace

/**
 * WeChat on a phone-ROM tablet may be under vendor split/dual-window ownership.
 * Its task must never be forcibly reparented into a VirtualDisplay until WM
 * reports a normal fullscreen task. This preserves the live activity and data.
 * It deliberately does NOT claim to fix the vendor's split-window mode.
 */
internal object StageY700WechatTaskPolicy {
    fun nativeTaskMustStayOwned(width: Int, height: Int,
        packageName: String, vendorWindowed: Boolean, windowingMode: Int): Boolean =
        StageDisplayPolicy.wideLandscapeTablet(width,height) &&
            packageName == "com.tencent.mm" &&
            (vendorWindowed || windowingMode != 1)
}
