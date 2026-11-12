package io.github.x647368maker.atomicworkbench.config

/**
 * Only the URL decoded from the owner's supplied Alipay receipt QR is eligible
 * for explicit ACTION_VIEW. The wxp:// personal receipt payload is NOT an
 * authorized third-party app-payment API and must never be deep-linked.
 *
 * No payment SDK, backend, permissions, transaction confirmation or auto-launch.
 */
internal object StageDonationLinkPolicy {
    const val ALIPAY_RECEIPT_URL = "https://qr.alipay.com/fkx12832fcf3lxl8txjpz50"
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"
    const val WECHAT_PACKAGE = "com.tencent.mm"

    fun isApprovedReceiptLink(candidate: String): Boolean =
        candidate == ALIPAY_RECEIPT_URL &&
            candidate.startsWith("https://qr.alipay.com/") &&
            !candidate.contains('#') &&
            !candidate.contains('?')

    fun mayOpenWithoutUserTap(userTapped: Boolean): Boolean = false
}
