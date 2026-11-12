package io.github.x647368maker.atomicworkbench.config

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class StageDonationAvatarTest {
    @Test fun originalUserPaymentAvatarsAreTinyValidWebPFiles() {
        val avatars=listOf(StageDonationAvatar.WECHAT,StageDonationAvatar.ALIPAY)
        val expectedHashes=listOf(
            "0187b5ea8f6fa3ee7b2afe9e262447fc99383a7a0526f08841b38b3fcdc11924",
            "e7ad5d30cade9b1936e75052616aaa7bc532ce1d1fec6eb08c71be96f9cb684c"
        )
        avatars.forEachIndexed { index, content ->
            val bytes=Base64.getDecoder().decode(content)
            assertTrue(bytes.size in 600..1400)
            assertEquals("RIFF",String(bytes.copyOfRange(0,4),Charsets.US_ASCII))
            assertEquals("WEBP",String(bytes.copyOfRange(8,12),Charsets.US_ASCII))
            val sha=MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expectedHashes[index],sha)
        }
        assertNotEquals(avatars[0],avatars[1])
    }
}
