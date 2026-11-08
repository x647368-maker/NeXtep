package io.github.x647368maker.atomicworkbench.config

/** Profile URL decoded from ZMW专用's official Coolapk QR card (UID screenshot agrees).
 * Package is also published by coolapk.com in its official Android download URL.
 * No private API, login, network lookup or automatic device information collection. */
internal object CoolapkFeedback {
    const val ACCOUNT = "ZMW专用"
    const val UID = "43881308"
    const val PROFILE = "https://www.coolapk.com/u/43881308?from=qr"
    const val PACKAGE = "com.coolapk.market"
    enum class Destination { APP, WEB, UNAVAILABLE }
    fun open(attempt: (url: String, packageName: String?) -> Boolean): Destination {
        if (runCatching { attempt(PROFILE, PACKAGE) }.getOrDefault(false)) return Destination.APP
        if (runCatching { attempt(PROFILE, null) }.getOrDefault(false)) return Destination.WEB
        return Destination.UNAVAILABLE
    }
    val template = """
        【台前调度反馈】

        App版本：

        手机型号：

        系统版本：

        Root / 模块环境：

        问题描述或功能建议：

        复现步骤：

        是否可以稳定复现：

        可附截图或日志（注意隐藏个人隐私）。
    """.trimIndent()
}
