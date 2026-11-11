package io.github.x647368maker.atomicworkbench.workspace

/** Y700 ColorOS phone-ROM port: protect WeChat's OEM split-window task.
 * This policy never launches, kills, replays, freezes, or changes the app's
 * private interfaces. The native task remains on its actual display.
 */
internal object StageTabletWeChatPolicy {
    private const val WECHAT = "com.tencent.mm"

    fun nativeWindowMustRemainUntouched(
        screenWidth: Int, screenHeight: Int, app: String,
        mode: Int, vendorWindowed: Boolean
    ): Boolean =
        StageDisplayPolicy.wideTablet(screenWidth, screenHeight) &&
            app == WECHAT && (vendorWindowed || mode != 1)

    fun nativeMainRedirect(
        screenWidth: Int, screenHeight: Int, pendingApp: String?,
        observedApp: String?, observedDisplay: Int
    ): Boolean =
        StageDisplayPolicy.wideTablet(screenWidth, screenHeight) &&
            pendingApp == WECHAT && observedApp == WECHAT && observedDisplay == 0
}
