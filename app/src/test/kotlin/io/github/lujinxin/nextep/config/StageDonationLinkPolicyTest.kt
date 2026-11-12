package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test

class StageDonationLinkPolicyTest {
    @Test fun ownedAlipayQrIsOnlyApprovedTarget() {
        assertTrue(StageDonationLinkPolicy.isApprovedReceiptLink("https://qr.alipay.com/fkx12832fcf3lxl8txjpz50"))
        for (x in listOf("http://qr.alipay.com/fkx12832fcf3lxl8txjpz50",
            "https://qr.alipay.com/other", "https://qr.alipay.com.evil.org/fkx12832fcf3lxl8txjpz50",
            "alipays://platformapi/startapp?appId=20000056",
            "wxp://f2f0wS6dugkH292zD4aLKIJU4x7M2F85ode1o6dsJZBvPPA",
            "https://qr.alipay.com/fkx12832fcf3lxl8txjpz50?redirect=evil")) {
            assertFalse(x,StageDonationLinkPolicy.isApprovedReceiptLink(x))
        }
    }
    @Test fun paymentNeverBeginsWithoutExplicitUserAction() {
        assertFalse(StageDonationLinkPolicy.mayOpenWithoutUserTap(false))
        assertFalse(StageDonationLinkPolicy.mayOpenWithoutUserTap(true))
    }
}
